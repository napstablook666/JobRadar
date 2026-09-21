package com.getjobs.worker.liepin;

import lombok.Data;

import java.util.List;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Data
public class LiepinConfig {
    public static final int MAX_SAFE_PER_RUN = 10;
    public static final int DEFAULT_MAX_PER_RUN = 10;
    public static final int MIN_SAFE_SEND_DELAY_SECONDS = 90;
    public static final int DEFAULT_MIN_DELAY_SECONDS = 90;
    public static final int DEFAULT_MAX_DELAY_SECONDS = 180;
    public static final int DEFAULT_AI_MIN_SCORE = 70;
    public static final int DEFAULT_AI_REVIEW_MIN_SCORE = 60;
    public static final int DEFAULT_AI_BATCH_SIZE = 5;
    public static final int MIN_AI_BATCH_SIZE = 3;
    public static final int MAX_AI_BATCH_SIZE = 10;
    public static final int MIN_AI_SCORE = 0;
    public static final int MAX_AI_SCORE = 100;

    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /**
     * 城市编码
     */
    private String cityCode;

    /**
     * 薪资范围
     */
    private String salary;

    /** 是否启用逐岗位 AI 招呼语 */
    private Boolean enableAI = true;

    /** 是否启用 AI 评分通过后自动投递 */
    private Boolean autoAiDelivery = false;

    /** AI 投递模式 */
    private String aiDeliveryMode;

    /** 自动投递的最低 AI 评分 */
    private Integer aiMinScore = DEFAULT_AI_MIN_SCORE;

    /** 进入人工复核记录的最低评分 */
    private Integer aiReviewMinScore = DEFAULT_AI_REVIEW_MIN_SCORE;

    /** 批量评分的单批岗位数 */
    private Integer aiBatchSize = DEFAULT_AI_BATCH_SIZE;

    /** 单次任务最多处理的合格岗位数 */
    private Integer maxPerRun = DEFAULT_MAX_PER_RUN;

    /** 岗位之间的最小随机等待秒数 */
    private Integer minDelaySeconds = DEFAULT_MIN_DELAY_SECONDS;

    /** 岗位之间的最大随机等待秒数 */
    private Integer maxDelaySeconds = DEFAULT_MAX_DELAY_SECONDS;

    public boolean isAiEnabled() {
        return enableAI == null || enableAI;
    }

    public boolean isAutoAiDeliveryEnabled() {
        return "SINGLE_AUTO".equals(effectiveDeliveryMode()) || isBatchAutoDeliveryEnabled();
    }

    public boolean isBatchAutoDeliveryEnabled() {
        return "BATCH_AUTO".equals(effectiveDeliveryMode());
    }

    public boolean isBatchShadowEnabled() {
        return "BATCH_SHADOW".equals(effectiveDeliveryMode());
    }

    public String effectiveDeliveryMode() {
        if (aiDeliveryMode == null || aiDeliveryMode.isBlank()) {
            return Boolean.TRUE.equals(autoAiDelivery) ? "SINGLE_AUTO" : "MANUAL";
        }
        return switch (aiDeliveryMode.trim().toUpperCase()) {
            case "SINGLE_AUTO", "BATCH_SHADOW", "BATCH_AUTO" -> aiDeliveryMode.trim().toUpperCase();
            default -> "MANUAL";
        };
    }

    public int effectiveAiMinScore() {
        int score = aiMinScore == null ? DEFAULT_AI_MIN_SCORE : aiMinScore;
        return Math.max(MIN_AI_SCORE, Math.min(MAX_AI_SCORE, score));
    }

    public int effectiveAiReviewMinScore() {
        int score = aiReviewMinScore == null ? DEFAULT_AI_REVIEW_MIN_SCORE : aiReviewMinScore;
        return Math.max(MIN_AI_SCORE, Math.min(effectiveAiMinScore(), score));
    }

    public int effectiveAiBatchSize() {
        int size = aiBatchSize == null ? DEFAULT_AI_BATCH_SIZE : aiBatchSize;
        return Math.max(MIN_AI_BATCH_SIZE, Math.min(MAX_AI_BATCH_SIZE, size));
    }

    public int effectiveMaxPerRun() {
        return Math.min(maxPerRun == null ? DEFAULT_MAX_PER_RUN : maxPerRun,
                MAX_SAFE_PER_RUN);
    }

    public int effectiveMinDelaySeconds() {
        return Math.max(minDelaySeconds == null ? DEFAULT_MIN_DELAY_SECONDS : minDelaySeconds,
                MIN_SAFE_SEND_DELAY_SECONDS);
    }

    public int effectiveMaxDelaySeconds() {
        return Math.max(
                Math.max(maxDelaySeconds == null ? DEFAULT_MAX_DELAY_SECONDS : maxDelaySeconds,
                        DEFAULT_MAX_DELAY_SECONDS),
                effectiveMinDelaySeconds()
        );
    }

}
