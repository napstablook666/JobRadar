package com.jobradar.worker.service;

import com.jobradar.application.service.ConfigService;
import com.jobradar.worker.dto.JobProgressMessage;
import com.jobradar.worker.manager.PlaywrightManager;
import com.jobradar.worker.zhilian.ZhiLian;
import com.jobradar.worker.zhilian.ZhilianConfig;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * 智联招聘任务服务
 * 管理智联招聘平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZhilianJobService implements JobPlatformService {
    private static final String PLATFORM = "zhilian";

    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<ZhiLian> zhilianProvider;
    private final ConfigService configService;

    @Autowired
    @Qualifier("deliveryExecutor")
    private Executor deliveryExecutor = ForkJoinPool.commonPool();

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
    private volatile boolean shouldStop = false;
    // 最近一次任务状态，供管理页轮询显示实际执行结果
    private volatile String lastMessage = "尚未启动投递任务";
    private volatile String lastMessageType = "idle";
    private volatile long lastMessageAt = System.currentTimeMillis();

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        if (!claimRun()) {
            publish(progressCallback, JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }
        runClaimed(progressCallback);
    }

    @Override
    public boolean startDeliveryAsync(Consumer<JobProgressMessage> progressCallback) {
        if (!claimRun()) {
            return false;
        }
        try {
            deliveryExecutor.execute(() -> runClaimed(progressCallback));
            return true;
        } catch (RuntimeException e) {
            finishRun();
            log.error("提交智联招聘投递任务失败", e);
            return false;
        }
    }

    private synchronized boolean claimRun() {
        if (isRunning) {
            return false;
        }
        isRunning = true;
        shouldStop = false;
        return true;
    }

    private void runClaimed(Consumer<JobProgressMessage> progressCallback) {
        try {
            playwrightManager.withPlatformAccess(PLATFORM,
                    () -> executeDeliveryInternal(progressCallback));
        } catch (Exception e) {
            log.error("智联招聘投递任务访问浏览器失败", e);
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
            finishRun();
        }
    }

    private void executeDeliveryInternal(Consumer<JobProgressMessage> progressCallback) {
        try {
            // 获取智联招聘页面实例
            Page page = playwrightManager.ensureZhilianPageReady();
            if (page == null) {
                publish(progressCallback, JobProgressMessage.error(PLATFORM, "智联招聘页面未初始化"));
                return;
            }

            // 检查是否已登录
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                publish(progressCallback, JobProgressMessage.error(PLATFORM, "请先登录智联招聘"));
                return;
            }

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pauseZhilianMonitoring();

            // 加载配置（统一从 zhilian_config 专表读取）
            ZhilianConfig config = configService.getZhilianConfig();
            publish(progressCallback, JobProgressMessage.info(PLATFORM, "配置加载成功"));

            publish(progressCallback, JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建ZhiLian实例并执行投递
            ZhiLian.ProgressCallback zhilianCallback = (message, current, total) -> {
                if (current != null && total != null) {
                    publish(progressCallback, JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    publish(progressCallback, JobProgressMessage.info(PLATFORM, message));
                }
            };

            ZhiLian zhilian = zhilianProvider.getObject();
            zhilian.setPage(page);
            zhilian.setConfig(config);
            zhilian.setProgressCallback(zhilianCallback);
            zhilian.setShouldStopCallback(this::shouldStop);
            zhilian.prepare();

            int deliveredCount = zhilian.execute();

            publish(progressCallback, JobProgressMessage.success(PLATFORM,
                String.format("投递任务完成，共投递%d个职位", deliveredCount)));
        } catch (Exception e) {
            log.error("智联招聘投递任务执行失败", e);
            publish(progressCallback, JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            finishRun();
        }
    }

    private synchronized void finishRun() {
        isRunning = false;
        shouldStop = false;
        try {
            playwrightManager.resumeZhilianMonitoring();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void stopDelivery() {
        if (isRunning) {
            log.info("收到停止智联招聘投递任务的请求");
            shouldStop = true;
            lastMessage = "正在停止投递任务...";
            lastMessageType = "info";
            lastMessageAt = System.currentTimeMillis();
        }
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.put("message", lastMessage);
        status.put("messageType", lastMessageType);
        status.put("messageAt", lastMessageAt);
        return status;
    }

    private void publish(Consumer<JobProgressMessage> progressCallback, JobProgressMessage message) {
        lastMessage = message.getMessage();
        lastMessageType = message.getType();
        lastMessageAt = message.getTimestamp() == null
                ? System.currentTimeMillis()
                : message.getTimestamp();
        progressCallback.accept(message);
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
