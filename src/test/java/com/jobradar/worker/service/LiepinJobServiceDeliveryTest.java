package com.jobradar.worker.service;

import com.jobradar.application.service.ConfigService;
import com.jobradar.application.service.LiepinService;
import com.jobradar.worker.dto.JobProgressMessage;
import com.jobradar.worker.liepin.Liepin;
import com.jobradar.worker.liepin.LiepinAccountPacing;
import com.jobradar.worker.manager.PlaywrightManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.lang.reflect.Method;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;

class LiepinJobServiceDeliveryTest {

    @Test
    void zeroConfirmedChatsAreReportedAsWarning() {
        JobProgressMessage message = LiepinJobService.buildCompletionMessage(0);

        assertEquals("warning", message.getType());
        assertEquals("投递任务完成，本轮未成功发起聊天", message.getMessage());
    }

    @Test
    void confirmedChatsKeepSuccessStatusAndCount() {
        JobProgressMessage message = LiepinJobService.buildCompletionMessage(2);

        assertEquals("success", message.getType());
        assertEquals("投递任务完成，共发起2个聊天", message.getMessage());
    }

    @Test
    void completionMessageExplainsZeroChatRound() {
        JobProgressMessage message = LiepinJobService.buildCompletionMessage(0, Map.of(
                "scanned", 12,
                "salarySkipped", 9,
                "otherSkipped", 3
        ));

        assertEquals("warning", message.getType());
        assertEquals("投递任务完成，本轮未成功发起聊天，处理12个岗位，薪资跳过9个，其它筛选跳过3个",
                message.getMessage());
    }

    @Test
    void recentStatusKeepsNewestEightMessagesInOrder() {
        LiepinJobService.StatusHistory history = new LiepinJobService.StatusHistory(8);
        for (int i = 1; i <= 9; i++) {
            history.add(JobProgressMessage.info("liepin", "状态" + i));
        }

        List<JobProgressMessage> messages = history.snapshot();

        assertEquals(8, messages.size());
        assertEquals("状态2", messages.get(0).getMessage());
        assertEquals("状态9", messages.get(7).getMessage());
    }

