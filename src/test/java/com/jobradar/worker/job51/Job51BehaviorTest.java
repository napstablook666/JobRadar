package com.jobradar.worker.job51;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import java.util.List;

import com.jobradar.worker.liepin.LiepinAiBatchAssessment;
class Job51BehaviorTest {

    @Test
    void pageNumberMatchingIsExact() {
        assertTrue(Job51.pageNumberMatches(" 2 ", 2));
        assertFalse(Job51.pageNumberMatches("12", 2));
    }

    @Test
    void confirmedCountNeverExceedsSelectedJobs() {
        assertEquals(3, Job51.confirmedInfoCount(5, 3));
        assertEquals(0, Job51.confirmedInfoCount(-1, 3));
    }

    @Test
    void closedTargetErrorsAreRecognized() {
        assertTrue(Job51.isTargetClosedFailure(
                new IllegalStateException("Target page, context or browser has been closed")));
        assertFalse(Job51.isTargetClosedFailure(new IllegalStateException("network timeout")));
    }

    @Test
    void deliveryCountParsesNumericAndGenericSuccessFeedback() {
        assertEquals(3, Job51.parseDeliveryCount("投递成功 3 个，未投递 1 个", "投递成功"));
        assertEquals(1, Job51.parseDeliveryCount("投递成功 3 个，未投递 1 个", "未投递"));
        assertEquals(null, Job51.parseDeliveryCount("投递成功！", "投递成功"));
    }

    @Test
    void searchFallbackAcceptsPlatformConfirmButtonText() {
        assertTrue(java.util.Arrays.asList(Job51Locators.APPLY_CONFIRM_TEXTS).contains("确定"));
        assertTrue(java.util.Arrays.asList(Job51Locators.APPLY_CONFIRM_TEXTS).contains("确 定"));
    }
    @Test
    void cardJobIdTakesPrecedenceOverPageSnapshotFallback() {
        assertEquals(9001L, Job51.resolveJobIdForCard(9001L, 0, List.of(1001L)));
    }
    @Test
    void checkboxStateTextIsNotTreatedAsAlreadyApplied() {
        assertFalse(Job51.hasAppliedJobMarker("岗位名称 公司名称"));
        assertTrue(Job51.hasAppliedJobMarker("岗位名称 已申请"));
        assertTrue(Job51.hasAppliedJobMarker("岗位名称 已投递"));
    }

    @Test
    void pageSnapshotJobIdFillsCardsWithoutDomJobId() {
        assertEquals(1002L, Job51.resolveJobIdForCard(null, 1, List.of(1001L, 1002L)));
    }

    @Test
    void missingPageSnapshotJobIdRemainsSkippable() {
        assertEquals(null, Job51.resolveJobIdForCard(null, 2, List.of(1001L, 1002L)));
        assertEquals(null, Job51.resolveJobIdForCard(null, 0, null));
    }


    @Test
    void aiResponseClassificationSkipsFalseAndEmptyText() {
        assertFalse(Job51.isUsableAiGreeting(Job51.classifyAiResponse("false")));
        assertFalse(Job51.isUsableAiGreeting(Job51.classifyAiResponse("   ")));
        assertFalse(Job51.isUsableAiGreeting(Job51.classifyAiResponse(null)));
    }

    @Test
    void falseResponseIsRetriedAsTemplateViolation() {
        assertTrue(Job51.shouldRetryAiReason("ai_response_false"));
        assertFalse(Job51.shouldRetryAiReason("ai_prompt_missing"));
    }

    @Test
    void aiResponseClassificationRemovesMarkdownFence() {
        Job51.AiGreetingResult result = Job51.classifyAiResponse("```text\n您好，关注到贵司岗位，希望有机会交流。\n```");
        assertTrue(Job51.isUsableAiGreeting(result));
        assertEquals("您好，关注到贵司岗位，希望有机会交流。", result.message());
    }

    @Test
    void extractsJobIdsFromTopLevelAndNestedSearchItems() {
        assertEquals(
                java.util.List.of(1001L, 1002L, 1003L),
                Job51.extractJobIdsFromJsonText(
                        "{\"data\":{\"items\":[{\"job\":{\"jobId\":\"1001\"}},{\"jobInfo\":{\"job_id\":1002}}]},"
                                + "\"list\":[{\"jobId\":1003},{\"jobId\":1001}]}"));
    }

