package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.LiepinService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.manager.ReadRequestStatus;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.concurrent.TimeUnit;
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
    private static final long SHUTDOWN_WAIT_MILLIS = 30_000L;

    public enum TaskState {
        IDLE,
        RUNNING,
        STOPPING,
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

    private static Map<String, Object> emptyDeliverySummary() {
        return Map.of(
                "scanned", 0,
                "salaryEligible", 0,
                "salarySkipped", 0,
                "otherSkipped", 0,
                "delivered", 0
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
        runClaimed(progressCallback);
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
        CompletableFuture.runAsync(() -> runClaimed(progressCallback));
        return true;
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
        if (isRunning) {
            return false;
        }
        List<Long> suspendedIds = liepinService.listSuspendedRetryJobIds();
        if (suspendedIds.isEmpty()) {
            return false;
        }
        return startDeliveryAsync(
                progressCallback,
                RetryMode.SUSPENDED_BATCH,
                suspendedIds.size(),
                new HashSet<>(suspendedIds)
        );
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
        if (isRunning) {
            return false;
        }
        isRunning = true;
        runId++;
        taskState = TaskState.RUNNING;
        shouldStop = false;
        activeProgressCallback = progressCallback;
        aiSummary = emptyAiSummary();
        deliverySummary = emptyDeliverySummary();
        lastReadRequestStatus = ReadRequestStatus.browser(false, "尚未读取猎聘搜索");
        lastDeliveredCount = 0;
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

    private void runClaimed(Consumer<JobProgressMessage> progressCallback) {
        try {
            playwrightManager.withPlaywrightAccess(() -> executeDeliveryInternal(progressCallback));
        } catch (Exception e) {
            log.error("猎聘投递任务访问浏览器失败", e);
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
            finishRun();
        }
    }

    private void executeDeliveryInternal(Consumer<JobProgressMessage> progressCallback) {
        Liepin liepin = null;
        try {
            Page page = playwrightManager.ensureLiepinPageReady();
            if (page == null) {
                taskState = TaskState.FAILED;
                publish(progressCallback, JobProgressMessage.error(PLATFORM, "猎聘页面未初始化"));
                return;
            }

            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                taskState = TaskState.FAILED;
                publish(progressCallback, JobProgressMessage.error(PLATFORM, "请先登录猎聘"));
                return;
            }

            // 暂停后台登录监控，避免并发访问冲突
            playwrightManager.pauseLiepinMonitoring();

            // 加载配置（统一通过 ConfigService 从专表读取）
            LiepinConfig config = configService.getLiepinConfig();
            publish(progressCallback, JobProgressMessage.info(PLATFORM, "配置加载成功"));

            publish(progressCallback, JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建并执行 Bean
            Liepin.ProgressCallback cb = (message, current, total) -> {
                if (current != null && total != null) {
                    publish(progressCallback, JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    publish(progressCallback, JobProgressMessage.info(PLATFORM, message));
                }
            };

            liepin = liepinProvider.getObject();
            liepin.setPage(page);
            liepin.setConfig(config);
            liepin.setProgressCallback(cb);
            liepin.setShouldStopCallback(this::shouldStop);
            liepin.setGreetingConfirmation(this::awaitGreetingConfirmation);
            liepin.setPageRecovery(playwrightManager::ensureLiepinPageReady);
            Liepin activeLiepin = liepin;
            liepin.setStatsChangedCallback(() -> refreshRunStats(activeLiepin));

            if (retryMode == RetryMode.AI_BATCH) {
                executeBatchRetryRounds(progressCallback, liepin, config);
                return;
            }
            if (retryMode == RetryMode.SUSPENDED_BATCH) {
                liepin.setPendingRetryJobIds(pendingRetryJobIds);
                executeSuspendedBatchRetryRounds(progressCallback, liepin);
                return;
            }

            Liepin.ExecutionResult executionResult = liepin.execute();
            int deliveredCount = executionResult.deliveredCount();
            lastDeliveredCount = deliveredCount;
            refreshRunStats(liepin);
            switch (executionResult.outcome()) {
                case USER_PAUSED -> {
                    taskState = TaskState.PAUSED;
                    publish(progressCallback, buildPausedMessage(deliveredCount));
                }
                case RATE_LIMITED -> {
                    taskState = TaskState.PAUSED;
                    publish(progressCallback, buildRateLimitedMessage(deliveredCount, executionResult.detail()));
                }
                case COMPLETED -> {
                    taskState = TaskState.COMPLETED;
                    publish(progressCallback, buildCompletionMessage(deliveredCount, deliverySummary));
                }
            }
        } catch (Liepin.PageLifecycleException e) {
            log.error("猎聘投递任务因页面生命周期中断", e);
            taskState = TaskState.FAILED;
            String message = e.sideEffectStarted()
                    ? "浏览器页面在发送过程中失效，发送结果不确定，任务已暂停，请检查后重试"
                    : "猎聘页面已失效，任务已暂停，请重新启动投递";
            publish(progressCallback, JobProgressMessage.error(PLATFORM, message));
        } catch (Exception e) {
            log.error("猎聘投递任务执行失败", e);
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            refreshRunStats(liepin);
            finishRun();
        }
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
                        totalDelivered, batchRetryRound));
                return;
            }

            if (action == BatchRetryAction.PAUSE) {
                batchRetryReason = result.outcome() == Liepin.ExecutionOutcome.RATE_LIMITED
                        ? result.detail()
                        : currentPending >= previousPending
                        ? "本轮待处理数量没有下降"
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

    private void finishRun() {
        cancelPendingGreeting();
        isRunning = false;
        shouldStop = false;
        activeProgressCallback = null;
        synchronized (lifecycleMonitor) {
            lifecycleMonitor.notifyAll();
        }
        try {
            playwrightManager.resumeLiepinMonitoring();
        } catch (Exception ignored) {}
    }

    /**
     * Spring 关闭应用时先给投递线程收尾，确保最近完成的页码已经落盘。
     * 半页不会伪装成完成页，下次仍会从该页重跑。
     */
    @PreDestroy
    public void shutdown() {
        if (!stopAndAwait(SHUTDOWN_WAIT_MILLIS)) {
            log.warn("猎聘任务在{}ms内未完成停止，继续关闭应用；下次将从最近完整页恢复", SHUTDOWN_WAIT_MILLIS);
        }
    }

    /** 请求停止并等待后台任务收尾，供关闭钩子和停止脚本使用。 */
    boolean stopAndAwait(long timeoutMillis) {
        stopDelivery();
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        synchronized (lifecycleMonitor) {
            while (isRunning) {
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
        return !isRunning;
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
        taskState = TaskState.STOPPING;
        shouldStop = true;
        cancelPendingGreeting();
        publish(activeProgressCallback, JobProgressMessage.info(PLATFORM, "正在暂停投递任务..."));
    }

    /**
     * 获取任务状态
     */
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("runId", runId);
        status.put("isRunning", isRunning);
        status.put("taskState", taskState.name());
        status.put("lastDeliveredCount", lastDeliveredCount);
        status.put("retryMode", retryMode.name());
        Map<String, Object> batchRetry = new HashMap<>();
        batchRetry.put("active", isRunning
                && (retryMode == RetryMode.AI_BATCH || retryMode == RetryMode.SUSPENDED_BATCH));
        batchRetry.put("scope", retryMode == RetryMode.SUSPENDED_BATCH ? "SUSPENDED" :
                retryMode == RetryMode.AI_BATCH ? "AI_TIMEOUT" : "NONE");
        batchRetry.put("round", batchRetryRound);
        batchRetry.put("initialPending", batchRetryInitialPending);
        batchRetry.put("remaining", batchRetryRemaining);
        batchRetry.put("delivered", batchRetryDelivered);
        batchRetry.put("reason", batchRetryReason);
        status.put("batchRetry", batchRetry);
        status.put("resumeAvailable", taskState == TaskState.PAUSED || taskState == TaskState.FAILED
                || number(aiSummary, "aiRetryable") > 0
                || (retryMode == RetryMode.SUSPENDED_BATCH && batchRetryRemaining > 0));
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.put("message", lastMessage);
        status.put("messageType", lastMessageType);
        status.put("messageAt", lastMessageAt);
        List<JobProgressMessage> recentMessages = recentStatus.snapshot();
        if (recentMessages.isEmpty()) {
            recentMessages = List.of(new JobProgressMessage(
                    PLATFORM, lastMessageType, lastMessage, null, null, lastMessageAt));
        }
        status.put("recentMessages", recentMessages);
        status.put("aiSummary", aiSummary);
        status.put("deliverySummary", deliverySummary);
        status.put("readRequest", lastReadRequestStatus.asMap());
        Liepin.GreetingRequest pending = pendingGreeting;
        if (pending != null) {
            Map<String, Object> pendingData = new HashMap<>();
            pendingData.put("jobId", pending.jobId());
            pendingData.put("companyName", pending.companyName());
            pendingData.put("jobTitle", pending.jobTitle());
            pendingData.put("salary", pending.salary());
            pendingData.put("jd", pending.jd());
            pendingData.put("message", pending.message());
            pendingData.put("aiAvailable", pending.aiAvailable());
            pendingData.put("source", pending.aiAvailable() ? "ai" : "preset");
            status.put("pendingGreeting", pendingData);
        } else {
            status.put("pendingGreeting", null);
        }
        return status;
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

    private void cancelPendingGreeting() {
        CompletableFuture<Liepin.GreetingAction> decision = pendingDecision;
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

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    public long getRunId() {
        return runId;
    }

    public int getBatchRetryInitialPending() {
        return batchRetryInitialPending;
    }

    public boolean shouldStop() {
        return shouldStop;
    }

    
}
