package com.jobradar.worker.liepin;

import lombok.Data;

import java.util.List;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/napstablook666/JobRadar">https://github.com/napstablook666/JobRadar</a>
 */
@Data
public class LiepinConfig {
    public static final int DEFAULT_MAX_PER_RUN = 15;
    public static final int DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS = 30;
    public static final int DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS = 60;
    public static final int MIN_MAX_PER_RUN_REST_SECONDS = 30;
    public static final int MAX_MAX_PER_RUN_REST_SECONDS = 300;
    public static final int MIN_SAFE_SEND_DELAY_SECONDS = 10;
    public static final int DEFAULT_MIN_DELAY_SECONDS = 20;
    public static final int DEFAULT_MAX_DELAY_SECONDS = 35;
    public static final int MIN_SEARCH_DELAY_SECONDS = 5;
    public static final int MIN_PAGE_DELAY_SECONDS = 3;
    public static final int MIN_DETAIL_DELAY_SECONDS = 5;
    public static final int MAX_RATE_DELAY_SECONDS = 300;
    public static final int DEFAULT_SEARCH_MIN_DELAY_SECONDS = 5;
    public static final int DEFAULT_SEARCH_MAX_DELAY_SECONDS = 10;
    public static final int DEFAULT_PAGE_MIN_DELAY_SECONDS = 3;
    public static final int DEFAULT_PAGE_MAX_DELAY_SECONDS = 5;
    public static final int DEFAULT_DETAIL_MIN_DELAY_SECONDS = 5;
    public static final int DEFAULT_DETAIL_MAX_DELAY_SECONDS = 8;
    public static final int DEFAULT_RATE_GUARD_BATCH_SIZE = 15;
    public static final int MIN_RATE_GUARD_BATCH_SIZE = 1;
    public static final int MAX_RATE_GUARD_BATCH_SIZE = 50;
    public static final int DEFAULT_BATCH_COOLDOWN_MIN_SECONDS = 180;
    public static final int DEFAULT_BATCH_COOLDOWN_MAX_SECONDS = 300;
    public static final int MIN_BATCH_COOLDOWN_SECONDS = 60;
    public static final int MAX_BATCH_COOLDOWN_SECONDS = 3600;
    public static final int DEFAULT_AI_MIN_SCORE = 70;
    public static final int DEFAULT_AI_REVIEW_MIN_SCORE = 60;
    public static final int DEFAULT_AI_BATCH_SIZE = 5;
    public static final int MIN_AI_BATCH_SIZE = 3;
    public static final int MAX_AI_BATCH_SIZE = 10;
    public static final boolean DEFAULT_AI_TIMEOUT_RETRY_ENABLED = true;
    public static final int DEFAULT_AI_TIMEOUT_MAX_RETRIES = 3;
    public static final int MIN_AI_TIMEOUT_MAX_RETRIES = 0;
    public static final int MAX_AI_TIMEOUT_MAX_RETRIES = 10;
    public static final int DEFAULT_AI_TIMEOUT_RETRY_DELAY_SECONDS = 3;
    public static final int MIN_AI_TIMEOUT_RETRY_DELAY_SECONDS = 0;
    public static final int MAX_AI_TIMEOUT_RETRY_DELAY_SECONDS = 60;
    public static final int MIN_AI_SCORE = 0;
    public static final int MAX_AI_SCORE = 100;

    /** 风控信号后的账户级长休息区间。 */
    public static final int ADAPTIVE_RISK_REST_MIN_SECONDS = 30 * 60;
    public static final int ADAPTIVE_RISK_REST_MAX_SECONDS = 45 * 60;
    public static final int ADAPTIVE_PROBE_MAX_SUCCESSFUL_SENDS = 3;
    public static final int ADAPTIVE_PROBE_SEND_MIN_DELAY_SECONDS = 45;
    public static final int ADAPTIVE_PROBE_SEND_MAX_DELAY_SECONDS = 75;
    public static final int ADAPTIVE_RECOVERY_MAX_SUCCESSFUL_SENDS = 8;
    public static final int ADAPTIVE_RECOVERY_SEND_MIN_DELAY_SECONDS = 30;
    public static final int ADAPTIVE_RECOVERY_SEND_MAX_DELAY_SECONDS = 45;

    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    private String searchProfile;

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

