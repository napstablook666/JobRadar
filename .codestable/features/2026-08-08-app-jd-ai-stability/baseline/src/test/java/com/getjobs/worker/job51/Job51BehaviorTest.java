package com.getjobs.worker.job51;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void aiResponseClassificationSkipsFalseAndEmptyText() {
        assertFalse(Job51.isUsableAiGreeting(Job51.classifyAiResponse("false")));
        assertFalse(Job51.isUsableAiGreeting(Job51.classifyAiResponse("   ")));
        assertFalse(Job51.isUsableAiGreeting(Job51.classifyAiResponse(null)));
    }

    @Test
    void aiResponseClassificationRemovesMarkdownFence() {
        Job51.AiGreetingResult result = Job51.classifyAiResponse("```text\n您好，关注到贵司岗位，希望有机会交流。\n```");
        assertTrue(Job51.isUsableAiGreeting(result));
        assertEquals("您好，关注到贵司岗位，希望有机会交流。", result.message());
    }

    @Test
    void aiConfigValuesAreClampedToApprovedBounds() {
        Job51Config config = new Job51Config();
        config.setMaxPerRun(99);
        config.setMinDelaySeconds(1);
        config.setMaxDelaySeconds(2);

        assertEquals(Job51Config.MAX_AI_PER_RUN, config.effectiveMaxPerRun());
        assertEquals(Job51Config.MIN_DELAY_SECONDS, config.effectiveMinDelaySeconds());
        assertEquals(Job51Config.MIN_DELAY_SECONDS, config.effectiveMaxDelaySeconds());
    }

    @Test
    void aiMaxDelayNeverFallsBelowMinDelay() {
        Job51Config config = new Job51Config();
        config.setMinDelaySeconds(120);
        config.setMaxDelaySeconds(10);

        assertEquals(120, config.effectiveMinDelaySeconds());
        assertEquals(120, config.effectiveMaxDelaySeconds());
    }

}
