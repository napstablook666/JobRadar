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

}
