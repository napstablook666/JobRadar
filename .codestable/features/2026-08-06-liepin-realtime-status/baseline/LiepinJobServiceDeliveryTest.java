package com.getjobs.worker.service;

import com.getjobs.worker.dto.JobProgressMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
