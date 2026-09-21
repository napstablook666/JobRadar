package com.getjobs.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

class AiPromptTest {

    private final AiService aiService = new AiService(null, null);

    @Test
    void rendersProductionArgumentOrder() {
        String result = aiService.renderPrompt(
                "%s|%s|%s|%s|%s",
                "个人介绍",
                "放疗设备",
                "应用工程师助理",
                "岗位要求",
                "参考语"
        );

        assertEquals("个人介绍|放疗设备|应用工程师助理|岗位要求|参考语", result);
    }

    @Test
    void rejectsTemplateWithWrongPlaceholderCount() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> aiService.renderPrompt("%s|%s", "a", "b", "c", "d", "e")
        );

        assertTrue(error.getMessage().contains("必须包含5个%s"));
    }

    @Test
    void insertedJobTextCanContainPercentSigns() {
        String result = aiService.renderPrompt(
                "%s %s %s %s %s",
                "介绍",
                "关键词",
                "岗位",
                "薪资涨幅50%，负责现场支持",
                "参考"
        );

        assertTrue(result.contains("薪资涨幅50%，负责现场支持"));
    }

    @Test
    void rendersNamedBatchTemplateWithoutPositionErrors() {
        String result = aiService.renderNamedTemplate(
                "候选人={{candidate}};岗位={{jobs}};阈值={{min_score}}",
                Map.of("candidate", "介绍", "jobs", "[]", "min_score", "70")
        );

        assertEquals("候选人=介绍;岗位=[];阈值=70", result);
    }

    @Test
    void rejectsUnknownNamedTemplateVariable() {
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> aiService.renderNamedTemplate("{{unknown}}", Map.of())
        );

        assertTrue(error.getMessage().contains("未提供"));
    }

    @Test
    void acceptsGreetingWithTheRequiredPrefix() {
        AiService.GreetingValidation result = AiService.validateGreeting(
                "您好，我是应用物理学应届毕业生，熟悉放疗设备现场流程，期待与您沟通。",
                "我叫测试用户，是应用物理学应届毕业生。");

        assertTrue(result.usable());
        assertEquals("您好，我是应用物理学应届毕业生，熟悉放疗设备现场流程，期待与您沟通。", result.message());
    }

    @Test
    void rejectsCandidateNameAndWrongPrefix() {
        AiService.GreetingValidation named = AiService.validateGreeting(
                "您好，我是应用物理学应届毕业生，测试用户熟悉放疗设备现场流程。",
                "我叫测试用户，是应用物理学应届毕业生。");
        AiService.GreetingValidation wrongPrefix = AiService.validateGreeting(
                "您好，我是应用物理学应届生，熟悉放疗设备现场流程。",
                "我叫测试用户，是应用物理学应届毕业生。");

        assertFalse(named.usable());
        assertEquals("candidate_name", named.reason());
        assertFalse(wrongPrefix.usable());
        assertEquals("wrong_prefix", wrongPrefix.reason());
    }

    @Test
    void normalizesMarkdownAndPreservesFalseRejection() {
        AiService.GreetingValidation fenced = AiService.validateGreeting(
                "```text\n您好，我是应用物理学应届毕业生，熟悉放疗设备现场流程。\n```",
                "个人介绍");
        AiService.GreetingValidation rejected = AiService.validateGreeting("false", "个人介绍");

        assertTrue(fenced.usable());
        assertEquals("您好，我是应用物理学应届毕业生，熟悉放疗设备现场流程。", fenced.message());
        assertFalse(rejected.usable());
        assertTrue(rejected.rejected());
    }
}