    @Test
    void aiConfigValuesAreClampedToApprovedBounds() {
        Job51Config config = new Job51Config();
        config.setMaxPerRun(99);
        config.setMinDelaySeconds(1);
        config.setMaxDelaySeconds(2);

        assertEquals(99, config.effectiveMaxPerRun());
        assertEquals(Job51Config.MIN_DELAY_SECONDS, config.effectiveMinDelaySeconds());
        assertEquals(Job51Config.MIN_DELAY_SECONDS, config.effectiveMaxDelaySeconds());
    }

    @Test
    void maxPerRunZeroMeansUnlimitedAndRestRangeIsConfigurable() {
        Job51Config config = new Job51Config();
        config.setMaxPerRun(0);
        config.setStopAfterMaxPerRun(false);
        config.setMaxPerRunRestMinSeconds(45);
        config.setMaxPerRunRestMaxSeconds(90);

        assertEquals(0, config.effectiveMaxPerRun());
        assertFalse(config.isStopAfterMaxPerRun());
        assertEquals(45, config.effectiveMaxPerRunRestMinSeconds());
        assertEquals(90, config.effectiveMaxPerRunRestMaxSeconds());
    }

    @Test
    void aiMaxDelayNeverFallsBelowMinDelay() {
        Job51Config config = new Job51Config();
        config.setMinDelaySeconds(120);
        config.setMaxDelaySeconds(10);

        assertEquals(120, config.effectiveMinDelaySeconds());
        assertEquals(120, config.effectiveMaxDelaySeconds());
    }

    @Test
    void restConfigClampsBoundsAndKeepsEachRangeOrdered() {
        Job51Config config = new Job51Config();
        config.setRestStage1MinSeconds(1);
        config.setRestStage1MaxSeconds(2);
        config.setRestStage2MinSeconds(500);
        config.setRestStage2MaxSeconds(400);
        config.setRestStage3MinSeconds(90000);
        config.setRestStage3MaxSeconds(90001);
        config.setRestMaxConsecutive(99);

        assertEquals(Job51Config.MIN_REST_SECONDS, config.effectiveRestStage1MinSeconds());
        assertEquals(Job51Config.MIN_REST_SECONDS, config.effectiveRestStage1MaxSeconds());
        assertEquals(500, config.effectiveRestStage2MinSeconds());
        assertEquals(500, config.effectiveRestStage2MaxSeconds());
        assertEquals(Job51Config.MAX_REST_SECONDS, config.effectiveRestStage3MinSeconds());
        assertEquals(Job51Config.MAX_REST_SECONDS, config.effectiveRestStage3MaxSeconds());
        assertEquals(Job51Config.MAX_REST_CONSECUTIVE, config.effectiveRestMaxConsecutive());
    }

    @Test
    void loginRedirectsAreRecognizedBeforeDetailActionLookup() {
        assertTrue(Job51.isLoginPageUrl("https://login.51job.com/login.php?url=%2Fpc%2Fjobdetail"));
        assertTrue(Job51.isLoginPageUrl("https://we.51job.com/login"));
        assertFalse(Job51.isLoginPageUrl("https://we.51job.com/pc/jobdetail?jobId=1001"));
    }

    @Test
    void externalApplicationRoutesAreSkippedBeforePlatformApply() {
        assertTrue(Job51.isExternalApplicationRoute("https://campus.51job.com/example/"));
        assertFalse(Job51.isExternalApplicationRoute("https://jobs.51job.com/wuhan/1001.html"));
        assertFalse(Job51.isExternalApplicationRoute("https://we.51job.com/pc/jobdetail?jobId=1001"));
    }

