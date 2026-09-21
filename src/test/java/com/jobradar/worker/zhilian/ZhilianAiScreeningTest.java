package com.jobradar.worker.zhilian;

import com.jobradar.application.entity.ZhilianJobDataEntity;
import com.jobradar.worker.liepin.LiepinAiBatchAssessment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhilianAiScreeningTest {

    @Test
    void aiScreeningIsOnByDefaultAndScoreIsBounded() {
        ZhilianConfig config = new ZhilianConfig();

        assertTrue(config.isAiScreeningEnabled());
        config.setAiMinScore(101);
        assertTrue(config.effectiveAiMinScore() == 100);
    }

    @Test
    void onlyPassingAssessmentWithoutHardMismatchIsAccepted() {
        LiepinAiBatchAssessment.Item pass = new LiepinAiBatchAssessment.Item(
                "1", 70, "PASS", List.of("DIRECTION_MATCH"), "方向匹配");
        LiepinAiBatchAssessment.Item hardMismatch = new LiepinAiBatchAssessment.Item(
                "2", 90, "PASS", List.of("HARD_MISMATCH"), "不匹配");

        assertTrue(ZhiLian.isAccepted(pass, 70));
        assertFalse(ZhiLian.isAccepted(hardMismatch, 70));
    }

    @Test
    void reusesOnlyUsableCachedDescriptionAndSkipsFilteredJob() {
        ZhilianJobDataEntity cached = new ZhilianJobDataEntity();
        cached.setJobDescription("职位描述\n负责放疗设备临床应用培训和客户现场支持。\n任职要求\n本科及以上。");
        ZhilianJobDataEntity filtered = new ZhilianJobDataEntity();
        filtered.setDeliveryStatus("已过滤");
        ZhilianJobDataEntity verificationWall = new ZhilianJobDataEntity();
        verificationWall.setJobDescription("安全验证：请完成验证后继续浏览岗位详情");

        assertTrue(ZhiLian.usableCachedJobDescription(cached).contains("临床应用培训"));
        assertFalse(ZhiLian.shouldSkipPersistedJob(cached));
        assertTrue(ZhiLian.shouldSkipPersistedJob(filtered));
        assertTrue(ZhiLian.usableCachedJobDescription(verificationWall) == null);
    }

    @Test
    void persistsOnlyExplicitAiRejections() {
        LiepinAiBatchAssessment.Item lowScore = new LiepinAiBatchAssessment.Item(
                "1", 69, "PASS", List.of("DIRECTION_MATCH"), "分数不足");
        LiepinAiBatchAssessment.Item hardMismatch = new LiepinAiBatchAssessment.Item(
                "2", 90, "PASS", List.of("HARD_MISMATCH"), "不匹配");
        LiepinAiBatchAssessment.Item review = new LiepinAiBatchAssessment.Item(
                "3", 95, "REVIEW", List.of("INSUFFICIENT_INFO"), "信息不足");
        LiepinAiBatchAssessment.Item skipWithoutHardMismatch = new LiepinAiBatchAssessment.Item(
                "4", 20, "SKIP", List.of("INSUFFICIENT_INFO"), "方向不符");

        assertFalse(ZhiLian.shouldPersistAiRejection(null, 70));
        assertTrue(ZhiLian.shouldPersistAiRejection(lowScore, 70));
        assertTrue(ZhiLian.shouldPersistAiRejection(hardMismatch, 70));
        assertFalse(ZhiLian.shouldPersistAiRejection(review, 70));
        assertFalse(ZhiLian.shouldPersistAiRejection(skipWithoutHardMismatch, 70));
    }

    @Test
    void migratesOnlyNullEnableAiScreening() {
        assertTrue(com.jobradar.application.service.ZhilianService.shouldMigrateAiScreening(null, null));
        assertTrue(com.jobradar.application.service.ZhilianService.shouldMigrateAiScreening(null, 0));
        assertFalse(com.jobradar.application.service.ZhilianService.shouldMigrateAiScreening(0, 0));
        assertFalse(com.jobradar.application.service.ZhilianService.shouldMigrateAiScreening(0, 1));
        assertFalse(com.jobradar.application.service.ZhilianService.shouldMigrateAiScreening(1, 1));
    }
}
