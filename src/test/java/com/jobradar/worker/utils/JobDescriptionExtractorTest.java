package com.jobradar.worker.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobDescriptionExtractorTest {

    @Test
    void keepsJobDutiesAndRequirementsWhileDroppingPageControlsAndCompanyTail() {
        String text = "首页\n立即投递\n职位描述\n负责放疗设备临床应用培训和客户现场支持。\n"
                + "任职要求\n本科及以上，医学物理相关专业，可接受短期出差。\n公司信息\n公司成立于2010年\n相关职位";

        String result = JobDescriptionExtractor.normalize(text);

        assertNotNull(result);
        assertTrue(result.contains("临床应用培训"));
        assertTrue(result.contains("任职要求"));
        assertFalse(result.contains("立即投递"));
        assertFalse(result.contains("公司成立"));
    }

    @Test
    void rejectsAccessVerificationText() {
        assertFalse(JobDescriptionExtractor.isUsable("访问验证，请按住滑块完成验证后继续浏览岗位详情"));
        assertFalse(JobDescriptionExtractor.isUsable("安全验证：请完成验证后继续浏览岗位详情"));
    }

    @Test
    void limitsLongJobTextBeforeAiUse() {
        String result = JobDescriptionExtractor.normalize("职位描述\n" + "岗位职责内容".repeat(3_000));

        assertNotNull(result);
        assertTrue(result.length() <= JobDescriptionExtractor.MAX_TEXT_LENGTH);
    }

    @Test
    void rejectsCompanyOrRecommendationTailWhenItStartsThePage() {
        assertFalse(JobDescriptionExtractor.isUsable(
                JobDescriptionExtractor.normalize("公司信息\n公司规模500人\n相关职位\n医疗设备工程师")));
        assertFalse(JobDescriptionExtractor.isUsable(
                JobDescriptionExtractor.normalize("相关推荐\n放疗设备工程师\n立即投递")));
        assertFalse(JobDescriptionExtractor.isUsable(
                JobDescriptionExtractor.normalize("公司信息\n公司规模500人\n相关职位\n职位描述\n推荐岗位职责内容")));
    }

    @Test
    void rejectsLongHeaderBeforeCompanyTailAndRecommendedFakeDescription() {
        String page = "高级医学工程师\n北京 15-20K\n某医疗器械公司\n"
                + "公司信息\n公司规模500人\n相关职位\n职位描述\n推荐岗位职责内容";

        assertFalse(JobDescriptionExtractor.isUsable(JobDescriptionExtractor.normalize(page)));
    }
}
