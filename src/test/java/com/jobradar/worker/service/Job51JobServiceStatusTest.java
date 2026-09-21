package com.jobradar.worker.service;

import com.jobradar.application.service.ConfigService;
import com.jobradar.worker.dto.JobProgressMessage;
import com.jobradar.worker.manager.PlaywrightManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Job51JobServiceStatusTest {

    @Test
    void recentStatusKeepsNewestFiveMessagesInOrder() {
        Job51JobService.StatusHistory history = new Job51JobService.StatusHistory(5);
        for (int i = 1; i <= 6; i++) {
            history.add(JobProgressMessage.info("51job", "状态" + i));
        }

        List<JobProgressMessage> messages = history.snapshot();

        assertEquals(5, messages.size());
        assertEquals("状态2", messages.get(0).getMessage());
        assertEquals("状态6", messages.get(4).getMessage());
    }

    @Test
    void recentStatusCanBeClearedForNextRun() {
        Job51JobService.StatusHistory history = new Job51JobService.StatusHistory(5);
        history.add(JobProgressMessage.info("51job", "上一轮"));

        history.clear();

        assertEquals(0, history.snapshot().size());
    }

    @Test
    void statusContainsInitialRecentMessage() {
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        when(playwrightManager.isLoggedIn("51job")).thenReturn(false);
        Job51JobService service = new Job51JobService(
                playwrightManager,
                mock(ObjectProvider.class),
                mock(ConfigService.class)
        );

        Map<String, Object> status = service.getStatus();
        List<?> recentMessages = (List<?>) status.get("recentMessages");
        JobProgressMessage initialMessage = (JobProgressMessage) recentMessages.get(0);

        assertEquals(1, recentMessages.size());
        assertEquals("idle", initialMessage.getType());
        assertEquals("尚未启动投递任务", initialMessage.getMessage());
        assertEquals("IDLE", status.get("taskState"));
        assertEquals(null, status.get("restReason"));
        assertEquals(0L, status.get("restUntil"));
        assertEquals(0, status.get("restAttempt"));
        assertEquals(null, status.get("resumeCursor"));
        Map<?, ?> deliverySummary = (Map<?, ?>) status.get("deliverySummary");
        assertEquals(0, deliverySummary.get("candidates"));
        assertEquals(0, deliverySummary.get("aiProcessed"));
        assertEquals(0, deliverySummary.get("greetingSent"));
        assertEquals(0, deliverySummary.get("aiDelivered"));
    }

    @Test
    void restingTaskCancelsScheduledResumeImmediately() throws Exception {
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        when(playwrightManager.isLoggedIn("51job")).thenReturn(false);
        Job51JobService service = new Job51JobService(
                playwrightManager,
                mock(ObjectProvider.class),
                mock(ConfigService.class)
        );
        ScheduledFuture<?> pending = mock(ScheduledFuture.class);
        setField(service, "isRunning", true);
        setField(service, "taskState", Job51JobService.TaskState.RESTING);
        setField(service, "scheduledResume", pending);

        service.stopDelivery();

        verify(pending).cancel(false);
        assertFalse(service.isRunning());
        assertEquals("CANCELLED", service.getStatus().get("taskState"));
    }

    @Test
    void restStagesEscalateAndStayAtThirdStage() {
        assertEquals(1, Job51JobService.restStageFor(1, 3));
        assertEquals(2, Job51JobService.restStageFor(2, 3));
        assertEquals(3, Job51JobService.restStageFor(3, 3));
        assertEquals(3, Job51JobService.restStageFor(8, 3));
    }

    @Test
    void nextMidnightUsesTheConfiguredTimezone() {
        long now = java.time.ZonedDateTime.of(2026, 8, 10, 23, 30, 0, 0,
                java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();

        long next = Job51JobService.nextLocalMidnightMillis(now, java.time.ZoneId.of("Asia/Shanghai"));

        assertEquals(java.time.ZonedDateTime.of(2026, 8, 11, 0, 0, 0, 0,
                java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(), next);
    }

    @Test
    void waitingTaskCanBeCancelledBeforeItUsesBrowser() throws Exception {
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        CountDownLatch waitingEntered = new CountDownLatch(1);
        when(playwrightManager.withPlaywrightAccessCancellable(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenAnswer(invocation -> {
            BooleanSupplier shouldStop = invocation.getArgument(0);
            waitingEntered.countDown();
            while (!shouldStop.getAsBoolean()) {
                Thread.yield();
            }
            return false;
        });

        Job51JobService service = new Job51JobService(
                playwrightManager,
                mock(ObjectProvider.class),
                mock(ConfigService.class)
        );
        List<JobProgressMessage> messages = new CopyOnWriteArrayList<>();

        assertTrue(service.startDeliveryAsync(messages::add));
        assertTrue(waitingEntered.await(1, TimeUnit.SECONDS));
        assertEquals("WAITING", service.getStatus().get("taskState"));

        service.stopDelivery();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (service.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertFalse(service.isRunning());
        assertEquals("CANCELLED", service.getStatus().get("taskState"));
        assertTrue(messages.stream().anyMatch(message ->
                "投递任务已取消，尚未开始投递".equals(message.getMessage())));
        verify(playwrightManager, never()).ensureJob51PageReady();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
