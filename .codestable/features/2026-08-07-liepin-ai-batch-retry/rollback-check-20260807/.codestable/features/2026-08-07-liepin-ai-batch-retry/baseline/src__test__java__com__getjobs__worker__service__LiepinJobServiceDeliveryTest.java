package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.manager.PlaywrightManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                mock(ObjectProvider.class)
        );

        Map<String, Object> status = service.getStatus();
        List<?> recentMessages = (List<?>) status.get("recentMessages");
        JobProgressMessage initialMessage = (JobProgressMessage) recentMessages.get(0);

        assertEquals(1, recentMessages.size());
        assertEquals("idle", initialMessage.getType());
        assertEquals("尚未启动投递任务", initialMessage.getMessage());
        assertEquals(0L, status.get("runId"));
        assertEquals(0, ((Map<?, ?>) status.get("deliverySummary")).get("scanned"));
        assertEquals(0, ((Map<?, ?>) status.get("aiSummary")).get("candidateCount"));
    }

    @Test
    void statusUsesLatestWorkerSnapshotsWhileRunIsActive() {
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        when(playwrightManager.isLoggedIn("liepin")).thenReturn(false);
        LiepinJobService service = new LiepinJobService(
                playwrightManager,
                mock(ConfigService.class),
                mock(ObjectProvider.class)
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
}
