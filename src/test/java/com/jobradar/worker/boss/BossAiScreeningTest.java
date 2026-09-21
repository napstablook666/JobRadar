package com.jobradar.worker.boss;

import com.jobradar.worker.liepin.LiepinAiBatchAssessment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossAiScreeningTest {
    @Test
    void requiresPassScoreAndNoHardMismatch() {
        LiepinAiBatchAssessment.Item passing = new LiepinAiBatchAssessment.Item(
                "job", 70, "PASS", List.of("SKILL_MATCH"), "方向匹配");
        LiepinAiBatchAssessment.Item review = new LiepinAiBatchAssessment.Item(
                "job", 90, "REVIEW", List.of("SKILL_MATCH"), "信息不足");
        LiepinAiBatchAssessment.Item hardMismatch = new LiepinAiBatchAssessment.Item(
                "job", 95, "PASS", List.of("HARD_MISMATCH"), "硬性冲突");

        assertTrue(Boss.passesAiScreening(passing, 70));
        assertFalse(Boss.passesAiScreening(review, 70));
        assertFalse(Boss.passesAiScreening(hardMismatch, 70));
        assertFalse(Boss.passesAiScreening(passing, 71));
    }
}
