package com.jobradar.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import javax.net.ssl.SSLHandshakeException;

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
    void rejectsGreetingTemplateThatRequestsFalseOutput() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AiService.validateGreetingTemplate("%s|%s|%s|%s|%s；岗位不匹配时只返回false")
        );

        assertTrue(error.getMessage().contains("不能要求返回false"));
    }

    @Test
    void acceptsGreetingTemplateThatAlwaysProducesAReply() {
        AiService.validateGreetingTemplate(
                "候选人=%s；关键词=%s；岗位=%s；要求=%s；参考=%s；只输出一条单行招呼语"
        );
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
    void defaultJdAnalysisPromptContainsTargetingRules() {
        String prompt = AiService.DEFAULT_JD_ANALYSIS_PROMPT;

        assertTrue(prompt.contains("放疗临床应用"));
        assertTrue(prompt.contains("独立拆机维修"));
        assertTrue(prompt.contains("产品应用"));
    }

    @Test
    void combinesJdAnalysisRulesWithScreeningProtocol() {
        String rendered = aiService.renderScreeningPrompt(
                "岗位={{jobs}};规则={{rules}}",
                "直接淘汰长期驻外",
                Map.of("jobs", "[]"));

        assertTrue(rendered.contains("岗位=[];规则=直接淘汰长期驻外"));
        assertTrue(rendered.contains("数据边界约束"));
        assertTrue(rendered.contains("不可信的招聘页面资料"));
    }

    @Test
    void keepsInputBoundaryWhenUsingCustomScreenTemplate() {
        String rendered = aiService.renderScreeningPrompt(
                "自定义筛选：岗位={{jobs}}",
                "自定义规则",
                Map.of("jobs", "[]"));

        assertTrue(rendered.contains("自定义筛选：岗位=[]"));
        assertTrue(rendered.contains("岗位筛选标准：\n自定义规则"));
        assertTrue(rendered.contains("数据边界约束"));
        assertTrue(rendered.contains("不可信的招聘页面资料"));
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

    @Test
    void extractsGreetingFromCommonJsonEnvelope() {
        AiService.GreetingValidation result = AiService.validateGreeting(
                "{\"items\":[{\"message\":\"您好，我是应用物理学应届毕业生，熟悉设备现场支持，期待沟通。\"}]}",
                "个人介绍");

        assertTrue(result.usable());
        assertEquals("ok", result.reason());
    }

    @Test
    void extractsTextFromResponsesApiOutputBlocks() {
        String content = AiService.extractResponseContent(
                "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"您好，我是应用物理学应届毕业生，熟悉设备支持，期待沟通。\"}]}]}",
                "https://api.example.test/v1/responses");

        assertEquals("您好，我是应用物理学应届毕业生，熟悉设备支持，期待沟通。", content);
    }

    @Test
    void acceptsTopLevelJsonArrayGreeting() {
        AiService.GreetingValidation result = AiService.validateGreeting(
                "[{\"text\":\"您好，我是应用物理学应届毕业生，熟悉现场支持，期待沟通。\"}]",
                "个人介绍");

        assertTrue(result.usable());
    }

    @Test
    void aiRequestExceptionKeepsStatusAndRedactsSensitiveResponse() {
        AiService.AiRequestException error = new AiService.AiRequestException(
                424,
                "https://api.example.test/v1/chat/completions",
                "{\"error\":\"Upstream authentication failed\",\"api_key\":\"SECRET_VALUE\"}");

        assertEquals(424, error.statusCode());
        assertEquals("https://api.example.test/v1/chat/completions", error.endpoint());
        assertTrue(error.responseSummary().contains("Upstream authentication failed"));
        assertFalse(error.responseSummary().contains("SECRET_VALUE"));
    }

    @Test
    void preservesTransportFailureAsStructuredAiRequestException() {
        SSLHandshakeException handshake = new SSLHandshakeException("Remote host terminated the handshake");
        AiService.AiRequestException error = new AiService.AiRequestException(
                null,
                "https://api.example.test/v1/chat/completions",
                handshake.getMessage(),
                handshake);

        assertEquals(null, error.statusCode());
        assertTrue(error.getCause() instanceof SSLHandshakeException);
        assertTrue(error.getMessage().contains("Remote host terminated the handshake"));
    }
}