    /** 是否自动重试 AI 超时 */
    private Boolean aiTimeoutRetryEnabled = DEFAULT_AI_TIMEOUT_RETRY_ENABLED;

    /** AI 超时自动追加重试次数 */
    private Integer aiTimeoutMaxRetries = DEFAULT_AI_TIMEOUT_MAX_RETRIES;

    /** AI 超时重试间隔秒数 */
    private Integer aiTimeoutRetryDelaySeconds = DEFAULT_AI_TIMEOUT_RETRY_DELAY_SECONDS;

    /** 每轮最多成功投递的合格岗位数；0 表示不设上限 */
    private Integer maxPerRun = DEFAULT_MAX_PER_RUN;

    /** 达到每轮成功投递上限后是否停止 */
    private Boolean stopAfterMaxPerRun = true;

    /** 达到每轮上限后自动继续前的短休息区间 */
    private Integer maxPerRunRestMinSeconds = DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS;
    private Integer maxPerRunRestMaxSeconds = DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS;

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

    public boolean isAiTimeoutRetryEnabled() {
        return aiTimeoutRetryEnabled == null || aiTimeoutRetryEnabled;
    }

    public int effectiveAiTimeoutMaxRetries() {
        int retries = aiTimeoutMaxRetries == null ? DEFAULT_AI_TIMEOUT_MAX_RETRIES : aiTimeoutMaxRetries;
        return Math.max(MIN_AI_TIMEOUT_MAX_RETRIES, Math.min(MAX_AI_TIMEOUT_MAX_RETRIES, retries));
    }

    public int effectiveAiTimeoutRetryDelaySeconds() {
        int delay = aiTimeoutRetryDelaySeconds == null
                ? DEFAULT_AI_TIMEOUT_RETRY_DELAY_SECONDS : aiTimeoutRetryDelaySeconds;
        return Math.max(MIN_AI_TIMEOUT_RETRY_DELAY_SECONDS,
                Math.min(MAX_AI_TIMEOUT_RETRY_DELAY_SECONDS, delay));
    }

    public int effectiveMaxPerRun() {
        return Math.max(0, maxPerRun == null ? DEFAULT_MAX_PER_RUN : maxPerRun);
    }

    public boolean isStopAfterMaxPerRun() {
        return !Boolean.FALSE.equals(stopAfterMaxPerRun);
    }

    public int effectiveMaxPerRunRestMinSeconds() {
        int value = maxPerRunRestMinSeconds == null
                ? DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS : maxPerRunRestMinSeconds;
        return Math.max(MIN_MAX_PER_RUN_REST_SECONDS,
                Math.min(MAX_MAX_PER_RUN_REST_SECONDS, value));
    }

    public int effectiveMaxPerRunRestMaxSeconds() {
        int value = maxPerRunRestMaxSeconds == null
                ? DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS : maxPerRunRestMaxSeconds;
        return Math.max(effectiveMaxPerRunRestMinSeconds(),
                Math.min(MAX_MAX_PER_RUN_REST_SECONDS, value));
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
                MIN_SEARCH_DELAY_SECONDS, MAX_RATE_DELAY_SECONDS);
    }

    public int effectiveSearchMaxDelaySeconds() {
        return effectiveRangeMax(searchMaxDelaySeconds, DEFAULT_SEARCH_MAX_DELAY_SECONDS,
                effectiveSearchMinDelaySeconds(), MAX_RATE_DELAY_SECONDS);
    }

    public int effectivePageMinDelaySeconds() {
        return effectiveRangeMin(pageMinDelaySeconds, DEFAULT_PAGE_MIN_DELAY_SECONDS,
                MIN_PAGE_DELAY_SECONDS, MAX_RATE_DELAY_SECONDS);
    }

    public int effectivePageMaxDelaySeconds() {
        return effectiveRangeMax(pageMaxDelaySeconds, DEFAULT_PAGE_MAX_DELAY_SECONDS,
                effectivePageMinDelaySeconds(), MAX_RATE_DELAY_SECONDS);
    }

    public int effectiveDetailMinDelaySeconds() {
        return effectiveRangeMin(detailMinDelaySeconds, DEFAULT_DETAIL_MIN_DELAY_SECONDS,
                MIN_DETAIL_DELAY_SECONDS, MAX_RATE_DELAY_SECONDS);
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
        return Math.max(min, Math.min(ceiling, resolved));
    }

}
