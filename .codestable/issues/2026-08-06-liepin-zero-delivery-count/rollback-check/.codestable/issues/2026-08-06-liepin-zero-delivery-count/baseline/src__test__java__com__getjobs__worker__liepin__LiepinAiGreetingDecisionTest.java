package com.getjobs.worker.liepin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinAiGreetingDecisionTest {

    @Test
    void falseResponseIsNotUsableForSending() {
        Liepin.AiGreetingResult result = Liepin.classifyAiResponse("  FALSE  ");

        assertTrue(result.rejected());
        assertNull(result.message());
        assertFalse(Liepin.isUsableAiGreeting(result));
    }

    @Test
    void normalResponseRemainsAvailableForConfirmation() {
        Liepin.AiGreetingResult result = Liepin.classifyAiResponse(" 您好，我对这个岗位很感兴趣。 ");

        assertFalse(result.rejected());
        assertEquals("您好，我对这个岗位很感兴趣。", result.message());
    }

    @Test
    void blankResponseIsNotUsableForSending() {
        Liepin.AiGreetingResult result = Liepin.classifyAiResponse("  ");

        assertFalse(result.rejected());
        assertNull(result.message());
        assertFalse(Liepin.isUsableAiGreeting(result));
    }

    @Test
    void fencedResponseIsNormalized() {
        Liepin.AiGreetingResult result = Liepin.classifyAiResponse("```text\n您好，我对这个岗位很感兴趣。\n```");

        assertFalse(result.rejected());
        assertEquals("您好，我对这个岗位很感兴趣。", result.message());
        assertTrue(Liepin.isUsableAiGreeting(result));
    }

    @Test
    void relevantMedicalDeviceJobIsAllowed() {
        assertTrue(Liepin.isRelevantJob("医疗设备应用工程师", "负责放疗设备临床应用、安装调试和技术支持。"));
    }

    @Test
    void unrelatedJobIsRejectedEvenWhenJdExists() {
        assertFalse(Liepin.isRelevantJob("建筑工程师", "负责建筑项目施工管理和现场协调。"));
    }

    @Test
    void salesAndGenericManufacturingTitlesAreRejected() {
        assertFalse(Liepin.isRelevantJob("医疗器械销售经理", "负责医疗器械客户开发和销售。"));
        assertFalse(Liepin.isRelevantJob("机械制造工程师", "负责生产线和车间工艺。"));
    }

    @Test
    void missingJobDataIsRejected() {
        assertFalse(Liepin.isRelevantJob("医疗设备工程师", null));
    }
}