    @Test
    void classifiesRetryableAiFailuresByTransportAndStatus() {
        assertEquals("ai_tls_error", Job51.classifyAiFailure(
                new SSLHandshakeException("Remote host terminated the handshake")));
        assertEquals("ai_timeout", Job51.classifyAiFailure(
                new HttpTimeoutException("request timed out")));
        assertEquals("ai_upstream_auth_error", Job51.classifyAiFailure(
                new com.jobradar.application.service.AiService.AiRequestException(424, "https://api.example.test", "auth failed")));
        assertEquals("ai_rate_limited", Job51.classifyAiFailure(
                new com.jobradar.application.service.AiService.AiRequestException(429, "https://api.example.test", "rate limited")));
        assertEquals("ai_request_rejected", Job51.classifyAiFailure(
                new com.jobradar.application.service.AiService.AiRequestException(401, "https://api.example.test", "denied")));
        assertEquals("ai_request_error", Job51.classifyAiFailure(
                new com.jobradar.application.service.AiService.AiRequestException(500, "https://api.example.test", "server error")));
        assertEquals("ai_upstream_auth_error", Job51.classifyAiFailure(
                new RuntimeException("HTTP 424 Upstream authentication failed")));
    }

    @Test
    void retriesOnlyApprovedAiFailures() {
        assertTrue(Job51.isRetryableAiFailure(new SSLHandshakeException("handshake_failure")));
        assertTrue(Job51.isRetryableAiFailure(new HttpTimeoutException("timed out")));
        assertTrue(Job51.isRetryableAiFailure(
                new com.jobradar.application.service.AiService.AiRequestException(424, "https://api.example.test", "auth failed")));
        assertTrue(Job51.isRetryableAiFailure(
                new com.jobradar.application.service.AiService.AiRequestException(429, "https://api.example.test", "rate limited")));
        assertTrue(Job51.isRetryableAiFailure(
                new com.jobradar.application.service.AiService.AiRequestException(503, "https://api.example.test", "unavailable")));
        assertTrue(Job51.isRetryableAiFailure(new RuntimeException("HTTP 502 Bad Gateway")));
        assertFalse(Job51.isRetryableAiFailure(
                new com.jobradar.application.service.AiService.AiRequestException(400, "https://api.example.test", "bad request")));
        assertFalse(Job51.isRetryableAiFailure(
                new com.jobradar.application.service.AiService.AiRequestException(401, "https://api.example.test", "denied")));
        assertFalse(Job51.isRetryableAiFailure(
                new com.jobradar.application.service.AiService.AiRequestException(422, "https://api.example.test", "invalid")));
    }

    @Test
    void usesApprovedExponentialRetryDelays() {
        assertEquals(1000L, Job51.aiRetryDelayMillis(1));
        assertEquals(2000L, Job51.aiRetryDelayMillis(2));
        assertEquals(0L, Job51.aiRetryDelayMillis(3));
    }

    @Test
    void structuredJdAnalysisRequiresPassScoreAndNoHardMismatch() {
        LiepinAiBatchAssessment.Item pass = new LiepinAiBatchAssessment.Item(
                "1001", 70, "PASS", List.of("DIRECTION_MATCH"), "方向匹配");
        LiepinAiBatchAssessment.Item lowScore = new LiepinAiBatchAssessment.Item(
                "1001", 69, "PASS", List.of("DIRECTION_MATCH"), "分数不足");
        LiepinAiBatchAssessment.Item review = new LiepinAiBatchAssessment.Item(
                "1001", 90, "REVIEW", List.of("INSUFFICIENT_INFO"), "信息不足");
        LiepinAiBatchAssessment.Item mismatch = new LiepinAiBatchAssessment.Item(
                "1001", 95, "PASS", List.of("HARD_MISMATCH"), "HARD_MISMATCH");

        assertTrue(Job51.passesAiScreening(pass, 70));
        assertFalse(Job51.passesAiScreening(lowScore, 70));
        assertFalse(Job51.passesAiScreening(review, 70));
        assertFalse(Job51.passesAiScreening(mismatch, 70));
    }

    @Test
    void aiJdAnalysisScoreIsClampedToSupportedBounds() {
        Job51Config config = new Job51Config();
        config.setAiMinScore(-1);
        assertEquals(0, config.effectiveAiMinScore());
        config.setAiMinScore(101);
        assertEquals(100, config.effectiveAiMinScore());
    }
}
