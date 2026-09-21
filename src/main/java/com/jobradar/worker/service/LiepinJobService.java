package com.jobradar.worker.service;

import com.jobradar.application.service.ConfigService;
import com.jobradar.application.service.LiepinService;
import com.jobradar.worker.dto.JobProgressMessage;
import com.jobradar.worker.liepin.Liepin;
import com.jobradar.worker.liepin.LiepinAccountPacing;
import com.jobradar.worker.liepin.LiepinConfig;
import com.jobradar.worker.manager.PlaywrightManager;
import com.jobradar.worker.manager.ReadRequestStatus;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 猎聘任务服务
 * 负责猎聘平台的自动投递任务管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiepinJobService implements JobPlatformService {

    private static final String PLATFORM = "liepin";
    private static final int RECENT_MESSAGE_LIMIT = 8;
    private static final long STOP_GRACE_MILLIS = 5_000L;

    private static ScheduledExecutorService createFallbackScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Liepin-Resume-Fallback");
            thread.setDaemon(true);
            return thread;
        });
    }

    public enum TaskState {
        IDLE,
        WAITING,
        RUNNING,
        RESTING,
        STOPPING,
        CANCELLED,
        PAUSED,
        COMPLETED,
        FAILED
    }

    private enum RetryMode {
        NORMAL,
        AI_SINGLE,
        AI_BATCH,
        SUSPENDED_BATCH
    }

    enum BatchRetryAction {
        COMPLETE,
        CONTINUE,
        PAUSE
    }

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Liepin> liepinProvider;
    private final LiepinService liepinService;

    @Autowired
    @Qualifier("deliveryExecutor")
    private java.util.concurrent.Executor deliveryExecutor = ForkJoinPool.commonPool();

    @Autowired(required = false)
    @Qualifier("deliveryResumeScheduler")
    private ScheduledExecutorService deliveryResumeScheduler = createFallbackScheduler();

    // 运行状态标志
    private volatile boolean isRunning = false;

    // 任务生命周期状态；isRunning 保留给已有调用方作兼容字段。
    private volatile TaskState taskState = TaskState.IDLE;
    private volatile int lastDeliveredCount = 0;
    private volatile long runId = 0L;
    private volatile Map<String, Object> deliverySummary = emptyDeliverySummary();
    private volatile ReadRequestStatus lastReadRequestStatus =
            ReadRequestStatus.browser(false, "尚未读取猎聘搜索");

    // 停止请求标志
    private volatile boolean shouldStop = false;
    private volatile boolean forcedCancellation = false;

    // 最近一次任务消息，供页面轮询显示真实状态
    private volatile String lastMessage = "尚未启动投递任务";
    private volatile String lastMessageType = "idle";
    private volatile long lastMessageAt = System.currentTimeMillis();
    private final StatusHistory recentStatus = new StatusHistory(RECENT_MESSAGE_LIMIT);
    private final Object pendingLock = new Object();
    private final Object lifecycleMonitor = new Object();
    private volatile Liepin.GreetingRequest pendingGreeting;
    private volatile CompletableFuture<Liepin.GreetingAction> pendingDecision;
    private volatile Consumer<JobProgressMessage> activeProgressCallback;
    private volatile Map<String, Object> aiSummary = emptyAiSummary();
    private volatile RetryMode retryMode = RetryMode.NORMAL;
    private volatile int batchRetryRound = 0;
    private volatile int batchRetryInitialPending = 0;
    private volatile int batchRetryRemaining = 0;
    private volatile int batchRetryDelivered = 0;
    private volatile String batchRetryReason = "";
    private volatile Set<Long> pendingRetryJobIds = Set.of();
    private volatile Liepin activeLiepin;
    private volatile Liepin.RestRequest restRequest;
    private volatile String restReason;
    private volatile long restUntil;
    private volatile int restAttempt;
    private volatile ScheduledFuture<?> scheduledResume;
    private final LiepinAccountPacing accountPacing = new LiepinAccountPacing();
    private final AtomicInteger activeTaskLeases = new AtomicInteger();
    private final AtomicBoolean taskLeaseHeld = new AtomicBoolean();
    private final AtomicInteger activeWorkerTasks = new AtomicInteger();
    private final AtomicBoolean restartBlocked = new AtomicBoolean();
    private final SuspendedRetryRun suspendedRetry = new SuspendedRetryRun();

    private static Map<String, Object> emptyCollectionSummary() {
        return Map.ofEntries(
                Map.entry("searchPages", 0),
                Map.entry("jobsFetched", 0),
                Map.entry("jobsPersisted", 0),
                Map.entry("jobsInserted", 0),
                Map.entry("jobsExisting", 0),
                Map.entry("searchFailures", 0),
                Map.entry("searchPersistDurationMs", 0L),
                Map.entry("detailAttempts", 0),
                Map.entry("detailHits", 0),
                Map.entry("detailMisses", 0),
                Map.entry("detailCacheSkips", 0),
                Map.entry("detailDurationMs", 0L),
                Map.entry("lastKeyword", ""),
                Map.entry("lastPage", 0),
                Map.entry("previewJobs", List.of())
        );
    }

    private static Map<String, Object> emptyDeliverySummary() {
        return Map.ofEntries(
                Map.entry("scanned", 0),
                Map.entry("salaryEligible", 0),
                Map.entry("salarySkipped", 0),
                Map.entry("otherSkipped", 0),
                Map.entry("delivered", 0),
                Map.entry("retryAttempts", 0),
                Map.entry("retryDirectHits", 0),
                Map.entry("retryRetained", 0),
                Map.entry("retryReasons", Map.of()),
                Map.entry("collection", emptyCollectionSummary())
        );
    }

    private static Map<String, Object> emptyAiSummary() {
        return Map.ofEntries(
                Map.entry("candidateCount", 0),
                Map.entry("cacheHits", 0),
                Map.entry("screenCalls", 0),
                Map.entry("messageCalls", 0),
                Map.entry("passed", 0),
                Map.entry("review", 0),
                Map.entry("skipped", 0),
                Map.entry("invalid", 0),
                Map.entry("invalidResults", 0),
                Map.entry("aiTimeouts", 0),
                Map.entry("aiRetryAttempts", 0),
                Map.entry("aiRetrySuccesses", 0),
                Map.entry("aiRetryable", 0),
                Map.entry("buttonFailures", 0),
                Map.entry("confirmationFailures", 0),
                Map.entry("avgLatencyMs", 0)
        );
    }

    public record ConfirmationResult(boolean accepted, String message) {
    }

    static final class StatusHistory {
        private final int limit;
        private final Deque<JobProgressMessage> messages = new ArrayDeque<>();

        StatusHistory(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("状态消息上限必须大于0");
            }
            this.limit = limit;
        }

        synchronized void clear() {
            messages.clear();
        }

        synchronized void add(JobProgressMessage message) {
            if (message == null) {
                return;
            }
            messages.addLast(message);
            while (messages.size() > limit) {
                messages.removeFirst();
            }
        }

        synchronized List<JobProgressMessage> snapshot() {
            return new ArrayList<>(messages);
        }
    }

    /** 暂缓重试独立任务槽，避免普通投递收尾覆盖它的状态。 */
    private final class SuspendedRetryRun {
        private volatile boolean isRunning;
        private volatile TaskState taskState = TaskState.IDLE;
        private volatile long runId;
        private volatile int lastDeliveredCount;
        private volatile Map<String, Object> deliverySummary = emptyDeliverySummary();
        private volatile ReadRequestStatus lastReadRequestStatus =
                ReadRequestStatus.browser(false, "尚未读取猎聘搜索");
        private volatile boolean shouldStop;
        private volatile boolean forcedCancellation;
        private volatile String lastMessage = "尚未启动暂缓岗位重试";
        private volatile String lastMessageType = "idle";
        private volatile long lastMessageAt = System.currentTimeMillis();
        private final StatusHistory recentStatus = new StatusHistory(RECENT_MESSAGE_LIMIT);
        private final Object pendingLock = new Object();
        private volatile Liepin.GreetingRequest pendingGreeting;
        private volatile CompletableFuture<Liepin.GreetingAction> pendingDecision;
        private volatile Consumer<JobProgressMessage> activeProgressCallback;
        private volatile Map<String, Object> aiSummary = emptyAiSummary();
        private volatile int batchRetryRound;
        private volatile int batchRetryInitialPending;
        private volatile int batchRetryRemaining;
        private volatile int batchRetryDelivered;
        private volatile String batchRetryReason = "";
        private volatile Set<Long> pendingRetryJobIds = Set.of();
        private volatile List<LiepinService.SuspendedRetryJob> pendingRetryJobs = List.of();
        private final AtomicBoolean leaseHeld = new AtomicBoolean();
    }

    static JobProgressMessage buildCompletionMessage(int deliveredCount) {
        return buildCompletionMessage(deliveredCount, Map.of());
    }

    static JobProgressMessage buildCompletionMessage(int deliveredCount, Map<String, Object> summary) {
        int scanned = number(summary, "scanned");
        int salarySkipped = number(summary, "salarySkipped");
        int otherSkipped = number(summary, "otherSkipped");
        String detail = scanned > 0
                ? String.format("，处理%d个岗位，薪资跳过%d个，其它筛选跳过%d个", scanned, salarySkipped, otherSkipped)
                : "";
        if (deliveredCount > 0) {
            return JobProgressMessage.success(PLATFORM,
                    String.format("投递任务完成，共发起%d个聊天%s", deliveredCount, detail));
        }
        return JobProgressMessage.warning(PLATFORM,
                String.format("投递任务完成，本轮未成功发起聊天%s", detail));
    }

    private static int number(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    static JobProgressMessage buildPausedMessage(int deliveredCount) {
        return JobProgressMessage.warning(PLATFORM,
                String.format("投递任务已暂停，本轮已发起%d个聊天", deliveredCount));
    }

    static JobProgressMessage buildRateLimitedMessage(int deliveredCount, String detail) {
        String suffix = detail == null || detail.isBlank() ? "" : "：" + detail;
        return JobProgressMessage.error(PLATFORM,
                String.format("检测到平台风控或节奏中断，任务已暂停，本轮已发起%d个聊天%s",
                        deliveredCount, suffix));
    }

    static JobProgressMessage buildSearchRetryMessage(int deliveredCount, String detail) {
        String suffix = detail == null || detail.isBlank() ? "" : "：" + detail;
        return JobProgressMessage.warning(PLATFORM,
                String.format("搜索结果暂不可用，任务已暂停等待重试，本轮已发起%d个聊天%s",
                        deliveredCount, suffix));
    }

    static BatchRetryAction decideBatchRetry(int previousPending, int currentPending,
                                             Liepin.ExecutionOutcome outcome) {
        if (outcome != Liepin.ExecutionOutcome.COMPLETED) {
            return BatchRetryAction.PAUSE;
        }
        if (currentPending == 0) {
            return BatchRetryAction.COMPLETE;
        }
        return currentPending < previousPending ? BatchRetryAction.CONTINUE : BatchRetryAction.PAUSE;
    }

    static JobProgressMessage buildBatchRetryCompletionMessage(int deliveredCount, int rounds) {
        if (deliveredCount > 0) {
            return JobProgressMessage.success(PLATFORM,
                    String.format("AI超时岗位批量重试完成，共发起%d个聊天，执行%d轮", deliveredCount, rounds));
        }
        return JobProgressMessage.warning(PLATFORM,
                String.format("AI超时岗位批量重试完成，本轮没有新增聊天，执行%d轮", rounds));
    }

    static JobProgressMessage buildBatchRetryPausedMessage(int deliveredCount, int remaining, String reason) {
        String suffix = reason == null || reason.isBlank() ? "" : "：" + reason;
        return JobProgressMessage.warning(PLATFORM,
                String.format("AI超时批量重试已暂停，剩余%d个，累计发起%d个聊天%s",
                        remaining, deliveredCount, suffix));
    }

    static JobProgressMessage buildSuspendedBatchRetryCompletionMessage(int deliveredCount, int rounds) {
        String message = deliveredCount > 0
                ? String.format("筛选后暂缓岗位批量重试完成，共发起%d个聊天，执行%d轮", deliveredCount, rounds)
                : String.format("筛选后暂缓岗位批量重试完成，本轮没有新增聊天，执行%d轮", rounds);
        return deliveredCount > 0
                ? JobProgressMessage.success(PLATFORM, message)
                : JobProgressMessage.warning(PLATFORM, message);
    }

    static JobProgressMessage buildSuspendedBatchRetryCompletionMessage(
            int deliveredCount, int rounds, String detail) {
        JobProgressMessage base = buildSuspendedBatchRetryCompletionMessage(deliveredCount, rounds);
        if (detail == null || detail.isBlank()) return base;
        base.setMessage(base.getMessage() + "；" + detail);
        return base;
    }

    static JobProgressMessage buildSuspendedBatchRetryPausedMessage(int deliveredCount, int remaining, String reason) {
        String suffix = reason == null || reason.isBlank() ? "" : "：" + reason;
        return JobProgressMessage.warning(PLATFORM,
                String.format("筛选后暂缓岗位批量重试已暂停，剩余%d个，累计发起%d个聊天%s",
                        remaining, deliveredCount, suffix));
    }

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        if (!claimRun(progressCallback)) {
            publish(progressCallback, JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }
        runClaimedManaged(progressCallback);
    }

    /** 原子占用运行状态后异步执行，避免快速连续点击产生双任务。 */
    public boolean startDeliveryAsync(Consumer<JobProgressMessage> progressCallback) {
        return startDeliveryAsync(progressCallback, RetryMode.NORMAL, 0);
    }

    private boolean startDeliveryAsync(Consumer<JobProgressMessage> progressCallback,
                                       RetryMode mode, int initialPending) {
        return startDeliveryAsync(progressCallback, mode, initialPending, Set.of());
    }

    private boolean startDeliveryAsync(Consumer<JobProgressMessage> progressCallback,
                                       RetryMode mode, int initialPending,
                                       Set<Long> retryJobIds) {
        if (!claimRun(progressCallback, mode, initialPending, retryJobIds)) {
            return false;
        }
        try {
            deliveryExecutor.execute(() -> runClaimedManaged(progressCallback));
            return true;
        } catch (RuntimeException e) {
            taskState = TaskState.FAILED;
            finishRun();
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "提交投递任务失败: " + e.getMessage()));
            return false;
        }
    }

    /** 重新跑保留页，专门处理上一轮耗尽自动重试的 AI 超时岗位。 */
    public boolean retryAiTimeoutsAsync(Consumer<JobProgressMessage> progressCallback) {
        int pending = number(aiSummary, "aiRetryable");
        if (isRunning || pending < 1) {
            return false;
        }
        return startDeliveryAsync(progressCallback, RetryMode.AI_SINGLE, pending);
    }

    /** 连续处理 AI 超时待重试岗位，直到清空或本轮没有进展。 */
    public boolean retryAiTimeoutsBatchAsync(Consumer<JobProgressMessage> progressCallback) {
        int pending = number(aiSummary, "aiRetryable");
        if (isRunning || pending < 1) {
            return false;
        }
        return startDeliveryAsync(progressCallback, RetryMode.AI_BATCH, pending);
    }

    /** 连续处理启动时快照的筛选后暂缓岗位，只包含 AI 或网络异常保留的记录。 */
    public boolean retrySuspendedBatchAsync(Consumer<JobProgressMessage> progressCallback) {
        List<LiepinService.SuspendedRetryJob> suspendedJobs = liepinService.listSuspendedRetryJobs();
        if (suspendedJobs.isEmpty()) {
            return false;
        }
        if (!claimSuspendedRetryRun(progressCallback, suspendedJobs)) {
            return false;
        }
        try {
            deliveryExecutor.execute(() -> runSuspendedRetryClaimedManaged(progressCallback));
            return true;
        } catch (RuntimeException e) {
            suspendedRetry.taskState = TaskState.FAILED;
            finishSuspendedRetryRun();
            publish(suspendedRetry, JobProgressMessage.error(PLATFORM, "提交暂缓岗位重试失败: " + e.getMessage()));
            return false;
        }
    }

    private synchronized boolean claimRun(Consumer<JobProgressMessage> progressCallback) {
        return claimRun(progressCallback, RetryMode.NORMAL, 0);
    }

    private synchronized boolean claimRun(Consumer<JobProgressMessage> progressCallback,
                                          RetryMode mode, int initialPending) {
        return claimRun(progressCallback, mode, initialPending, Set.of());
    }

    private synchronized boolean claimRun(Consumer<JobProgressMessage> progressCallback,
                                          RetryMode mode, int initialPending,
                                          Set<Long> retryJobIds) {
        if (restartBlocked.get()) {
            return false;
        }
        boolean freshManualRun = mode == RetryMode.NORMAL && activeTaskLeases.get() == 0;
        if (isRunning || !acquireTaskLease()) {
            return false;
        }
        if (freshManualRun) {
            accountPacing.resetForManualRun();
        }
        isRunning = true;
        runId++;
        taskState = TaskState.WAITING;
        shouldStop = false;
        forcedCancellation = false;
        activeProgressCallback = progressCallback;
        aiSummary = emptyAiSummary();
        deliverySummary = emptyDeliverySummary();
        lastReadRequestStatus = ReadRequestStatus.browser(false, "尚未读取猎聘搜索");
        lastDeliveredCount = 0;
        activeLiepin = null;
        restRequest = null;
        restReason = null;
        restUntil = 0L;
        restAttempt = 0;
        scheduledResume = null;
        retryMode = mode;
        pendingRetryJobIds = mode == RetryMode.SUSPENDED_BATCH
                ? new HashSet<>(retryJobIds)
                : Set.of();
        if (mode == RetryMode.AI_BATCH || mode == RetryMode.SUSPENDED_BATCH) {
            batchRetryRound = 0;
            batchRetryInitialPending = initialPending;
            batchRetryRemaining = initialPending;
            batchRetryDelivered = 0;
            batchRetryReason = "";
        } else {
            batchRetryRound = 0;
            batchRetryInitialPending = 0;
            batchRetryRemaining = 0;
            batchRetryDelivered = 0;
            batchRetryReason = "";
        }
        recentStatus.clear();
        publish(progressCallback, JobProgressMessage.info(PLATFORM, "正在初始化投递任务..."));
        return true;
    }

    private synchronized boolean claimSuspendedRetryRun(
            Consumer<JobProgressMessage> progressCallback,
            List<LiepinService.SuspendedRetryJob> retryJobs
    ) {
        if (restartBlocked.get()) {
            return false;
        }
        if (suspendedRetry.isRunning || !acquireSuspendedTaskLease()) {
            return false;
        }
        Set<Long> retryJobIds = retryJobs.stream()
                .map(LiepinService.SuspendedRetryJob::jobId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        suspendedRetry.isRunning = true;
        suspendedRetry.runId++;
        suspendedRetry.taskState = TaskState.WAITING;
        suspendedRetry.shouldStop = false;
        suspendedRetry.forcedCancellation = false;
        suspendedRetry.lastDeliveredCount = 0;
        suspendedRetry.lastReadRequestStatus = ReadRequestStatus.browser(false, "尚未读取猎聘搜索");
        suspendedRetry.deliverySummary = emptyDeliverySummary();
        suspendedRetry.aiSummary = emptyAiSummary();
        suspendedRetry.batchRetryRound = 0;
        suspendedRetry.batchRetryInitialPending = retryJobIds.size();
        suspendedRetry.batchRetryRemaining = retryJobIds.size();
        suspendedRetry.batchRetryDelivered = 0;
        suspendedRetry.batchRetryReason = "";
        suspendedRetry.pendingRetryJobIds = new HashSet<>(retryJobIds);
        suspendedRetry.pendingRetryJobs = List.copyOf(retryJobs);
        suspendedRetry.activeProgressCallback = progressCallback;
        suspendedRetry.recentStatus.clear();
        publish(suspendedRetry, JobProgressMessage.info(PLATFORM, "正在初始化暂缓岗位重试..."));
        return true;
    }

    private void runClaimedManaged(Consumer<JobProgressMessage> progressCallback) {
        activeWorkerTasks.incrementAndGet();
        try {
            runClaimed(progressCallback);
        } finally {
            workerFinished();
        }
    }

    private void runSuspendedRetryClaimedManaged(Consumer<JobProgressMessage> progressCallback) {
        activeWorkerTasks.incrementAndGet();
        try {
            runSuspendedRetryClaimed(progressCallback);
        } finally {
            workerFinished();
        }
    }

    private void workerFinished() {
        if (activeWorkerTasks.decrementAndGet() <= 0) {
            activeWorkerTasks.set(0);
            restartBlocked.set(false);
            synchronized (lifecycleMonitor) {
                lifecycleMonitor.notifyAll();
            }
        }
    }

    private void runClaimed(Consumer<JobProgressMessage> progressCallback) {
        try {
            if (shouldStop()) {
                taskState = TaskState.CANCELLED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM, "投递任务已取消"));
                finishRun();
                return;
            }
            publish(progressCallback, JobProgressMessage.info(PLATFORM, "正在等待猎聘浏览器资源..."));
            AtomicReference<Liepin.ExecutionResult> resultRef = new AtomicReference<>();
            boolean acquired = playwrightManager.withPlatformAccessCancellable(PLATFORM,
                    this::shouldStop,
                    () -> resultRef.set(executeDeliveryInternal(progressCallback))
            );
            if (!acquired) {
                taskState = TaskState.CANCELLED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM,
                        "投递任务已取消，尚未开始投递"));
                finishRun();
                return;
            }

            if (shouldStop()) {
                taskState = TaskState.CANCELLED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM,
                        "投递任务已取消，当前页未标记完成"));
                finishRun();
                return;
            }

            Liepin.ExecutionResult result = resultRef.get();
            if (result == null) {
                finishRun();
                return;
            }
            lastDeliveredCount = result.deliveredCount();
            switch (result.outcome()) {
                case USER_PAUSED -> {
                    taskState = shouldStop() ? TaskState.CANCELLED : TaskState.PAUSED;
                    publish(progressCallback, shouldStop()
                            ? JobProgressMessage.warning(PLATFORM,
                            String.format("投递已停止，已发起%d个聊天", result.deliveredCount()))
                            : buildPausedMessage(result.deliveredCount()));
                }
                case RATE_LIMITED -> {
                    if (result.restRequest() != null) {
                        scheduleRest(result.restRequest(), progressCallback);
                        return;
                    }
                    taskState = TaskState.PAUSED;
                    publish(progressCallback, buildRateLimitedMessage(
                            result.deliveredCount(), result.detail()));
                }
                case RETRY_REQUIRED -> {
                    taskState = TaskState.PAUSED;
                    publish(progressCallback, buildSearchRetryMessage(
                            result.deliveredCount(), result.detail()));
                }
                case REST_REQUIRED -> {
                    scheduleRest(result.restRequest(), progressCallback);
                    return;
                }
                case COMPLETED -> {
                    taskState = TaskState.COMPLETED;
                    publish(progressCallback, buildCompletionMessage(
                            result.deliveredCount(), deliverySummary));
                }
            }
            finishRun();
        } catch (Liepin.PageLifecycleException e) {
            log.error("猎聘投递任务因页面生命周期中断", e);
            taskState = shouldStop() ? TaskState.CANCELLED : TaskState.FAILED;
            if (shouldStop()) {
                publish(progressCallback, JobProgressMessage.warning(PLATFORM,
                        "投递任务已取消，当前页未标记完成"));
                finishRun();
                return;
            }
            String message = e.sideEffectStarted()
                    ? "浏览器页面在发送过程中失效，发送结果不确定，任务已暂停，请检查后重试"
                    : "猎聘页面已失效，任务已暂停，请重新启动投递";
            publish(progressCallback, JobProgressMessage.error(PLATFORM, message));
            finishRun();
        } catch (Exception e) {
            log.error("猎聘投递任务访问浏览器失败", e);
            if (shouldStop()) {
                taskState = TaskState.CANCELLED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM,
                        "投递任务已取消，当前页未标记完成"));
                finishRun();
                return;
            }
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
            finishRun();
        }
    }

    private void runSuspendedRetryClaimed(Consumer<JobProgressMessage> progressCallback) {
        try {
            publish(suspendedRetry, JobProgressMessage.info(PLATFORM, "正在等待猎聘浏览器资源..."));
            boolean acquired = playwrightManager.withPlatformAccessCancellable(PLATFORM,
                    () -> suspendedRetry.shouldStop,
                    () -> executeSuspendedRetryInternal(progressCallback)
            );
            if (!acquired) {
                suspendedRetry.taskState = TaskState.CANCELLED;
                publish(suspendedRetry, buildSuspendedBatchRetryPausedMessage(
                        suspendedRetry.batchRetryDelivered,
                        suspendedRetry.batchRetryRemaining,
                        "收到停止指令，当前页未标记完成"));
                finishSuspendedRetryRun();
            }
        } catch (Exception e) {
            log.error("猎聘暂缓岗位重试访问浏览器失败", e);
            if (suspendedRetry.shouldStop || suspendedRetry.forcedCancellation) {
                suspendedRetry.taskState = TaskState.CANCELLED;
                publish(suspendedRetry, JobProgressMessage.warning(PLATFORM,
                        "暂缓岗位重试已取消，当前页未标记完成"));
                finishSuspendedRetryRun();
                return;
            }
            suspendedRetry.taskState = TaskState.FAILED;
            publish(suspendedRetry, JobProgressMessage.error(PLATFORM, "暂缓岗位重试失败: " + e.getMessage()));
            finishSuspendedRetryRun();
        }
    }

    /** 两个任务槽共享登录监控租约；浏览器页面本身仍由 Playwright 临界区串行保护。 */
    private synchronized boolean acquireTaskLease() {
        if (taskLeaseHeld.get()) {
            return true;
        }
        int previous = activeTaskLeases.getAndIncrement();
        taskLeaseHeld.set(true);
        if (previous == 0) {
            try {
                playwrightManager.pauseLiepinMonitoring();
            } catch (Exception e) {
                log.warn("暂停猎聘登录监控失败: {}", e.getMessage());
            }
        }
        return true;
    }

    private synchronized boolean acquireSuspendedTaskLease() {
        if (suspendedRetry.leaseHeld.get()) {
            return true;
        }
        int previous = activeTaskLeases.getAndIncrement();
        suspendedRetry.leaseHeld.set(true);
        if (previous == 0) {
            try {
                playwrightManager.pauseLiepinMonitoring();
            } catch (Exception e) {
                log.warn("暂停猎聘登录监控失败: {}", e.getMessage());
            }
        }
        return true;
    }

    private synchronized void releaseTaskLease() {
        if (taskLeaseHeld.compareAndSet(true, false)) {
            int remaining = activeTaskLeases.updateAndGet(current -> Math.max(0, current - 1));
            if (remaining == 0) {
                resumeLiepinMonitoringAfterLeaseRelease();
            }
            synchronized (lifecycleMonitor) {
                lifecycleMonitor.notifyAll();
            }
        }
    }

    private synchronized void releaseSuspendedTaskLease() {
        if (suspendedRetry.leaseHeld.compareAndSet(true, false)) {
            int remaining = activeTaskLeases.updateAndGet(current -> Math.max(0, current - 1));
            if (remaining == 0) {
                resumeLiepinMonitoringAfterLeaseRelease();
            }
            synchronized (lifecycleMonitor) {
                lifecycleMonitor.notifyAll();
            }
        }
    }

    private void resumeLiepinMonitoringAfterLeaseRelease() {
        try {
            playwrightManager.resumeLiepinMonitoring();
        } catch (Exception e) {
            log.warn("恢复猎聘登录监控失败: {}", e.getMessage());
        }
    }

    private boolean hasActiveTask() {
        return isRunning || suspendedRetry.isRunning;
    }

    private void executeSuspendedRetryInternal(Consumer<JobProgressMessage> progressCallback) {
        Liepin liepin = null;
        try {
            if (shouldStopSuspendedRetry()) {
                return;
            }
            Page page = playwrightManager.ensureLiepinPageReady();
            if (page == null) {
                suspendedRetry.taskState = TaskState.FAILED;
                publish(suspendedRetry, JobProgressMessage.error(PLATFORM, "猎聘页面未初始化"));
                return;
            }
            if (shouldStopSuspendedRetry()) {
                return;
            }
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                suspendedRetry.taskState = TaskState.FAILED;
                publish(suspendedRetry, JobProgressMessage.error(PLATFORM, "请先登录猎聘"));
                return;
            }

            playwrightManager.pauseLiepinMonitoring();
            LiepinConfig config = configService.getLiepinConfig();
            // 暂缓岗位已经过前置筛选；本任务沿用批量自动投递的评分与回执链路。
            config.setAiDeliveryMode("BATCH_AUTO");
            config.setAutoAiDelivery(true);
            publish(suspendedRetry, JobProgressMessage.info(PLATFORM, "配置加载成功"));
            publish(suspendedRetry, JobProgressMessage.info(PLATFORM, "开始按原岗位链接直达重试，使用 BATCH_AUTO..."));

            Liepin.ProgressCallback callback = (message, current, total) -> {
                if (current != null && total != null) {
                    publish(suspendedRetry, JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    publish(suspendedRetry, JobProgressMessage.info(PLATFORM, message));
                }
            };

            liepin = liepinProvider.getObject();
            liepin.setPage(page);
            liepin.setConfig(config);
            liepin.setAccountPacing(accountPacing);
            liepin.setAutomaticRiskRecoveryAllowed(false);
            liepin.setProgressCallback(callback);
            liepin.setShouldStopCallback(this::shouldStopSuspendedRetry);
            liepin.setGreetingConfirmation(this::awaitSuspendedGreetingConfirmation);
            liepin.setPageRecovery(playwrightManager::ensureLiepinPageReady);
            liepin.setPendingRetryJobs(suspendedRetry.pendingRetryJobs);
            Liepin activeLiepin = liepin;
            liepin.setStatsChangedCallback(() -> refreshSuspendedRetryStats(activeLiepin));

            executeSuspendedBatchRetryRounds(progressCallback, liepin, suspendedRetry);
        } catch (Liepin.PageLifecycleException e) {
            log.error("猎聘暂缓岗位重试因页面生命周期中断", e);
            if (suspendedRetry.shouldStop || suspendedRetry.forcedCancellation) {
                suspendedRetry.taskState = TaskState.CANCELLED;
                publish(suspendedRetry, JobProgressMessage.warning(PLATFORM,
                        "暂缓岗位重试已取消，当前页未标记完成"));
                return;
            }
            suspendedRetry.taskState = TaskState.FAILED;
            String message = e.sideEffectStarted()
                    ? "浏览器页面在发送过程中失效，发送结果不确定，暂缓重试已暂停，请检查后重试"
                    : "猎聘页面已失效，暂缓重试已暂停，请重新启动";
            publish(suspendedRetry, JobProgressMessage.error(PLATFORM, message));
        } catch (Exception e) {
            log.error("猎聘暂缓岗位重试执行失败", e);
            suspendedRetry.taskState = TaskState.FAILED;
            publish(suspendedRetry, JobProgressMessage.error(PLATFORM, "暂缓岗位重试失败: " + e.getMessage()));
        } finally {
            refreshSuspendedRetryStats(liepin);
            finishSuspendedRetryRun();
        }
    }

    private Liepin.ExecutionResult executeDeliveryInternal(Consumer<JobProgressMessage> progressCallback) {
        if (shouldStop()) {
            return new Liepin.ExecutionResult(0, Liepin.ExecutionOutcome.USER_PAUSED, "收到停止指令");
        }
        Page page = playwrightManager.ensureLiepinPageReady();
        if (page == null) {
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "猎聘页面未初始化"));
            return null;
        }

        if (shouldStop()) {
            return new Liepin.ExecutionResult(0, Liepin.ExecutionOutcome.USER_PAUSED, "收到停止指令");
        }

        if (!playwrightManager.isLoggedIn(PLATFORM)) {
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "请先登录猎聘"));
            return null;
        }

        taskState = TaskState.RUNNING;
        // 暂停后台登录监控，避免并发访问冲突
        playwrightManager.pauseLiepinMonitoring();

        // 每次恢复都重新读取配置；当前休息倒计时仍使用触发时的快照。
        LiepinConfig config = configService.getLiepinConfig();
        publish(progressCallback, JobProgressMessage.info(PLATFORM, "配置加载成功"));
        publish(progressCallback, JobProgressMessage.info(PLATFORM, "开始投递任务..."));

        Liepin.ProgressCallback cb = (message, current, total) -> {
            if (current != null && total != null) {
                publish(progressCallback, JobProgressMessage.progress(PLATFORM, message, current, total));
            } else {
                publish(progressCallback, JobProgressMessage.info(PLATFORM, message));
            }
        };

        Liepin liepin = activeLiepin;
        if (liepin == null) {
            liepin = liepinProvider.getObject();
            activeLiepin = liepin;
        } else if (restRequest != null) {
            liepin.prepareForResume(restRequest);
            restRequest = null;
            publish(progressCallback, JobProgressMessage.info(PLATFORM,
                    "间歇休息结束，恢复当前关键词和页面"));
        }
        liepin.setPage(page);
        liepin.setConfig(config);
        liepin.setAccountPacing(accountPacing);
        liepin.setAutomaticRiskRecoveryAllowed(retryMode == RetryMode.NORMAL);
        liepin.setProgressCallback(cb);
        liepin.setShouldStopCallback(this::shouldStop);
        liepin.setGreetingConfirmation(this::awaitGreetingConfirmation);
        liepin.setPageRecovery(playwrightManager::ensureLiepinPageReady);
        Liepin active = liepin;
        liepin.setStatsChangedCallback(() -> refreshRunStats(active));

        if (retryMode == RetryMode.AI_BATCH) {
            executeBatchRetryRounds(progressCallback, liepin, config);
            return null;
        }
        if (retryMode == RetryMode.SUSPENDED_BATCH) {
            liepin.setPendingRetryJobIds(pendingRetryJobIds);
            executeSuspendedBatchRetryRounds(progressCallback, liepin);
            return null;
        }

        Liepin.ExecutionResult result = liepin.execute();
        lastDeliveredCount = result.deliveredCount();
        refreshRunStats(liepin);
        return result;
    }

    private LiepinConfig configForRest() {
        try {
            LiepinConfig latest = configService.getLiepinConfig();
            if (latest != null) {
                return latest;
            }
        } catch (Exception e) {
            log.debug("读取最新猎聘休息配置失败，使用默认短休息区间: {}", e.getMessage());
        }
        return new LiepinConfig();
    }

    private synchronized void scheduleRest(Liepin.RestRequest request,
                                           Consumer<JobProgressMessage> progressCallback) {
        if (request == null || !isRunning || shouldStop()) {
            taskState = TaskState.CANCELLED;
            publish(progressCallback, JobProgressMessage.warning(PLATFORM, "投递任务已取消"));
            finishRun();
            return;
        }

        LiepinConfig config = configForRest();
        boolean riskRest = "risk_signal".equals(request.reason());
        int restMinSeconds = riskRest
                ? LiepinConfig.ADAPTIVE_RISK_REST_MIN_SECONDS
                : config.effectiveMaxPerRunRestMinSeconds();
        int restMaxSeconds = riskRest
                ? LiepinConfig.ADAPTIVE_RISK_REST_MAX_SECONDS
                : config.effectiveMaxPerRunRestMaxSeconds();
        long delayMillis = ThreadLocalRandom.current().nextLong(
                restMinSeconds, (long) restMaxSeconds + 1L) * 1000L;
        restRequest = request;
        restReason = request.reason();
        restUntil = System.currentTimeMillis() + delayMillis;
        restAttempt++;
        taskState = TaskState.RESTING;

        // 休息期间不占用登录监控或平台任务租约；恢复时重新取得。
        releaseTaskLease();
        publish(progressCallback, JobProgressMessage.warning(PLATFORM,
                String.format("%s，%s%s后自动继续（第%d次）",
                        request.message(), riskRest ? "长休息" : "休息",
                        formatRestDelay(delayMillis), restAttempt)));
        try {
            scheduledResume = deliveryResumeScheduler.schedule(() -> {
                synchronized (LiepinJobService.this) {
                    if (!isRunning || shouldStop() || taskState != TaskState.RESTING) {
                        return;
                    }
                    scheduledResume = null;
                    acquireTaskLease();
                    restReason = null;
                    restUntil = 0L;
                    taskState = TaskState.WAITING;
                }
                publish(progressCallback, JobProgressMessage.info(PLATFORM,
                        "间歇休息结束，准备恢复任务"));
                try {
                    deliveryExecutor.execute(() -> runClaimedManaged(progressCallback));
                } catch (RuntimeException e) {
                    taskState = TaskState.FAILED;
                    publish(progressCallback, JobProgressMessage.error(PLATFORM,
                            "提交休息后的恢复任务失败: " + e.getMessage()));
                    finishRun();
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM,
                    "创建间歇休息调度失败: " + e.getMessage()));
            finishRun();
        }
    }

    private String formatRestDelay(long delayMillis) {
        long seconds = Math.max(1L, delayMillis / 1000L);
        return seconds >= 60L
                ? String.format("%d分%d秒", seconds / 60L, seconds % 60L)
                : seconds + "秒";
    }

    private void executeBatchRetryRounds(Consumer<JobProgressMessage> progressCallback,
                                         Liepin liepin, LiepinConfig config) {
        int previousPending = Math.max(1, batchRetryInitialPending);
        int totalDelivered = 0;

        while (true) {
            if (shouldStop()) {
                batchRetryReason = "收到停止指令";
                taskState = TaskState.PAUSED;
                publish(progressCallback, buildBatchRetryPausedMessage(
                        totalDelivered, batchRetryRemaining, batchRetryReason));
                return;
            }

            batchRetryRound++;
            publish(progressCallback, JobProgressMessage.info(PLATFORM,
                    String.format("开始 AI 超时批量重试第%d轮，当前待重试%d个",
                            batchRetryRound, previousPending)));

            Liepin.ExecutionResult result = liepin.execute();
            totalDelivered += result.deliveredCount();
            batchRetryDelivered = totalDelivered;
            lastDeliveredCount = totalDelivered;
            refreshRunStats(liepin);

            int currentPending = number(aiSummary, "aiRetryable");
            batchRetryRemaining = currentPending;
            BatchRetryAction action = decideBatchRetry(previousPending, currentPending, result.outcome());
            if (action == BatchRetryAction.COMPLETE) {
                taskState = TaskState.COMPLETED;
                batchRetryReason = "已清空待重试岗位";
                publish(progressCallback, buildBatchRetryCompletionMessage(totalDelivered, batchRetryRound));
                return;
            }

            if (action == BatchRetryAction.PAUSE) {
                batchRetryReason = result.outcome() == Liepin.ExecutionOutcome.RATE_LIMITED
                        || result.outcome() == Liepin.ExecutionOutcome.RETRY_REQUIRED
                        ? result.detail()
                        : currentPending >= previousPending
                        ? "本轮待重试数量没有下降"
                        : "任务已暂停";
                taskState = TaskState.PAUSED;
                if (result.outcome() == Liepin.ExecutionOutcome.RATE_LIMITED) {
                    publish(progressCallback, JobProgressMessage.error(PLATFORM,
                            String.format("AI超时批量重试因平台风控或节奏中断，剩余%d个，累计发起%d个聊天：%s",
                                    currentPending, totalDelivered, batchRetryReason)));
                } else {
                    publish(progressCallback, buildBatchRetryPausedMessage(
                            totalDelivered, currentPending, batchRetryReason));
                }
                return;
            }

            previousPending = currentPending;
            int delaySeconds = config.effectiveAiTimeoutRetryDelaySeconds() * batchRetryRound;
            if (delaySeconds > 0) {
                publish(progressCallback, JobProgressMessage.info(PLATFORM,
                        String.format("第%d轮完成本轮%d个聊天，剩余%d个，等待%d秒后继续",
                                batchRetryRound, result.deliveredCount(), currentPending, delaySeconds)));
                if (!waitForBatchRetryDelay(delaySeconds)) {
                    batchRetryReason = "收到停止指令";
                    taskState = TaskState.PAUSED;
                    publish(progressCallback, buildBatchRetryPausedMessage(
                            totalDelivered, currentPending, batchRetryReason));
                    return;
                }
            }
        }
    }

    private void executeSuspendedBatchRetryRounds(Consumer<JobProgressMessage> progressCallback,
                                                  Liepin liepin) {
        int previousPending = batchRetryInitialPending;
        int totalDelivered = 0;

        while (true) {
            if (shouldStop()) {
                batchRetryReason = "收到停止指令";
                taskState = TaskState.PAUSED;
                publish(progressCallback, buildSuspendedBatchRetryPausedMessage(
                        totalDelivered, batchRetryRemaining, batchRetryReason));
                return;
            }

            if (previousPending == 0) {
                taskState = TaskState.COMPLETED;
                batchRetryReason = "已清空筛选后暂缓岗位";
                publish(progressCallback, buildSuspendedBatchRetryCompletionMessage(
                        totalDelivered, batchRetryRound));
                return;
            }

            batchRetryRound++;
            publish(progressCallback, JobProgressMessage.info(PLATFORM,
                    String.format("开始筛选后暂缓岗位批量重试第%d轮，当前待处理%d个",
                            batchRetryRound, previousPending)));

            Liepin.ExecutionResult result = liepin.execute();
            totalDelivered += result.deliveredCount();
            batchRetryDelivered = totalDelivered;
            lastDeliveredCount = totalDelivered;
            refreshRunStats(liepin);

            int currentPending = liepin.getPendingRetryRemaining();
            batchRetryRemaining = currentPending;
            BatchRetryAction action = decideBatchRetry(previousPending, currentPending, result.outcome());
            if (action == BatchRetryAction.COMPLETE) {
                taskState = TaskState.COMPLETED;
                batchRetryReason = "已清空筛选后暂缓岗位";
                publish(progressCallback, buildSuspendedBatchRetryCompletionMessage(
                        totalDelivered, batchRetryRound, result.detail()));
                return;
            }

            if (action == BatchRetryAction.PAUSE) {
                batchRetryReason = result.outcome() == Liepin.ExecutionOutcome.RATE_LIMITED
                        || result.outcome() == Liepin.ExecutionOutcome.RETRY_REQUIRED
                        ? result.detail()
                        : currentPending >= previousPending
                        ? (result.detail() == null || result.detail().isBlank()
                        ? "本轮待处理数量没有下降" : result.detail())
                        : "任务已暂停";
                taskState = TaskState.PAUSED;
                if (result.outcome() == Liepin.ExecutionOutcome.RATE_LIMITED) {
                    publish(progressCallback, JobProgressMessage.error(PLATFORM,
                            String.format("筛选后暂缓岗位批量重试因平台风控或节奏中断，剩余%d个，累计发起%d个聊天：%s",
                                    currentPending, totalDelivered, batchRetryReason)));
                } else {
                    publish(progressCallback, buildSuspendedBatchRetryPausedMessage(
                            totalDelivered, currentPending, batchRetryReason));
                }
                return;
            }

            previousPending = currentPending;
        }
    }

    private void executeSuspendedBatchRetryRounds(Consumer<JobProgressMessage> progressCallback,
                                                  Liepin liepin,
                                                  SuspendedRetryRun state) {
        int previousPending = state.batchRetryInitialPending;
        int totalDelivered = 0;

        while (true) {
            if (state.shouldStop) {
                state.batchRetryReason = "收到停止指令";
                state.taskState = TaskState.PAUSED;
                publish(state, buildSuspendedBatchRetryPausedMessage(
                        totalDelivered, state.batchRetryRemaining, state.batchRetryReason));
                return;
            }
            if (previousPending == 0) {
                state.taskState = TaskState.COMPLETED;
                state.batchRetryReason = "已清空筛选后暂缓岗位";
                publish(state, buildSuspendedBatchRetryCompletionMessage(
                        totalDelivered, state.batchRetryRound));
                return;
            }

            state.batchRetryRound++;
            publish(state, JobProgressMessage.info(PLATFORM,
                    String.format("开始筛选后暂缓岗位批量重试第%d轮，当前待处理%d个",
                            state.batchRetryRound, previousPending)));

            Liepin.ExecutionResult result = liepin.execute();
            totalDelivered += result.deliveredCount();
            state.batchRetryDelivered = totalDelivered;
            state.lastDeliveredCount = totalDelivered;
            refreshSuspendedRetryStats(liepin);

            int currentPending = liepin.getPendingRetryRemaining();
            state.batchRetryRemaining = currentPending;
            BatchRetryAction action = decideBatchRetry(previousPending, currentPending, result.outcome());
            if (action == BatchRetryAction.COMPLETE) {
                state.taskState = TaskState.COMPLETED;
                state.batchRetryReason = "已清空筛选后暂缓岗位";
                publish(state, buildSuspendedBatchRetryCompletionMessage(
                        totalDelivered, state.batchRetryRound, result.detail()));
                return;
            }
            if (action == BatchRetryAction.PAUSE) {
                state.batchRetryReason = result.outcome() == Liepin.ExecutionOutcome.RATE_LIMITED
                        || result.outcome() == Liepin.ExecutionOutcome.RETRY_REQUIRED
                        ? result.detail()
                        : currentPending >= previousPending
                        ? (result.detail() == null || result.detail().isBlank()
                        ? "本轮待处理数量没有下降" : result.detail())
                        : "任务已暂停";
                state.taskState = TaskState.PAUSED;
                if (result.outcome() == Liepin.ExecutionOutcome.RATE_LIMITED) {
                    publish(state, JobProgressMessage.error(PLATFORM,
                            String.format("筛选后暂缓岗位批量重试因平台风控或节奏中断，剩余%d个，累计发起%d个聊天：%s",
                                    currentPending, totalDelivered, state.batchRetryReason)));
                } else {
                    publish(state, buildSuspendedBatchRetryPausedMessage(
                            totalDelivered, currentPending, state.batchRetryReason));
                }
                return;
            }
            previousPending = currentPending;
        }
    }

    private boolean waitForBatchRetryDelay(int delaySeconds) {
        long deadline = System.currentTimeMillis() + delaySeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop()) {
                return false;
            }
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(250L, Math.max(1L, remaining)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    void refreshRunStats(Liepin liepin) {
        if (liepin == null) {
            return;
        }
        try {
            aiSummary = liepin.getAiSummary();
            deliverySummary = liepin.getDeliverySummary();
            ReadRequestStatus currentReadRequestStatus = liepin.getReadRequestStatus();
            if (currentReadRequestStatus != null) {
                lastReadRequestStatus = currentReadRequestStatus;
            }
        } catch (RuntimeException e) {
            log.warn("读取猎聘运行统计失败: {}", e.getMessage());
        }
    }

    private void refreshSuspendedRetryStats(Liepin liepin) {
        if (liepin == null) {
            return;
        }
        try {
            suspendedRetry.aiSummary = liepin.getAiSummary();
            suspendedRetry.deliverySummary = liepin.getDeliverySummary();
            ReadRequestStatus currentReadRequestStatus = liepin.getReadRequestStatus();
            if (currentReadRequestStatus != null) {
                suspendedRetry.lastReadRequestStatus = currentReadRequestStatus;
            }
        } catch (RuntimeException e) {
            log.warn("读取猎聘暂缓重试统计失败: {}", e.getMessage());
        }
    }

    private synchronized void finishRun() {
        ScheduledFuture<?> pending = scheduledResume;
        if (pending != null) {
            pending.cancel(false);
        }
        scheduledResume = null;
        cancelPendingGreeting();
        isRunning = false;
        if (!forcedCancellation) {
            shouldStop = false;
        }
        restReason = null;
        restUntil = 0L;
        restAttempt = 0;
        restRequest = null;
        activeLiepin = null;
        activeProgressCallback = null;
        releaseTaskLease();
        synchronized (lifecycleMonitor) {
            lifecycleMonitor.notifyAll();
        }
    }

    private void finishSuspendedRetryRun() {
        cancelPendingGreeting(suspendedRetry);
        suspendedRetry.isRunning = false;
        if (!suspendedRetry.forcedCancellation) {
            suspendedRetry.shouldStop = false;
        }
        suspendedRetry.activeProgressCallback = null;
        releaseSuspendedTaskLease();
        synchronized (lifecycleMonitor) {
            lifecycleMonitor.notifyAll();
        }
    }

    /**
     * Spring 关闭应用时先给投递线程收尾，确保最近完成的页码已经落盘。
     * 半页不会伪装成完成页，下次仍会从该页重跑。
     */
    @PreDestroy
    public void shutdown() {
        if (!stopAndAwait(STOP_GRACE_MILLIS)) {
            log.warn("猎聘任务在{}ms内未完成停止，已强制关闭平台运行时；下次将从最近完整页恢复",
                    STOP_GRACE_MILLIS);
        }
    }

    /** 请求停止并等待后台任务收尾，供关闭钩子和停止脚本使用。 */
    public boolean stopAndAwait(long timeoutMillis) {
        stopDelivery();
        stopSuspendedRetry();
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        synchronized (lifecycleMonitor) {
            while (hasActiveTask()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    lifecycleMonitor.wait(Math.min(250L, remaining));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!hasActiveTask()) {
            return true;
        }

        log.warn("猎聘任务未在{}ms内收尾，开始强制关闭浏览器运行时", timeoutMillis);
        restartBlocked.set(true);
        forcedCancellation = true;
        suspendedRetry.forcedCancellation = true;
        shouldStop = true;
        suspendedRetry.shouldStop = true;
        taskState = TaskState.CANCELLED;
        suspendedRetry.taskState = TaskState.CANCELLED;
        playwrightManager.forceClosePlatformBrowsers(PLATFORM);
        finishRun();
        finishSuspendedRetryRun();
        synchronized (lifecycleMonitor) {
            long workerDeadline = System.currentTimeMillis() + STOP_GRACE_MILLIS;
            while (activeWorkerTasks.get() > 0) {
                long remaining = workerDeadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    lifecycleMonitor.wait(Math.min(100L, remaining));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return !hasActiveTask() && activeTaskLeases.get() == 0 && activeWorkerTasks.get() == 0;
    }

    @Override
    public synchronized void stopDelivery() {
        if (!isRunning) {
            log.warn("猎聘任务未在运行，无需停止");
            return;
        }
        if (taskState == TaskState.STOPPING) {
            return;
        }
        log.info("收到停止猎聘任务请求");
        forcedCancellation = false;
        shouldStop = true;
        cancelPendingGreeting();
        if (taskState == TaskState.RESTING) {
            ScheduledFuture<?> pending = scheduledResume;
            if (pending != null) {
                pending.cancel(false);
            }
            scheduledResume = null;
            taskState = TaskState.CANCELLED;
            publish(activeProgressCallback, JobProgressMessage.warning(PLATFORM,
                    "投递已停止，已取消当前间歇休息"));
            finishRun();
            return;
        }
        taskState = TaskState.STOPPING;
        publish(activeProgressCallback, JobProgressMessage.info(PLATFORM, "正在暂停投递任务..."));
    }

    /** 仅停止暂缓岗位重试槽，不影响普通投递任务。 */
    public synchronized boolean stopSuspendedRetry() {
        if (!suspendedRetry.isRunning) {
            return false;
        }
        if (suspendedRetry.taskState == TaskState.STOPPING) {
            return true;
        }
        log.info("收到停止猎聘暂缓岗位重试请求");
        suspendedRetry.forcedCancellation = false;
        suspendedRetry.taskState = TaskState.STOPPING;
        suspendedRetry.shouldStop = true;
        cancelPendingGreeting(suspendedRetry);
        publish(suspendedRetry, JobProgressMessage.info(PLATFORM, "正在暂停暂缓岗位重试..."));
        return true;
    }

    /**
     * 获取任务状态
     */
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> normalTask = buildTaskStatus(false);
        Map<String, Object> suspendedTask = buildTaskStatus(true);
        Map<String, Object> status = new HashMap<>(normalTask);
        // 顶层字段保持普通投递的旧契约；双任务详情使用独立槽位。
        status.put("normalTask", normalTask);
        status.put("suspendedRetryTask", suspendedTask);
        status.put("suspendedTask", suspendedTask);
        status.put("isAnyRunning", hasActiveTask());
        status.put("activeTaskLeases", activeTaskLeases.get());
        return status;
    }

    private Map<String, Object> buildTaskStatus(boolean suspendedView) {
        boolean running = suspendedView ? suspendedRetry.isRunning : isRunning;
        long currentRunId = suspendedView ? suspendedRetry.runId : runId;
        TaskState currentTaskState = suspendedView ? suspendedRetry.taskState : taskState;
        int currentDelivered = suspendedView ? suspendedRetry.lastDeliveredCount : lastDeliveredCount;
        RetryMode currentRetryMode = suspendedView ? RetryMode.SUSPENDED_BATCH : retryMode;
        int currentRound = suspendedView ? suspendedRetry.batchRetryRound : batchRetryRound;
        int currentInitialPending = suspendedView
                ? suspendedRetry.batchRetryInitialPending : batchRetryInitialPending;
        int currentRemaining = suspendedView ? suspendedRetry.batchRetryRemaining : batchRetryRemaining;
        int currentBatchDelivered = suspendedView
                ? suspendedRetry.batchRetryDelivered : batchRetryDelivered;
        String currentRetryReason = suspendedView ? suspendedRetry.batchRetryReason : batchRetryReason;
        Map<String, Object> currentAiSummary = suspendedView ? suspendedRetry.aiSummary : aiSummary;
        Map<String, Object> currentDeliverySummary = suspendedView
                ? suspendedRetry.deliverySummary : deliverySummary;
        ReadRequestStatus currentReadRequest = suspendedView
                ? suspendedRetry.lastReadRequestStatus : lastReadRequestStatus;
        String currentMessage = suspendedView ? suspendedRetry.lastMessage : lastMessage;
        String currentMessageType = suspendedView ? suspendedRetry.lastMessageType : lastMessageType;
        long currentMessageAt = suspendedView ? suspendedRetry.lastMessageAt : lastMessageAt;
        String currentRestReason = suspendedView ? null : restReason;
        long currentRestUntil = suspendedView ? 0L : restUntil;
        int currentRestAttempt = suspendedView ? 0 : restAttempt;
        Liepin.RestRequest currentRestRequest = suspendedView ? null : restRequest;

        Map<String, Object> result = new HashMap<>();
        result.put("platform", PLATFORM);
        result.put("runId", currentRunId);
        result.put("isRunning", running);
        result.put("taskState", currentTaskState.name());
        result.put("lastDeliveredCount", currentDelivered);
        result.put("retryMode", currentRetryMode.name());
        result.put("restReason", currentRestReason);
        result.put("restUntil", currentRestUntil);
        result.put("restRemainingMillis", currentRestUntil <= 0L
                ? 0L : Math.max(0L, currentRestUntil - System.currentTimeMillis()));
        result.put("restAttempt", currentRestAttempt);
        Map<String, Object> adaptivePacing = accountPacing.snapshot();
        result.put("adaptivePacing", adaptivePacing);
        result.put("adaptiveMode", adaptivePacing.get("mode"));
        result.put("riskReason", adaptivePacing.get("riskReason"));
        if (currentRestRequest == null) {
            result.put("resumeCursor", null);
        } else {
            Map<String, Object> resumeCursor = new HashMap<>();
            resumeCursor.put("keyword", currentRestRequest.keyword());
            resumeCursor.put("page", currentRestRequest.page());
            result.put("resumeCursor", resumeCursor);
        }
        Map<String, Object> batchRetry = new HashMap<>();
        batchRetry.put("active", running && (currentRetryMode == RetryMode.AI_BATCH
                || currentRetryMode == RetryMode.SUSPENDED_BATCH));
        batchRetry.put("scope", currentRetryMode == RetryMode.SUSPENDED_BATCH ? "SUSPENDED"
                : currentRetryMode == RetryMode.AI_BATCH ? "AI_TIMEOUT" : "NONE");
        batchRetry.put("round", currentRound);
        batchRetry.put("initialPending", currentInitialPending);
        batchRetry.put("remaining", currentRemaining);
        batchRetry.put("delivered", currentBatchDelivered);
        batchRetry.put("reason", currentRetryReason);
        result.put("batchRetry", batchRetry);
        result.put("resumeAvailable", currentTaskState == TaskState.PAUSED
                || currentTaskState == TaskState.FAILED
                || number(currentAiSummary, "aiRetryable") > 0
                || (currentRetryMode == RetryMode.SUSPENDED_BATCH && currentRemaining > 0));
        // 状态轮询不能因停止后的强制关闭再次创建懒加载运行时；登录页负责主动刷新登录态。
        result.put("isLoggedIn", playwrightManager.getCachedLoginStatus(PLATFORM));
        result.put("message", currentMessage);
        result.put("messageType", currentMessageType);
        result.put("messageAt", currentMessageAt);
        List<JobProgressMessage> recentMessages = suspendedView
                ? suspendedRetry.recentStatus.snapshot() : recentStatus.snapshot();
        if (recentMessages.isEmpty()) {
            recentMessages = List.of(new JobProgressMessage(
                    PLATFORM, currentMessageType, currentMessage, null, null, currentMessageAt));
        }
        result.put("recentMessages", recentMessages);
        result.put("aiSummary", currentAiSummary);
        result.put("deliverySummary", currentDeliverySummary);
        result.put("readRequest", currentReadRequest.asMap());
        Liepin.GreetingRequest pending = suspendedView
                ? suspendedRetry.pendingGreeting : pendingGreeting;
        result.put("pendingGreeting", pending == null ? null : greetingData(pending));
        return result;
    }

    private Map<String, Object> greetingData(Liepin.GreetingRequest pending) {
        Map<String, Object> data = new HashMap<>();
        data.put("jobId", pending.jobId());
        data.put("companyName", pending.companyName());
        data.put("jobTitle", pending.jobTitle());
        data.put("salary", pending.salary());
        data.put("jd", pending.jd());
        data.put("message", pending.message());
        data.put("aiAvailable", pending.aiAvailable());
        data.put("source", pending.aiAvailable() ? "ai" : "preset");
        return data;
    }

    /** 等待页面对当前岗位的最终发送确认。 */
    public Liepin.GreetingAction awaitGreetingConfirmation(Liepin.GreetingRequest request) {
        if (request == null || shouldStop()) return Liepin.GreetingAction.SKIP;

        CompletableFuture<Liepin.GreetingAction> decision = new CompletableFuture<>();
        synchronized (pendingLock) {
            if (pendingDecision != null) {
                log.warn("已有待确认猎聘岗位，跳过新的 job_id={}", request.jobId());
                return Liepin.GreetingAction.SKIP;
            }
            pendingGreeting = request;
            pendingDecision = decision;
        }
        publish(activeProgressCallback, JobProgressMessage.info(PLATFORM,
                String.format("等待确认【%s】【%s】的%s", request.companyName(), request.jobTitle(),
                        request.aiAvailable() ? "AI话术" : "猎聘预设语")));
        try {
            return decision.get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("猎聘岗位确认等待结束: {}", e.getMessage());
            return Liepin.GreetingAction.SKIP;
        } finally {
            synchronized (pendingLock) {
                if (pendingDecision == decision) {
                    pendingDecision = null;
                    pendingGreeting = null;
                }
            }
        }
    }

    private Liepin.GreetingAction awaitSuspendedGreetingConfirmation(Liepin.GreetingRequest request) {
        if (request == null || suspendedRetry.shouldStop) return Liepin.GreetingAction.SKIP;

        CompletableFuture<Liepin.GreetingAction> decision = new CompletableFuture<>();
        synchronized (suspendedRetry.pendingLock) {
            if (suspendedRetry.pendingDecision != null) {
                log.warn("已有待确认暂缓岗位，跳过新的 job_id={}", request.jobId());
                return Liepin.GreetingAction.SKIP;
            }
            suspendedRetry.pendingGreeting = request;
            suspendedRetry.pendingDecision = decision;
        }
        publish(suspendedRetry, JobProgressMessage.info(PLATFORM,
                String.format("等待确认暂缓岗位【%s】【%s】的%s", request.companyName(), request.jobTitle(),
                        request.aiAvailable() ? "AI话术" : "猎聘预设语")));
        try {
            return decision.get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("猎聘暂缓岗位确认等待结束: {}", e.getMessage());
            return Liepin.GreetingAction.SKIP;
        } finally {
            synchronized (suspendedRetry.pendingLock) {
                if (suspendedRetry.pendingDecision == decision) {
                    suspendedRetry.pendingDecision = null;
                    suspendedRetry.pendingGreeting = null;
                }
            }
        }
    }

    public ConfirmationResult confirmGreeting(Long jobId, String action) {
        Liepin.GreetingAction greetingAction = switch (action == null ? "" : action.trim().toLowerCase()) {
            case "ai", "send_ai" -> Liepin.GreetingAction.SEND_AI;
            case "preset", "send_preset" -> Liepin.GreetingAction.SEND_PRESET;
            case "skip" -> Liepin.GreetingAction.SKIP;
            default -> null;
        };
        if (greetingAction == null) {
            return new ConfirmationResult(false, "确认动作无效");
        }

        if (suspendedRetry.pendingGreeting != null
                && jobId != null
                && jobId.equals(suspendedRetry.pendingGreeting.jobId())) {
            return confirmGreeting(suspendedRetry, jobId, greetingAction);
        }

        synchronized (pendingLock) {
            if (pendingGreeting == null || pendingDecision == null) {
                return new ConfirmationResult(false, "当前没有待确认岗位");
            }
            if (jobId == null || !jobId.equals(pendingGreeting.jobId())) {
                return new ConfirmationResult(false, "待确认岗位已变化，请刷新页面");
            }
            if (greetingAction == Liepin.GreetingAction.SEND_AI && !pendingGreeting.aiAvailable()) {
                return new ConfirmationResult(false, "当前岗位没有可用AI话术");
            }
            boolean accepted = pendingDecision.complete(greetingAction);
            return new ConfirmationResult(accepted, accepted ? "已记录确认动作" : "确认动作已处理");
        }
    }

    private ConfirmationResult confirmGreeting(
            SuspendedRetryRun run,
            Long jobId,
            Liepin.GreetingAction greetingAction
    ) {
        synchronized (run.pendingLock) {
            if (run.pendingGreeting == null || run.pendingDecision == null) {
                return new ConfirmationResult(false, "当前没有待确认岗位");
            }
            if (jobId == null || !jobId.equals(run.pendingGreeting.jobId())) {
                return new ConfirmationResult(false, "待确认岗位已变化，请刷新页面");
            }
            if (greetingAction == Liepin.GreetingAction.SEND_AI && !run.pendingGreeting.aiAvailable()) {
                return new ConfirmationResult(false, "当前岗位没有可用AI话术");
            }
            boolean accepted = run.pendingDecision.complete(greetingAction);
            return new ConfirmationResult(accepted, accepted ? "已记录确认动作" : "确认动作已处理");
        }
    }

    private void cancelPendingGreeting() {
        CompletableFuture<Liepin.GreetingAction> decision = pendingDecision;
        if (decision != null) {
            decision.complete(Liepin.GreetingAction.SKIP);
        }
    }

    private void cancelPendingGreeting(SuspendedRetryRun run) {
        CompletableFuture<Liepin.GreetingAction> decision = run.pendingDecision;
        if (decision != null) {
            decision.complete(Liepin.GreetingAction.SKIP);
        }
    }

    private void publish(Consumer<JobProgressMessage> progressCallback, JobProgressMessage message) {
        if (message == null) {
            return;
        }
        if (message.getTimestamp() == null) {
            message.setTimestamp(System.currentTimeMillis());
        }
        lastMessage = message.getMessage();
        lastMessageType = message.getType();
        lastMessageAt = message.getTimestamp();
        recentStatus.add(message);
        if (progressCallback != null) {
            progressCallback.accept(message);
        }
    }

    private void publish(SuspendedRetryRun run, JobProgressMessage message) {
        if (message == null) {
            return;
        }
        if (message.getTimestamp() == null) {
            message.setTimestamp(System.currentTimeMillis());
        }
        run.lastMessage = message.getMessage();
        run.lastMessageType = message.getType();
        run.lastMessageAt = message.getTimestamp();
        run.recentStatus.add(message);
        if (run.activeProgressCallback != null) {
            run.activeProgressCallback.accept(message);
        }
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    /** 普通投递和暂缓重试任一任务槽运行时为 true。 */
    public boolean isAnyTaskRunning() {
        return hasActiveTask();
    }

    public boolean isSuspendedRetryRunning() {
        return suspendedRetry.isRunning;
    }

    public long getRunId() {
        return runId;
    }

    public int getBatchRetryInitialPending() {
        return batchRetryInitialPending;
    }

    public long getSuspendedRetryRunId() {
        return suspendedRetry.runId;
    }

    public int getSuspendedRetryInitialPending() {
        return suspendedRetry.batchRetryInitialPending;
    }

    public boolean shouldStop() {
        return shouldStop || forcedCancellation;
    }

    private boolean shouldStopSuspendedRetry() {
        return suspendedRetry.shouldStop;
    }

    
}
