package com.jobradar.worker.service;

import com.jobradar.application.service.ConfigService;
import com.jobradar.worker.dto.JobProgressMessage;
import com.jobradar.worker.job51.Job51;
import com.jobradar.worker.job51.Job51Config;
import com.jobradar.worker.manager.PlaywrightManager;
import com.jobradar.worker.manager.ReadRequestStatus;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Consumer;

/**
 * 51job任务服务
 * 管理51job平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Job51JobService implements JobPlatformService {
    private static final String PLATFORM = "51job";
    private static final int RECENT_MESSAGE_LIMIT = 5;
    private static final long VERIFICATION_TIMEOUT_MS = 120_000L;

    private static ScheduledExecutorService createFallbackScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Job51-Resume-Fallback");
            thread.setDaemon(true);
            return thread;
        });
    }

    public enum TaskState {
        IDLE,
        WAITING,
        RUNNING,
        RESTING,
        VERIFICATION_REQUIRED,
        WAITING_VERIFICATION,
        STOPPING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<Job51> job51Provider;
    private final ConfigService configService;

    @Autowired
    @Qualifier("deliveryExecutor")
    private Executor deliveryExecutor = ForkJoinPool.commonPool();

    @Autowired(required = false)
    @Qualifier("deliveryResumeScheduler")
    private ScheduledExecutorService deliveryResumeScheduler = createFallbackScheduler();

    // 任务运行状态
    private volatile boolean isRunning = false;
    private volatile TaskState taskState = TaskState.IDLE;
    // 停止标志
    private volatile boolean shouldStop = false;
    private volatile Job51 activeJob51;
    private volatile boolean aiEnabled;
    private volatile int lastGreetingSentCount;
    private volatile int lastGreetingSkippedCount;
    private volatile int lastGreetingFailedCount;
    private volatile int lastAiDeliveredCount;
    private volatile int lastAiProcessedCount;
    private volatile Map<String, Object> deliverySummary = emptyDeliverySummary();
    private volatile String restReason;
    private volatile long restUntil;
    private volatile int restAttempt;
    private volatile ReadRequestStatus lastReadRequestStatus =
            ReadRequestStatus.browser(false, "尚未读取51job搜索");
    private volatile Consumer<JobProgressMessage> activeProgressCallback;
    private volatile String lastMessage = "尚未启动投递任务";
    private volatile String lastMessageType = "idle";
    private volatile long lastMessageAt = System.currentTimeMillis();
    private volatile ScheduledFuture<?> scheduledResume;
    private final StatusHistory recentStatus = new StatusHistory(RECENT_MESSAGE_LIMIT);

    private static Map<String, Object> emptyDeliverySummary() {
        return Map.ofEntries(
                Map.entry("candidates", 0),
                Map.entry("aiProcessed", 0),
                Map.entry("greetingSent", 0),
                Map.entry("aiDelivered", 0),
                Map.entry("skipped", 0),
                Map.entry("failed", 0),
                Map.entry("selected", 0),
                Map.entry("confirmed", 0)
        );
    }

    private static Map<String, Object> deliverySummary(Job51 job51) {
        if (job51 == null) {
            return emptyDeliverySummary();
        }
        return Map.ofEntries(
                Map.entry("candidates", job51.getAiCandidateCount()),
                Map.entry("aiProcessed", job51.getAiProcessedCount()),
                Map.entry("greetingSent", job51.getGreetingSentCount()),
                Map.entry("aiDelivered", job51.getAiDeliveredCount()),
                Map.entry("skipped", job51.getGreetingSkippedCount()),
                Map.entry("failed", job51.getGreetingFailedCount()),
                Map.entry("selected", job51.getSelectedCount()),
                Map.entry("confirmed", job51.getConfirmedSuccessCount())
        );
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

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        if (!claimRun(progressCallback)) {
            publish(progressCallback, JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }
        runClaimed(progressCallback);
    }

    /** 原子占用运行状态后异步执行，避免启动接口返回成功但任务尚未占位。 */
    public boolean startDeliveryAsync(Consumer<JobProgressMessage> progressCallback) {
        if (!claimRun(progressCallback)) {
            return false;
        }
        try {
            deliveryExecutor.execute(() -> runClaimed(progressCallback));
            return true;
        } catch (RuntimeException e) {
            isRunning = false;
            shouldStop = false;
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "提交投递任务失败: " + e.getMessage()));
            return false;
        }
    }

    private synchronized boolean claimRun(Consumer<JobProgressMessage> progressCallback) {
        if (isRunning) {
            return false;
        }
        isRunning = true;
        taskState = TaskState.WAITING;
        shouldStop = false;
        activeJob51 = null;
        activeProgressCallback = progressCallback;
        lastGreetingSentCount = 0;
        lastGreetingSkippedCount = 0;
        lastGreetingFailedCount = 0;
        lastAiDeliveredCount = 0;
        lastAiProcessedCount = 0;
        deliverySummary = emptyDeliverySummary();
        restReason = null;
        restUntil = 0L;
        restAttempt = 0;
        scheduledResume = null;
        lastReadRequestStatus = ReadRequestStatus.browser(false, "尚未读取51job搜索");
        recentStatus.clear();
        publish(progressCallback, JobProgressMessage.info(PLATFORM, "正在初始化投递任务..."));
        return true;
    }

    private void runClaimed(Consumer<JobProgressMessage> progressCallback) {
        try {
            if (shouldStop()) {
                taskState = TaskState.CANCELLED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM, "投递任务已取消"));
                finishRun();
                return;
            }
            publish(progressCallback, JobProgressMessage.info(PLATFORM, "正在等待浏览器资源..."));
            AtomicReference<Job51.ExecutionResult> resultRef = new AtomicReference<>();
            boolean acquired = playwrightManager.withPlaywrightAccessCancellable(
                    this::shouldStop,
                    () -> resultRef.set(executeDeliveryInternal(progressCallback))
            );
            if (!acquired) {
                taskState = TaskState.CANCELLED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM, "投递任务已取消，尚未开始投递"));
                finishRun();
                return;
            }

            Job51.ExecutionResult result = resultRef.get();
            if (result == null) {
                taskState = TaskState.FAILED;
                publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败：执行结果为空"));
                finishRun();
                return;
            }

            switch (result.outcome()) {
                case REST_REQUIRED -> {
                    scheduleRest(result.restRequest(), progressCallback);
                    return;
                }
                case VERIFICATION_REQUIRED -> {
                    handleVerification(result.verificationRequest(), progressCallback);
                    return;
                }
                case USER_CANCELLED -> {
                    taskState = TaskState.CANCELLED;
                    publish(progressCallback, JobProgressMessage.warning(PLATFORM,
                            String.format("投递已停止，已确认成功%d个职位", result.deliveredCount())));
                }
                case FAILED -> {
                    taskState = TaskState.FAILED;
                    Job51 current = activeJob51;
                    String detail = current == null ? "执行失败" : current.getExecutionError();
                    publish(progressCallback, JobProgressMessage.error(PLATFORM,
                            "投递失败: " + (detail == null || detail.isBlank() ? "执行异常" : detail)));
                }
                case COMPLETED -> {
                    restAttempt = 0;
                    taskState = TaskState.COMPLETED;
                    Job51 current = activeJob51;
                    String message = current != null && current.getConfig() != null && current.getConfig().isAiEnabled()
                            ? String.format("51job AI处理完成，投递%d个，打招呼%d个，跳过%d个，失败%d个",
                            current.getAiDeliveredCount(), current.getGreetingSentCount(),
                            current.getGreetingSkippedCount(), current.getGreetingFailedCount())
                            : String.format("投递任务完成，已确认成功%d个职位", result.deliveredCount());
                    publish(progressCallback, JobProgressMessage.success(PLATFORM, message));
                }
            }
            finishRun();
        } catch (Exception e) {
            log.error("51job投递任务访问浏览器失败", e);
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
            finishRun();
        }
    }

    private Job51.ExecutionResult executeDeliveryInternal(Consumer<JobProgressMessage> progressCallback) {
        try {
            if (shouldStop()) {
                taskState = TaskState.CANCELLED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM, "投递任务已取消，尚未开始投递"));
                return new Job51.ExecutionResult(0, Job51.ExecutionOutcome.USER_CANCELLED, null);
            }
            taskState = TaskState.RUNNING;

            // 获取可用的51job页面；Page/Context失效时由管理器重连或重建
            Page page = playwrightManager.ensureJob51PageReady();
            if (page == null) {
                taskState = TaskState.FAILED;
                publish(progressCallback, JobProgressMessage.error(PLATFORM, "51job页面未初始化"));
                return new Job51.ExecutionResult(0, Job51.ExecutionOutcome.FAILED, null);
            }

            // 检查是否已登录
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                taskState = TaskState.FAILED;
                publish(progressCallback, JobProgressMessage.error(PLATFORM, "请先登录51job"));
                return new Job51.ExecutionResult(0, Job51.ExecutionOutcome.FAILED, null);
            }

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pause51jobMonitoring();

            // 每次恢复都重新读取配置；当前倒计时仍使用休息触发时的快照。
            Job51Config config = configService.getJob51Config();
            aiEnabled = config.isAiEnabled();
            Job51.ProgressCallback job51Callback = (message, current, total) -> {
                if (current != null && total != null) {
                    publish(progressCallback, JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    publish(progressCallback, JobProgressMessage.info(PLATFORM, message));
                }
            };

            Job51 job51 = activeJob51;
            if (job51 == null) {
                publish(progressCallback, JobProgressMessage.info(PLATFORM, "配置加载成功"));
                publish(progressCallback, JobProgressMessage.info(PLATFORM, "开始投递任务..."));
                job51 = job51Provider.getObject();
                job51.setProgressCallback(job51Callback);
                job51.setShouldStopCallback(this::shouldStop);
                job51.setPageRecovery(playwrightManager::ensureJob51PageReady);
                job51.prepare();
                activeJob51 = job51;
            } else if (job51.getRestRequest() != null) {
                job51.prepareForResume(job51.getRestRequest());
                publish(progressCallback, JobProgressMessage.info(PLATFORM, "间歇休息结束，恢复当前关键词和页面"));
            }
            job51.setPage(page);
            job51.setConfig(config);

            Job51.ExecutionResult result = job51.executeWithResult();
            lastGreetingSentCount = job51.getGreetingSentCount();
            lastGreetingSkippedCount = job51.getGreetingSkippedCount();
            lastGreetingFailedCount = job51.getGreetingFailedCount();
            lastAiDeliveredCount = job51.getAiDeliveredCount();
            lastAiProcessedCount = job51.getAiProcessedCount();
            deliverySummary = deliverySummary(job51);
            ReadRequestStatus currentReadRequestStatus = job51.getReadRequestStatus();
            if (currentReadRequestStatus != null) {
                lastReadRequestStatus = currentReadRequestStatus;
            }
            return result;
        } catch (Exception e) {
            log.error("51job投递任务执行失败", e);
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
            return new Job51.ExecutionResult(
                    activeJob51 == null ? 0 : activeJob51.getConfirmedSuccessCount()
                            + activeJob51.getAiDeliveredCount() + activeJob51.getGreetingSentCount(),
                    Job51.ExecutionOutcome.FAILED,
                    null
            );
        } finally {
            // 休息期间恢复登录监控；下一次执行片段重新暂停。
        }
    }

    private void handleVerification(Job51.VerificationRequest request,
                                    Consumer<JobProgressMessage> progressCallback) {
        if (request == null) {
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "访问验证请求缺少恢复游标"));
            finishRun();
            return;
        }
        if (shouldStop()) {
            taskState = TaskState.CANCELLED;
            publish(progressCallback, JobProgressMessage.warning(PLATFORM, "验证等待前收到停止请求"));
            finishRun();
            return;
        }

        taskState = TaskState.VERIFICATION_REQUIRED;
        publish(progressCallback, JobProgressMessage.warning(PLATFORM,
                String.format("岗位%s需要人工完成访问验证，正在准备可见验证窗口", request.title())));
        taskState = TaskState.WAITING_VERIFICATION;

        boolean resolved = false;
        String verificationLaunchFailure = null;
        try {
            resolved = playwrightManager.waitForJob51Verification(
                    request.detailUrl(),
                    playwrightManager.getSessionSnapshot(PLATFORM),
                    this::shouldStop,
                    () -> publish(progressCallback, JobProgressMessage.info(PLATFORM,
                            "验证窗口已打开，请完成页面验证；系统会自动检测并恢复当前岗位")),
                    VERIFICATION_TIMEOUT_MS
            );
        } catch (Exception e) {
            verificationLaunchFailure = e.getMessage();
            if (verificationLaunchFailure == null || verificationLaunchFailure.isBlank()) {
                verificationLaunchFailure = e.getClass().getSimpleName();
            }
            log.warn("51job访问验证会话失败", e);
            publish(progressCallback, JobProgressMessage.error(PLATFORM,
                    "验证窗口启动失败: " + verificationLaunchFailure));
        }

        if (shouldStop()) {
            taskState = TaskState.CANCELLED;
            publish(progressCallback, JobProgressMessage.warning(PLATFORM, "验证等待期间任务已停止"));
            finishRun();
            return;
        }

        Job51 current = activeJob51;
        if (resolved && current != null) {
            current.prepareForVerificationResume(request);
            taskState = TaskState.WAITING;
            publish(progressCallback, JobProgressMessage.success(PLATFORM,
                    "访问验证已通过，恢复当前岗位处理"));
            submitContinuation(progressCallback, "验证通过后的任务恢复提交失败");
            return;
        }

        if (current != null) {
            current.prepareForVerificationTimeout(request);
            if (current.getRestRequest() != null) {
                scheduleRest(current.getRestRequest(), progressCallback);
                return;
            }
        }
        taskState = TaskState.WAITING;
        publish(progressCallback, JobProgressMessage.warning(PLATFORM,
                verificationLaunchFailure == null
                        ? "访问验证等待超时，已跳过当前岗位并继续任务"
                        : "验证窗口启动失败，已跳过当前岗位并继续任务"));
        submitContinuation(progressCallback, "验证超时后的任务恢复提交失败");
    }

    private void submitContinuation(Consumer<JobProgressMessage> progressCallback, String failureMessage) {
        try {
            deliveryExecutor.execute(() -> runClaimed(progressCallback));
        } catch (RuntimeException e) {
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM,
                    failureMessage + ": " + e.getMessage()));
            finishRun();
        }
    }

    private synchronized void scheduleRest(Job51.RestRequest request,
                                           Consumer<JobProgressMessage> progressCallback) {
        if (request == null || !isRunning || shouldStop()) {
            taskState = TaskState.CANCELLED;
            publish(progressCallback, JobProgressMessage.warning(PLATFORM, "投递任务已取消"));
            finishRun();
            return;
        }

        Job51 current = activeJob51;
        if (current != null && current.isSuccessfulProgressSinceRest()) {
            restAttempt = 0;
        }
        restAttempt++;
        Job51Config config = configForRest(current);
        long nowMillis = System.currentTimeMillis();
        long delayMillis = restDelayMillis(request, config, restAttempt, nowMillis);
        restReason = request.reason();
        restUntil = nowMillis + delayMillis;
        taskState = TaskState.RESTING;

        try {
            playwrightManager.resume51jobMonitoring();
        } catch (Exception ignored) {
        }

        String delayText = formatDelay(delayMillis);
        String message = "daily_limit".equals(request.reason())
                ? String.format("检测到每日上限，休息至%s后自动恢复", formatClock(restUntil))
                : String.format("%s，休息%s后自动继续（第%d次）", request.message(), delayText, restAttempt);
        publish(progressCallback, JobProgressMessage.warning(PLATFORM, message));

        try {
            scheduledResume = deliveryResumeScheduler.schedule(() -> {
                synchronized (Job51JobService.this) {
                    if (!isRunning || shouldStop() || taskState != TaskState.RESTING) {
                        return;
                    }
                    scheduledResume = null;
                    taskState = TaskState.WAITING;
                }
                publish(progressCallback, JobProgressMessage.info(PLATFORM, "间歇休息结束，准备恢复任务"));
                try {
                    deliveryExecutor.execute(() -> runClaimed(progressCallback));
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

    private Job51Config configForRest(Job51 current) {
        try {
            Job51Config latest = configService.getJob51Config();
            if (latest != null) {
                return latest;
            }
        } catch (Exception e) {
            log.debug("读取最新51job休息配置失败，沿用当前配置: {}", e.getMessage());
        }
        if (current != null && current.getConfig() != null) {
            return current.getConfig();
        }
        return new Job51Config();
    }

    private long restDelayMillis(Job51.RestRequest request, Job51Config config,
                                 int attempt, long nowMillis) {
        if ("daily_limit".equals(request.reason())) {
            return Math.max(1_000L, nextLocalMidnightMillis(nowMillis, ZoneId.systemDefault()) - nowMillis);
        }
        if ("max_per_run".equals(request.reason())) {
            return ThreadLocalRandom.current().nextLong(
                    config.effectiveMaxPerRunRestMinSeconds(),
                    (long) config.effectiveMaxPerRunRestMaxSeconds() + 1L
            ) * 1000L;
        }
        int stage = restStageFor(attempt, config.effectiveRestMaxConsecutive());
        int min;
        int max;
        if (stage == 1) {
            min = config.effectiveRestStage1MinSeconds();
            max = config.effectiveRestStage1MaxSeconds();
        } else if (stage == 2) {
            min = config.effectiveRestStage2MinSeconds();
            max = config.effectiveRestStage2MaxSeconds();
        } else {
            min = config.effectiveRestStage3MinSeconds();
            max = config.effectiveRestStage3MaxSeconds();
        }
        return ThreadLocalRandom.current().nextLong(min, (long) max + 1L) * 1000L;
    }

    static int restStageFor(int attempt, int maxConsecutive) {
        if (attempt <= 1) return 1;
        if (attempt >= Math.max(1, maxConsecutive)) return 3;
        return 2;
    }

    static long nextLocalMidnightMillis(long nowMillis, ZoneId zoneId) {
        ZonedDateTime current = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(nowMillis), zoneId);
        return current.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli();
    }

    private String formatDelay(long delayMillis) {
        long seconds = Math.max(1L, delayMillis / 1000L);
        if (seconds >= 3600L) {
            return String.format("%d小时%d分钟", seconds / 3600L, (seconds % 3600L) / 60L);
        }
        if (seconds >= 60L) {
            return String.format("%d分钟", seconds / 60L);
        }
        return seconds + "秒";
    }

    private String formatClock(long timestamp) {
        return java.time.Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .toString()
                .replace('T', ' ');
    }

    private synchronized void finishRun() {
        ScheduledFuture<?> pending = scheduledResume;
        if (pending != null) {
            pending.cancel(false);
        }
        scheduledResume = null;
        isRunning = false;
        shouldStop = false;
        restReason = null;
        restUntil = 0L;
        restAttempt = 0;
        activeJob51 = null;
        activeProgressCallback = null;
        try {
            playwrightManager.resume51jobMonitoring();
        } catch (Exception ignored) {}
    }

    @Override
    public synchronized void stopDelivery() {
        if (isRunning) {
            if (taskState == TaskState.STOPPING) {
                return;
            }
            log.info("收到停止51job投递任务的请求");
            shouldStop = true;
            if (taskState == TaskState.RESTING) {
                ScheduledFuture<?> pending = scheduledResume;
                if (pending != null) {
                    pending.cancel(false);
                }
                scheduledResume = null;
                taskState = TaskState.CANCELLED;
                publish(activeProgressCallback, JobProgressMessage.warning(PLATFORM, "投递已停止，已取消当前间歇休息"));
                finishRun();
                return;
            }
            taskState = TaskState.STOPPING;
            publish(activeProgressCallback, JobProgressMessage.info(PLATFORM, "正在停止投递任务..."));
        }
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("taskState", taskState.name());
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        Job51 current = activeJob51;
        status.put("aiEnabled", current == null ? aiEnabled : current.getConfig() != null && current.getConfig().isAiEnabled());
        status.put("aiProcessedCount", current == null ? lastAiProcessedCount : current.getAiProcessedCount());
        status.put("greetingSentCount", current == null ? lastGreetingSentCount : current.getGreetingSentCount());
        status.put("greetingSkippedCount", current == null ? lastGreetingSkippedCount : current.getGreetingSkippedCount());
        status.put("greetingFailedCount", current == null ? lastGreetingFailedCount : current.getGreetingFailedCount());
        status.put("aiDeliveredCount", current == null ? lastAiDeliveredCount : current.getAiDeliveredCount());
        status.put("deliverySummary", current == null ? deliverySummary : deliverySummary(current));
        status.put("restReason", restReason);
        status.put("restUntil", restUntil);
        status.put("restRemainingMillis", restUntil <= 0L
                ? 0L : Math.max(0L, restUntil - System.currentTimeMillis()));
        status.put("restAttempt", restAttempt);
        Job51.RestRequest restRequest = current == null ? null : current.getRestRequest();
        if (restRequest == null) {
            status.put("resumeCursor", null);
        } else {
            Map<String, Object> resumeCursor = new HashMap<>();
            resumeCursor.put("keyword", restRequest.keyword());
            resumeCursor.put("keywordIndex", restRequest.keywordIndex());
            resumeCursor.put("page", restRequest.page());
            status.put("resumeCursor", resumeCursor);
        }
        Job51.VerificationRequest verificationRequest = current == null
                ? null : current.getVerificationRequest();
        if (verificationRequest == null) {
            status.put("verificationCursor", null);
        } else {
            Map<String, Object> verificationCursor = new HashMap<>();
            verificationCursor.put("jobId", verificationRequest.jobId());
            verificationCursor.put("detailUrl", verificationRequest.detailUrl());
            verificationCursor.put("keyword", verificationRequest.keyword());
            verificationCursor.put("keywordIndex", verificationRequest.keywordIndex());
            verificationCursor.put("page", verificationRequest.page());
            verificationCursor.put("title", verificationRequest.title());
            status.put("verificationCursor", verificationCursor);
        }
        status.put("readRequest", current == null
                ? lastReadRequestStatus.asMap()
                : current.getReadRequestStatus() == null
                ? lastReadRequestStatus.asMap()
                : current.getReadRequestStatus().asMap());
        status.put("message", lastMessage);
        status.put("messageType", lastMessageType);
        status.put("messageAt", lastMessageAt);
        List<JobProgressMessage> recentMessages = recentStatus.snapshot();
        if (recentMessages.isEmpty()) {
            recentMessages = List.of(new JobProgressMessage(
                    PLATFORM, lastMessageType, lastMessage, null, null, lastMessageAt));
        }
        status.put("recentMessages", recentMessages);
        return status;
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

    /**
     * 检查是否应该停止
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    
}
