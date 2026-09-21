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
    public static final int MIN_SAFE_SEND_DELAY_SECONDS = 120;
    public static final int DEFAULT_MIN_DELAY_SECONDS = 120;
    public static final int DEFAULT_MAX_DELAY_SECONDS = 240;
    public static final int MIN_RATE_DELAY_SECONDS = 15;
    public static final int MAX_RATE_DELAY_SECONDS = 300;
    public static final int DEFAULT_SEARCH_MIN_DELAY_SECONDS = 60;
    public static final int DEFAULT_SEARCH_MAX_DELAY_SECONDS = 120;
    public static final int DEFAULT_PAGE_MIN_DELAY_SECONDS = 30;
    public static final int DEFAULT_PAGE_MAX_DELAY_SECONDS = 60;
    public static final int DEFAULT_DETAIL_MIN_DELAY_SECONDS = 45;
    public static final int DEFAULT_DETAIL_MAX_DELAY_SECONDS = 90;
    public static final int DEFAULT_RATE_GUARD_BATCH_SIZE = 5;
    public static final int MIN_RATE_GUARD_BATCH_SIZE = 1;
    public static final int MAX_RATE_GUARD_BATCH_SIZE = 10;
    public static final int DEFAULT_BATCH_COOLDOWN_MIN_SECONDS = 1200;
    public static final int DEFAULT_BATCH_COOLDOWN_MAX_SECONDS = 1800;
    public static final int MIN_BATCH_COOLDOWN_SECONDS = 300;
    public static final int MAX_BATCH_COOLDOWN_SECONDS = 3600;
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

    /** 搜索动作之间的最小随机等待秒数 */
    private Integer searchMinDelaySeconds = DEFAULT_SEARCH_MIN_DELAY_SECONDS;

    /** 搜索动作之间的最大随机等待秒数 */
    private Integer searchMaxDelaySeconds = DEFAULT_SEARCH_MAX_DELAY_SECONDS;

    /** 翻页动作之间的最小随机等待秒数 */
    private Integer pageMinDelaySeconds = DEFAULT_PAGE_MIN_DELAY_SECONDS;

    /** 翻页动作之间的最大随机等待秒数 */
    private Integer pageMaxDelaySeconds = DEFAULT_PAGE_MAX_DELAY_SECONDS;

    /** 详情动作之间的最小随机等待秒数 */
    private Integer detailMinDelaySeconds = DEFAULT_DETAIL_MIN_DELAY_SECONDS;

    /** 详情动作之间的最大随机等待秒数 */
    private Integer detailMaxDelaySeconds = DEFAULT_DETAIL_MAX_DELAY_SECONDS;

    /** 成功发送多少次后进入批次冷却 */
    private Integer rateGuardBatchSize = DEFAULT_RATE_GUARD_BATCH_SIZE;

    /** 批次冷却的最小随机等待秒数 */
    private Integer batchCooldownMinSeconds = DEFAULT_BATCH_COOLDOWN_MIN_SECONDS;

    /** 批次冷却的最大随机等待秒数 */
    private Integer batchCooldownMaxSeconds = DEFAULT_BATCH_COOLDOWN_MAX_SECONDS;

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
        return effectiveRangeMin(minDelaySeconds, DEFAULT_MIN_DELAY_SECONDS, MIN_SAFE_SEND_DELAY_SECONDS,
                MAX_RATE_DELAY_SECONDS);
    }

    public int effectiveMaxDelaySeconds() {
        return effectiveRangeMax(maxDelaySeconds, DEFAULT_MAX_DELAY_SECONDS, effectiveMinDelaySeconds(),
                MAX_RATE_DELAY_SECONDS);
    }

    public int effectiveSearchMinDelaySeconds() {
        return effectiveRangeMin(searchMinDelaySeconds, DEFAULT_SEARCH_MIN_DELAY_SECONDS,
                MIN_RATE_DELAY_SECONDS, MAX_RATE_DELAY_SECONDS);
    }

    public int effectiveSearchMaxDelaySeconds() {
        return effectiveRangeMax(searchMaxDelaySeconds, DEFAULT_SEARCH_MAX_DELAY_SECONDS,
                effectiveSearchMinDelaySeconds(), MAX_RATE_DELAY_SECONDS);
    }

    public int effectivePageMinDelaySeconds() {
        return effectiveRangeMin(pageMinDelaySeconds, DEFAULT_PAGE_MIN_DELAY_SECONDS,
                MIN_RATE_DELAY_SECONDS, MAX_RATE_DELAY_SECONDS);
    }

    public int effectivePageMaxDelaySeconds() {
        return effectiveRangeMax(pageMaxDelaySeconds, DEFAULT_PAGE_MAX_DELAY_SECONDS,
                effectivePageMinDelaySeconds(), MAX_RATE_DELAY_SECONDS);
    }

    public int effectiveDetailMinDelaySeconds() {
        return effectiveRangeMin(detailMinDelaySeconds, DEFAULT_DETAIL_MIN_DELAY_SECONDS,
                MIN_RATE_DELAY_SECONDS, MAX_RATE_DELAY_SECONDS);
    }

    public int effectiveDetailMaxDelaySeconds() {
        return effectiveRangeMax(detailMaxDelaySeconds, DEFAULT_DETAIL_MAX_DELAY_SECONDS,
                effectiveDetailMinDelaySeconds(), MAX_RATE_DELAY_SECONDS);
    }

    public int effectiveRateGuardBatchSize() {
        int value = rateGuardBatchSize == null ? DEFAULT_RATE_GUARD_BATCH_SIZE : rateGuardBatchSize;
        return Math.max(MIN_RATE_GUARD_BATCH_SIZE, Math.min(MAX_RATE_GUARD_BATCH_SIZE, value));
    }

    public int effectiveBatchCooldownMinSeconds() {
        return effectiveRangeMin(batchCooldownMinSeconds, DEFAULT_BATCH_COOLDOWN_MIN_SECONDS,
                MIN_BATCH_COOLDOWN_SECONDS, MAX_BATCH_COOLDOWN_SECONDS);
    }

    public int effectiveBatchCooldownMaxSeconds() {
        return effectiveRangeMax(batchCooldownMaxSeconds, DEFAULT_BATCH_COOLDOWN_MAX_SECONDS,
                effectiveBatchCooldownMinSeconds(), MAX_BATCH_COOLDOWN_SECONDS);
    }

    private int effectiveRangeMin(Integer value, int fallback, int floor, int ceiling) {
        int resolved = value == null ? fallback : value;
        return Math.max(floor, Math.min(ceiling, resolved));
    }

    private int effectiveRangeMax(Integer value, int fallback, int min, int ceiling) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(ceiling, Math.max(fallback, resolved)));
    }

}
