package com.jobradar.worker.liepin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinMessageRequestTest {

    @Test
    void rewritesOnlyTheUniqueJsonMessageField() {
        LiepinMessageRequest.RewriteResult result = LiepinMessageRequest.rewrite(
                "{\"jobId\":123,\"message\":\"平台预设语\"}",
                "您好，我对这个岗位很感兴趣。"
        );

        assertTrue(result.rewritten());
        assertTrue(result.body().contains("您好，我对这个岗位很感兴趣。"));
        assertTrue(result.body().contains("123"));
    }

    @Test
    void refusesAmbiguousJsonBodies() {
        LiepinMessageRequest.RewriteResult result = LiepinMessageRequest.rewrite(
                "{\"message\":\"预设语\",\"content\":\"另一段文本\"}",
                "AI话术"
        );

        assertFalse(result.rewritten());
        assertTrue(result.body().contains("预设语"));
    }

    @Test
    void rewritesFormEncodedMessageField() {
        LiepinMessageRequest.RewriteResult result = LiepinMessageRequest.rewrite(
                "jobId=123&msg=%E9%A2%84%E8%AE%BE%E8%AF%AD",
                "AI话术"
        );

        assertTrue(result.rewritten());
        assertTrue(result.body().contains("AI%话术") || result.body().contains("AI"));
    }
}
