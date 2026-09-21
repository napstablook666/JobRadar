package com.jobradar.worker.service;

import com.jobradar.application.service.ConfigService;
import com.jobradar.worker.boss.Boss;
import com.jobradar.worker.boss.BossConfig;
import com.jobradar.worker.dto.JobProgressMessage;
import com.jobradar.worker.manager.PlaywrightManager;
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
 * Boss直聘任务服务
 * 管理Boss平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;

    @Autowired
    @Qualifier("deliveryExecutor")
    private Executor deliveryExecutor = ForkJoinPool.commonPool();

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
    private volatile boolean shouldStop = false;

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        if (!claimRun()) {
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
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
            log.error("提交Boss投递任务失败", e);
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
            log.error("Boss投递任务访问浏览器失败", e);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
            finishRun();
        }
    }

    private void executeDeliveryInternal(Consumer<JobProgressMessage> progressCallback) {
        try {
            // 获取Boss页面实例
            Page page = playwrightManager.ensureBossPageReady();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "Boss页面未初始化"));
                return;
            }

            // 检查是否已登录
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录Boss直聘"));
                return;
            }

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pauseBossMonitoring();

            // 加载配置（统一从 boss_config 专表读取）
            BossConfig config = configService.getBossConfig();
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建Boss实例并执行投递
            Boss.ProgressCallback bossCallback = (message, current, total) -> {
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            Boss boss = bossProvider.getObject();
            boss.setPage(page);
            boss.setConfig(config);
            boss.setProgressCallback(bossCallback);
            boss.setShouldStopCallback(this::shouldStop);
            boss.prepare();

            int deliveredCount = boss.execute();

            progressCallback.accept(JobProgressMessage.success(PLATFORM,
                String.format("投递任务完成，共发起%d个聊天", deliveredCount)));
        } catch (Exception e) {
            log.error("Boss投递任务执行失败", e);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            finishRun();
        }
    }

    private synchronized void finishRun() {
        isRunning = false;
        shouldStop = false;
        try {
            playwrightManager.resumeBossMonitoring();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void stopDelivery() {
        if (isRunning) {
            log.info("收到停止Boss投递任务的请求");
            shouldStop = true;
        }
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        return status;
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
     * 供Boss.java调用
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    
}
