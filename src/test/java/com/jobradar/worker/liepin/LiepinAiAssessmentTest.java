package com.jobradar.worker.liepin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinAiAssessmentTest {

    @Test
    void parsesCompleteJsonAssessment() {
        LiepinAiAssessment assessment = LiepinAiAssessment.parse(
                "{\"score\":85,\"reason\":\"方向和经验匹配\",\"message\":\"您好，我对这个岗位很感兴趣。\"}");

        assertEquals(85, assessment.score());
        assertEquals("方向和经验匹配", assessment.reason());
        assertEquals("您好，我对这个岗位很感兴趣。", assessment.message());
    }

    @Test
    void acceptsJsonInsideMarkdownFence() {
        LiepinAiAssessment assessment = LiepinAiAssessment.parse(
                "```json\n{\"score\":70,\"reason\":\"基本匹配\",\"message\":\"您好，期待交流。\"}\n```");

        assertEquals(70, assessment.score());
        assertTrue(assessment.passes(70));
    }

    @Test
    void rejectsIncompleteOrOutOfRangeResponse() {
        assertNull(LiepinAiAssessment.parse("{\"score\":101,\"reason\":\"太高\",\"message\":\"您好\"}"));
        assertNull(LiepinAiAssessment.parse("{\"score\":80,\"reason\":\"缺少话术\"}"));
        assertNull(LiepinAiAssessment.parse("{\"score\":80,\"reason\":\"有余\",\"message\":\"您好\"} extra"));
    }

    @Test
    void autoScreeningRequiresSwitchAndConfiguredThreshold() {
        LiepinConfig config = new LiepinConfig();
        LiepinAiAssessment assessment = new LiepinAiAssessment(70, "达到阈值", "您好");

        assertFalse(Liepin.passesAutoAiScreening(config, assessment));
        config.setAutoAiDelivery(true);
        assertTrue(Liepin.passesAutoAiScreening(config, assessment));
        config.setAiMinScore(71);
        assertFalse(Liepin.passesAutoAiScreening(config, assessment));
    }

    @Test
    void normalizesCachedJobDescriptionBeforeAiUse() {
        String cached = "首页\n职位描述\n负责放疗设备临床应用培训和客户现场支持。\n"
                + "任职要求\n本科及以上，医学物理相关专业。\n公司信息\n公司成立于2010年\n相关职位";

        String normalized = Liepin.normalizedCachedJobDescription(cached);

        assertTrue(normalized.contains("临床应用培训"));
        assertTrue(normalized.contains("任职要求"));
        assertFalse(normalized.contains("公司成立"));
        assertFalse(normalized.contains("相关职位"));
    }

    @Test
    void boundsLongCachedJobDescriptionBeforeAiUse() {
        String normalized = Liepin.normalizedCachedJobDescription("职位描述\n" + "岗位职责内容".repeat(3_000));

        assertTrue(normalized.length() <= com.jobradar.worker.utils.JobDescriptionExtractor.MAX_TEXT_LENGTH);
    }
}
