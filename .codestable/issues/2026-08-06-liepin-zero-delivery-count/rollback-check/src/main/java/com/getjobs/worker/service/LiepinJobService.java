package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
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

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Liepin> liepinProvider;

    // 运行状态标志
    private volatile boolean isRunning = false;

    // 停止请求标志
    private volatile boolean shouldStop = false;

    // 最近一次任务消息，供页面轮询显示真实状态
    private volatile String lastMessage = "尚未启动投递任务";
    private volatile String lastMessageType = "idle";
    private volatile long lastMessageAt = System.currentTimeMillis();
    private final Object pendingLock = new Object();
    private volatile Liepin.GreetingRequest pendingGreeting;
    private volatile CompletableFuture<Liepin.GreetingAction> pendingDecision;
    private volatile Consumer<JobProgressMessage> activeProgressCallback;

    public record ConfirmationResult(boolean accepted, String message) {
    }

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        playwrightManager.withPlaywrightAccess(() -> executeDeliveryInternal(progressCallback));
    }

    private void executeDeliveryInternal(Consumer<JobProgressMessage> progressCallback) {
        if (isRunning) {
            publish(progressCallback, JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }

        // 任务刚进入异步线程就占用运行状态，页面不会在初始化阶段误显示空闲。
        isRunning = true;
        shouldStop = false;
        activeProgressCallback = progressCallback;
        publish(progressCallback, JobProgressMessage.info(PLATFORM, "正在初始化投递任务..."));

        try {
            Page page = playwrightManager.ensureLiepinPageReady();
            if (page == null) {
                publish(progressCallback, JobProgressMessage.error(PLATFORM, "猎聘页面未初始化"));
                return;
            }

            if (!playwrightManager.isLoggedIn(PLATFORM)) {
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

            Liepin liepin = liepinProvider.getObject();
            liepin.setPage(page);
            liepin.setConfig(config);
            liepin.setProgressCallback(cb);
            liepin.setShouldStopCallback(this::shouldStop);
            liepin.setGreetingConfirmation(this::awaitGreetingConfirmation);
            liepin.setPageRecovery(playwrightManager::ensureLiepinPageReady);

            int deliveredCount = liepin.execute();

            publish(progressCallback, JobProgressMessage.success(PLATFORM,
                String.format("投递任务完成，共发起%d个聊天", deliveredCount)));
        } catch (Liepin.PageLifecycleException e) {
            log.error("猎聘投递任务因页面生命周期中断", e);
            String message = e.sideEffectStarted()
                    ? "浏览器页面在发送过程中失效，发送结果不确定，任务已暂停，请检查后重试"
                    : "猎聘页面已失效，任务已暂停，请重新启动投递";
            publish(progressCallback, JobProgressMessage.error(PLATFORM, message));
        } catch (Exception e) {
            log.error("猎聘投递任务执行失败", e);
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            cancelPendingGreeting();
            isRunning = false;
            shouldStop = false;
            activeProgressCallback = null;
            try {
                playwrightManager.resumeLiepinMonitoring();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void stopDelivery() {
        if (!isRunning) {
            log.warn("猎聘任务未在运行，无需停止");
            return;
        }
        log.info("收到停止猎聘任务请求");
        shouldStop = true;
        cancelPendingGreeting();
        lastMessage = "正在停止投递任务...";
        lastMessageType = "info";
        lastMessageAt = System.currentTimeMillis();
    }

    /**
     * 获取任务状态
     */
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.put("message", lastMessage);
        status.put("messageType", lastMessageType);
        status.put("messageAt", lastMessageAt);
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
        lastMessage = message.getMessage();
        lastMessageType = message.getType();
        lastMessageAt = message.getTimestamp() == null
                ? System.currentTimeMillis()
                : message.getTimestamp();
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

    public boolean shouldStop() {
        return shouldStop;
    }

    
}
