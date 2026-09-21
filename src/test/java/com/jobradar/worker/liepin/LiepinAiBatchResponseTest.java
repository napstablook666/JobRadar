package com.jobradar.worker.liepin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinAiBatchResponseTest {
    @Test
    void parsesStrictScreeningItems() {
        LiepinAiBatchAssessment.Result result = LiepinAiBatchAssessment.parse(
                "{\"items\":[{\"jobId\":\"101\",\"score\":85,\"decision\":\"PASS\","
                        + "\"reasonCodes\":[\"SKILL_MATCH\"],\"reason\":\"技能方向匹配\"}]}"
        );

        assertEquals("101", result.items().get(0).jobId());
        assertEquals(85, result.items().get(0).score());
        assertEquals("PASS", result.items().get(0).decision());
    }

    @Test
    void rejectsUnknownDecision() {
        assertNull(LiepinAiBatchAssessment.parse(
                "{\"items\":[{\"jobId\":\"101\",\"score\":85,\"decision\":\"YES\","
                        + "\"reasonCodes\":[],\"reason\":\"匹配\"}]}"
        ));
    }

    @Test
    void rejectsUnknownReasonCodeAndOverlongReason() {
        String unknownCode = """
                {"items":[{"jobId":"101","score":85,"decision":"PASS","reasonCodes":["UNKNOWN"],"reason":"匹配"}]}
                """;
        assertNull(LiepinAiBatchAssessment.parse(unknownCode));

        String longReason = """
                {"items":[{"jobId":"101","score":85,"decision":"PASS","reasonCodes":[],"reason":"这是一个明确超过四十个字符的理由文本，用于验证批量评分结果的严格长度校验是否生效并拒绝超长内容"}]}
                """;
        assertNull(LiepinAiBatchAssessment.parse(longReason));
    }

    @Test
    void parsesAndValidatesMessages() {
        LiepinAiBatchMessage.Result result = LiepinAiBatchMessage.parse(
                "{\"items\":[{\"jobId\":\"101\",\"message\":\"您好，我对该岗位很感兴趣。\"}]}"
        );

        assertEquals("您好，我对该岗位很感兴趣。", result.items().get(0).message());
        assertNull(LiepinAiBatchMessage.parse(
                "{\"items\":[{\"jobId\":\"101\",\"message\":\"```bad```\"}]}"
        ));
    }

    @Test
    void batchAutoDeliveryAcceptsQualifiedPassAndReviewButExcludesHardMismatch() {
        LiepinConfig config = new LiepinConfig();

        assertTrue(Liepin.isAiDeliveryAccepted(config,
                new LiepinAiBatchAssessment.Item("1", 70, "PASS", java.util.List.of(), "匹配")));
        assertTrue(Liepin.isAiDeliveryAccepted(config,
                new LiepinAiBatchAssessment.Item("2", 60, "REVIEW", java.util.List.of(), "待确认")));
        assertFalse(Liepin.isAiDeliveryAccepted(config,
                new LiepinAiBatchAssessment.Item("3", 95, "PASS", java.util.List.of("HARD_MISMATCH"), "不匹配")));
    }
}
