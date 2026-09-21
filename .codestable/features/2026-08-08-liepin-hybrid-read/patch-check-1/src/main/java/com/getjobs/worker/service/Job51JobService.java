package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.job51.Job51;
import com.getjobs.worker.job51.Job51Config;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.manager.ReadRequestStatus;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    public enum TaskState {
        IDLE,
        WAITING,
        RUNNING,
        STOPPING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<Job51> job51Provider;
    private final ConfigService configService;

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
    private volatile int lastAiProcessedCount;
    private volatile ReadRequestStatus lastReadRequestStatus =
            ReadRequestStatus.browser(false, "尚未读取51job搜索");
    private volatile Consumer<JobProgressMessage> activeProgressCallback;
    private volatile String lastMessage = "尚未启动投递任务";
    private volatile String lastMessageType = "idle";
    private volatile long lastMessageAt = System.currentTimeMillis();
    private final StatusHistory recentStatus = new StatusHistory(RECENT_MESSAGE_LIMIT);

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
        CompletableFuture.runAsync(() -> runClaimed(progressCallback));
        return true;
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
        lastAiProcessedCount = 0;
        lastReadRequestStatus = ReadRequestStatus.browser(false, "尚未读取51job搜索");
        recentStatus.clear();
        publish(progressCallback, JobProgressMessage.info(PLATFORM, "正在初始化投递任务..."));
        return true;
    }

    private void runClaimed(Consumer<JobProgressMessage> progressCallback) {
        try {
            publish(progressCallback, JobProgressMessage.info(PLATFORM, "正在等待浏览器资源..."));
            boolean acquired = playwrightManager.withPlaywrightAccessCancellable(
                    this::shouldStop,
                    () -> executeDeliveryInternal(progressCallback)
            );
            if (!acquired) {
                taskState = TaskState.CANCELLED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM, "投递任务已取消，尚未开始投递"));
                finishRun();
            }
        } catch (Exception e) {
            log.error("51job投递任务访问浏览器失败", e);
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
            finishRun();
        }
    }

    private void executeDeliveryInternal(Consumer<JobProgressMessage> progressCallback) {
        try {
            if (shouldStop()) {
                taskState = TaskState.CANCELLED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM, "投递任务已取消，尚未开始投递"));
                return;
            }
            taskState = TaskState.RUNNING;

            // 获取可用的51job页面；Page/Context失效时由管理器重连或重建
            Page page = playwrightManager.ensureJob51PageReady();
            if (page == null) {
                taskState = TaskState.FAILED;
                publish(progressCallback, JobProgressMessage.error(PLATFORM, "51job页面未初始化"));
                return;
            }

            // 检查是否已登录
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                taskState = TaskState.FAILED;
                publish(progressCallback, JobProgressMessage.error(PLATFORM, "请先登录51job"));
                return;
            }

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pause51jobMonitoring();

            // 加载配置（统一从 job51_config 专表读取）
            Job51Config config = configService.getJob51Config();
            aiEnabled = config.isAiEnabled();
            publish(progressCallback, JobProgressMessage.info(PLATFORM, "配置加载成功"));

            publish(progressCallback, JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建Job51实例并执行投递
            Job51.ProgressCallback job51Callback = (message, current, total) -> {
                // 拦截特定警告：当前页未采集到任何 jobId => 视为达到投递上限，自动停止并告警
                if (message != null && message.contains("当前页未采集到任何 jobId")) {
                    publish(progressCallback, JobProgressMessage.warning(PLATFORM, "检测到当前页无岗位ID，疑似达到上限或页面变化，任务已停止"));
                    // 设置停止标志，Job51 将在下一次 shouldStop() 检查时退出
                    stopDelivery();
                    return;
                }
                if (current != null && total != null) {
                    publish(progressCallback, JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    publish(progressCallback, JobProgressMessage.info(PLATFORM, message));
                }
            };

            Job51 job51 = job51Provider.getObject();
            job51.setPage(page);
            job51.setConfig(config);
            job51.setProgressCallback(job51Callback);
            job51.setShouldStopCallback(this::shouldStop);
            job51.setPageRecovery(playwrightManager::ensureJob51PageReady);
            job51.prepare();
            activeJob51 = job51;

            int deliveredCount = job51.execute();
            lastGreetingSentCount = job51.getGreetingSentCount();
            lastGreetingSkippedCount = job51.getGreetingSkippedCount();
            lastGreetingFailedCount = job51.getGreetingFailedCount();
            lastAiProcessedCount = job51.getAiProcessedCount();
            ReadRequestStatus currentReadRequestStatus = job51.getReadRequestStatus();
            if (currentReadRequestStatus != null) {
                lastReadRequestStatus = currentReadRequestStatus;
            }

            if (job51.getExecutionError() != null) {
                taskState = TaskState.FAILED;
                publish(progressCallback, JobProgressMessage.error(PLATFORM,
                    "投递失败: " + job51.getExecutionError()));
            } else if ("user_cancelled".equals(job51.getStopReason())) {
                taskState = TaskState.CANCELLED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM,
                    String.format("投递已停止，已确认成功%d个职位", deliveredCount)));
            } else if ("daily_limit".equals(job51.getStopReason())) {
                taskState = TaskState.COMPLETED;
                publish(progressCallback, JobProgressMessage.warning(PLATFORM,
                    String.format("检测到日投递上限，已确认成功%d个职位", deliveredCount)));
            } else {
                taskState = TaskState.COMPLETED;
                publish(progressCallback, JobProgressMessage.success(PLATFORM,
                    String.format("投递任务完成，已确认成功%d个职位", deliveredCount)));
            }
        } catch (Exception e) {
            log.error("51job投递任务执行失败", e);
            taskState = TaskState.FAILED;
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            finishRun();
        }
    }

    private void finishRun() {
        isRunning = false;
        shouldStop = false;
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
            taskState = TaskState.STOPPING;
            shouldStop = true;
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
        status.put("readRequest", current == null
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