    @Test
    void recentStatusCanBeClearedForNextRun() {
        LiepinJobService.StatusHistory history = new LiepinJobService.StatusHistory(8);
        history.add(JobProgressMessage.info("liepin", "上一轮"));

        history.clear();

        assertEquals(0, history.snapshot().size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void statusContainsRecentMessagesForInitialState() {
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        when(playwrightManager.isLoggedIn("liepin")).thenReturn(false);
        LiepinJobService service = new LiepinJobService(
                playwrightManager,
                mock(ConfigService.class),
                mock(ObjectProvider.class),
                mock(LiepinService.class)
        );

        Map<String, Object> status = service.getStatus();
        List<?> recentMessages = (List<?>) status.get("recentMessages");
        JobProgressMessage initialMessage = (JobProgressMessage) recentMessages.get(0);

        assertEquals(1, recentMessages.size());
        assertEquals("idle", initialMessage.getType());
        assertEquals("尚未启动投递任务", initialMessage.getMessage());
        assertEquals(0L, status.get("runId"));
        Map<?, ?> deliverySummary = (Map<?, ?>) status.get("deliverySummary");
        assertEquals(0, deliverySummary.get("scanned"));
        assertEquals(0, ((Map<?, ?>) deliverySummary.get("collection")).get("jobsFetched"));
        assertEquals(0, ((Map<?, ?>) status.get("aiSummary")).get("candidateCount"));
    }

    @Test
    void statusUsesLatestWorkerSnapshotsWhileRunIsActive() {
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        when(playwrightManager.isLoggedIn("liepin")).thenReturn(false);
        LiepinJobService service = new LiepinJobService(
                playwrightManager,
                mock(ConfigService.class),
                mock(ObjectProvider.class),
                mock(LiepinService.class)
        );
        Liepin worker = mock(Liepin.class);
        when(worker.getAiSummary()).thenReturn(Map.of(
                "candidateCount", 4,
                "screenCalls", 2,
                "messageCalls", 1,
                "avgLatencyMs", 125
        ));
        when(worker.getDeliverySummary()).thenReturn(Map.of(
                "scanned", 7,
                "salaryEligible", 4,
                "salarySkipped", 2,
                "otherSkipped", 1,
                "delivered", 1
        ));

        service.refreshRunStats(worker);

        Map<String, Object> status = service.getStatus();
        Map<?, ?> aiSummary = (Map<?, ?>) status.get("aiSummary");
        Map<?, ?> deliverySummary = (Map<?, ?>) status.get("deliverySummary");
        assertEquals(4, aiSummary.get("candidateCount"));
        assertEquals(2, aiSummary.get("screenCalls"));
        assertEquals(125, aiSummary.get("avgLatencyMs"));
        assertEquals(7, deliverySummary.get("scanned"));
        assertEquals(1, deliverySummary.get("delivered"));
    }

    @Test
    void restingTaskCancelsScheduledResumeImmediately() throws Exception {
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        when(playwrightManager.isLoggedIn("liepin")).thenReturn(false);
        LiepinJobService service = new LiepinJobService(
                playwrightManager,
                mock(ConfigService.class),
                mock(ObjectProvider.class),
                mock(LiepinService.class)
        );
        java.util.concurrent.ScheduledFuture<?> pending = mock(java.util.concurrent.ScheduledFuture.class);
        setField(service, "isRunning", true);
        setField(service, "taskState", LiepinJobService.TaskState.RESTING);
        setField(service, "scheduledResume", pending);

        service.stopDelivery();

        verify(pending).cancel(false);
        assertEquals(false, service.isRunning());
        assertEquals("CANCELLED", service.getStatus().get("taskState"));
    }

    @Test
    void riskSignalSchedulesTheConfiguredLongRestWindow() throws Exception {
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        when(playwrightManager.isLoggedIn("liepin")).thenReturn(false);
        ConfigService configService = mock(ConfigService.class);
        when(configService.getLiepinConfig()).thenReturn(new com.jobradar.worker.liepin.LiepinConfig());
        LiepinJobService service = new LiepinJobService(
                playwrightManager,
                configService,
                mock(ObjectProvider.class),
                mock(LiepinService.class)
        );
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        doReturn(scheduled).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        setField(service, "deliveryResumeScheduler", scheduler);
        setField(service, "isRunning", true);
        LiepinAccountPacing pacing = (LiepinAccountPacing) readField(service, "accountPacing");
        pacing.recordRiskSignal("captcha", true);

        List<JobProgressMessage> messages = new ArrayList<>();
        Method scheduleRest = LiepinJobService.class.getDeclaredMethod(
                "scheduleRest", Liepin.RestRequest.class, java.util.function.Consumer.class);
        scheduleRest.setAccessible(true);
        scheduleRest.invoke(service,
                new Liepin.RestRequest("risk_signal", "captcha", "Java", 2),
                (java.util.function.Consumer<JobProgressMessage>) messages::add);

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        verify(scheduler).schedule(any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));
        long delay = delayCaptor.getValue();
        assertTrue(delay >= 30 * 60 * 1000L);
        assertTrue(delay <= 45 * 60 * 1000L);
        assertEquals("RESTING", service.getStatus().get("taskState"));
        assertEquals("risk_signal", service.getStatus().get("restReason"));
        assertEquals("captcha", service.getStatus().get("riskReason"));
        assertTrue(messages.get(0).getMessage().contains("长休息"));
    }

    @Test
    void batchRetryContinuesOnlyWhenPendingCountDrops() {
        assertEquals(LiepinJobService.BatchRetryAction.CONTINUE,
                LiepinJobService.decideBatchRetry(3, 2, Liepin.ExecutionOutcome.COMPLETED));
        assertEquals(LiepinJobService.BatchRetryAction.COMPLETE,
                LiepinJobService.decideBatchRetry(2, 0, Liepin.ExecutionOutcome.COMPLETED));
        assertEquals(LiepinJobService.BatchRetryAction.PAUSE,
                LiepinJobService.decideBatchRetry(2, 2, Liepin.ExecutionOutcome.COMPLETED));
        assertEquals(LiepinJobService.BatchRetryAction.PAUSE,
                LiepinJobService.decideBatchRetry(2, 3, Liepin.ExecutionOutcome.COMPLETED));
    }

    @Test
    void batchRetryPausesAfterWorkerPauseOrRateLimit() {
        assertEquals(LiepinJobService.BatchRetryAction.PAUSE,
                LiepinJobService.decideBatchRetry(3, 1, Liepin.ExecutionOutcome.USER_PAUSED));
        assertEquals(LiepinJobService.BatchRetryAction.PAUSE,
                LiepinJobService.decideBatchRetry(3, 1, Liepin.ExecutionOutcome.RATE_LIMITED));
        assertEquals(LiepinJobService.BatchRetryAction.PAUSE,
                LiepinJobService.decideBatchRetry(3, 1, Liepin.ExecutionOutcome.RETRY_REQUIRED));
    }

    @Test
    void searchReadFailureIsReportedAsRetryablePause() {
        JobProgressMessage message = LiepinJobService.buildSearchRetryMessage(1,
                "猎聘浏览器搜索响应未捕获: Timeout 12000ms exceeded");

        assertEquals("warning", message.getType());
        assertTrue(message.getMessage().contains("等待重试"));
        assertTrue(message.getMessage().contains("Timeout 12000ms exceeded"));
    }

    @Test
    void batchRetryCompletionAndPauseMessagesExposeTheRemainingWork() {
        assertEquals("success", LiepinJobService.buildBatchRetryCompletionMessage(2, 2).getType());
        assertEquals("warning", LiepinJobService.buildBatchRetryPausedMessage(1, 2, "本轮待重试数量没有下降").getType());
        org.junit.jupiter.api.Assertions.assertTrue(
                LiepinJobService.buildBatchRetryPausedMessage(1, 2, "本轮待重试数量没有下降")
                        .getMessage().contains("剩余2个"));
    }

    @Test
    void suspendedBatchRetryMessagesUseTheQualifiedFailureScope() {
        assertEquals("success", LiepinJobService.buildSuspendedBatchRetryCompletionMessage(2, 2).getType());
        assertEquals("筛选后暂缓岗位批量重试完成，共发起2个聊天，执行2轮",
                LiepinJobService.buildSuspendedBatchRetryCompletionMessage(2, 2).getMessage());
        assertEquals("warning", LiepinJobService.buildSuspendedBatchRetryPausedMessage(1, 3, "本轮待处理数量没有下降").getType());
        org.junit.jupiter.api.Assertions.assertTrue(
                LiepinJobService.buildSuspendedBatchRetryPausedMessage(1, 3, "本轮待处理数量没有下降")
                        .getMessage().contains("剩余3个"));
    }

    @Test
    void statusKeepsNormalAndSuspendedTaskSlotsSeparate() throws Exception {
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        when(playwrightManager.isLoggedIn("liepin")).thenReturn(false);
        LiepinJobService service = new LiepinJobService(
                playwrightManager,
                mock(ConfigService.class),
                mock(ObjectProvider.class),
                mock(LiepinService.class)
        );

        setField(service, "isRunning", true);
        setField(service, "runId", 11L);
        setField(service, "taskState", LiepinJobService.TaskState.RUNNING);

        Object suspended = readField(service, "suspendedRetry");
        setField(suspended, "isRunning", true);
        setField(suspended, "runId", 22L);
        setField(suspended, "taskState", LiepinJobService.TaskState.PAUSED);

        Map<String, Object> status = service.getStatus();

        assertEquals(11L, status.get("runId"));
        assertEquals(11L, ((Map<?, ?>) status.get("normalTask")).get("runId"));
        assertEquals(22L, ((Map<?, ?>) status.get("suspendedRetryTask")).get("runId"));
        assertEquals(true, status.get("isAnyRunning"));
        assertEquals("PAUSED", ((Map<?, ?>) status.get("suspendedRetryTask")).get("taskState"));
    }

    @Test
    void confirmationRoutesToTheMatchingTaskSlot() throws Exception {
        LiepinJobService service = new LiepinJobService(
                mock(PlaywrightManager.class),
                mock(ConfigService.class),
                mock(ObjectProvider.class),
                mock(LiepinService.class)
        );
        Liepin.GreetingRequest normalRequest = new Liepin.GreetingRequest(
                101L, "普通公司", "普通岗位", "10K", "jd", "message", true);
        Liepin.GreetingRequest suspendedRequest = new Liepin.GreetingRequest(
                202L, "暂缓公司", "暂缓岗位", "10K", "jd", "message", true);
        CompletableFuture<Liepin.GreetingAction> normalDecision = new CompletableFuture<>();
        CompletableFuture<Liepin.GreetingAction> suspendedDecision = new CompletableFuture<>();
        setField(service, "pendingGreeting", normalRequest);
        setField(service, "pendingDecision", normalDecision);
        Object suspended = readField(service, "suspendedRetry");
        setField(suspended, "pendingGreeting", suspendedRequest);
        setField(suspended, "pendingDecision", suspendedDecision);

        assertEquals(true, service.confirmGreeting(202L, "ai").accepted());
        assertEquals(Liepin.GreetingAction.SEND_AI, suspendedDecision.getNow(null));
        assertEquals(true, service.confirmGreeting(101L, "ai").accepted());
        assertEquals(Liepin.GreetingAction.SEND_AI, normalDecision.getNow(null));
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        if (value instanceof Long longValue) {
            field.setLong(target, longValue);
        } else if (value instanceof Boolean booleanValue) {
            field.setBoolean(target, booleanValue);
        } else {
            field.set(target, value);
        }
    }
}
