package com.jobradar.worker.job51;

import com.jobradar.worker.utils.JobUtils;
import lombok.Data;
import lombok.SneakyThrows;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/napstablook666/JobRadar">https://github.com/napstablook666/JobRadar</a>
 * 前程无忧自动投递简历
 */
@Data
public class Job51Config {

    public static final int DEFAULT_MAX_PER_RUN = 8;
    public static final int DEFAULT_AI_MIN_SCORE = 70;
    public static final int MIN_AI_SCORE = 0;
    public static final int MAX_AI_SCORE = 100;
    public static final int DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS = 120;
    public static final int DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS = 240;
    public static final int MIN_MAX_PER_RUN_REST_SECONDS = 30;
    public static final int MAX_MAX_PER_RUN_REST_SECONDS = 300;
    public static final int MIN_DELAY_SECONDS = 20;
    public static final int MAX_DELAY_SECONDS = 300;
    public static final int DEFAULT_MIN_DELAY_SECONDS = 20;
    public static final int DEFAULT_MAX_DELAY_SECONDS = 40;
    public static final int MIN_REST_SECONDS = 30;
    public static final int MAX_REST_SECONDS = 86400;
    public static final int DEFAULT_REST_STAGE1_MIN_SECONDS = 180;
    public static final int DEFAULT_REST_STAGE1_MAX_SECONDS = 300;
    public static final int DEFAULT_REST_STAGE2_MIN_SECONDS = 600;
    public static final int DEFAULT_REST_STAGE2_MAX_SECONDS = 900;
    public static final int DEFAULT_REST_STAGE3_MIN_SECONDS = 1800;
    public static final int DEFAULT_REST_STAGE3_MAX_SECONDS = 2700;
    public static final int MIN_REST_CONSECUTIVE = 1;
    public static final int MAX_REST_CONSECUTIVE = 10;
    public static final int DEFAULT_REST_MAX_CONSECUTIVE = 3;


    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    private String searchProfile = "A";

    /**
     * 城市编码
     */
    private List<String> jobArea;

    /**
     * 薪资范围
     */
    private List<String> salary;

    /** 是否启用逐岗位 AI 打招呼 */
    private Boolean enableAI = false;

    /** AI JD 分析通过的最低评分 */
    private Integer aiMinScore = DEFAULT_AI_MIN_SCORE;

    /** 每轮最多成功投递的岗位数；0 表示不设上限 */
    private Integer maxPerRun = DEFAULT_MAX_PER_RUN;

    /** 达到每轮成功投递上限后是否停止 */
    private Boolean stopAfterMaxPerRun = false;

    /** 达到每轮上限后自动继续前的短休息区间 */
    private Integer maxPerRunRestMinSeconds = DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS;
    private Integer maxPerRunRestMaxSeconds = DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS;

    /** AI 模式岗位之间的最小随机等待秒数 */
    private Integer minDelaySeconds = DEFAULT_MIN_DELAY_SECONDS;

    /** AI 模式岗位之间的最大随机等待秒数 */
    private Integer maxDelaySeconds = DEFAULT_MAX_DELAY_SECONDS;

    /** 是否启用智能休息 */
    private Boolean restEnabled = true;

    /** 智能休息第一档随机区间 */
    private Integer restStage1MinSeconds = DEFAULT_REST_STAGE1_MIN_SECONDS;
    private Integer restStage1MaxSeconds = DEFAULT_REST_STAGE1_MAX_SECONDS;

    /** 智能休息第二档随机区间 */
    private Integer restStage2MinSeconds = DEFAULT_REST_STAGE2_MIN_SECONDS;
    private Integer restStage2MaxSeconds = DEFAULT_REST_STAGE2_MAX_SECONDS;

    /** 智能休息第三档随机区间；连续触发达到上限后持续使用此档探测 */
    private Integer restStage3MinSeconds = DEFAULT_REST_STAGE3_MIN_SECONDS;
    private Integer restStage3MaxSeconds = DEFAULT_REST_STAGE3_MAX_SECONDS;

    /** 进入第三档前允许的连续休息次数 */
    private Integer restMaxConsecutive = DEFAULT_REST_MAX_CONSECUTIVE;

    public boolean isAiEnabled() {
        return Boolean.TRUE.equals(enableAI);
    }

    public int effectiveAiMinScore() {
        int score = aiMinScore == null ? DEFAULT_AI_MIN_SCORE : aiMinScore;
        return Math.max(MIN_AI_SCORE, Math.min(MAX_AI_SCORE, score));
    }

    public int effectiveMaxPerRun() {
        int value = maxPerRun == null ? DEFAULT_MAX_PER_RUN : maxPerRun;
        return Math.max(0, value);
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
        int value = minDelaySeconds == null ? DEFAULT_MIN_DELAY_SECONDS : minDelaySeconds;
        return Math.max(MIN_DELAY_SECONDS, Math.min(value, MAX_DELAY_SECONDS));
    }

    public int effectiveMaxDelaySeconds() {
        int value = maxDelaySeconds == null ? DEFAULT_MAX_DELAY_SECONDS : maxDelaySeconds;
        return Math.max(effectiveMinDelaySeconds(), Math.min(value, MAX_DELAY_SECONDS));
    }

    public boolean isRestEnabled() {
        return !Boolean.FALSE.equals(restEnabled);
    }

    public int effectiveRestStage1MinSeconds() {
        return clampRestSeconds(restStage1MinSeconds, DEFAULT_REST_STAGE1_MIN_SECONDS);
    }

    public int effectiveRestStage1MaxSeconds() {
        return atLeast(effectiveRestStage1MinSeconds(), restStage1MaxSeconds, DEFAULT_REST_STAGE1_MAX_SECONDS);
    }

    public int effectiveRestStage2MinSeconds() {
        return clampRestSeconds(restStage2MinSeconds, DEFAULT_REST_STAGE2_MIN_SECONDS);
    }

    public int effectiveRestStage2MaxSeconds() {
        return atLeast(effectiveRestStage2MinSeconds(), restStage2MaxSeconds, DEFAULT_REST_STAGE2_MAX_SECONDS);
    }

    public int effectiveRestStage3MinSeconds() {
        return clampRestSeconds(restStage3MinSeconds, DEFAULT_REST_STAGE3_MIN_SECONDS);
    }

    public int effectiveRestStage3MaxSeconds() {
        return atLeast(effectiveRestStage3MinSeconds(), restStage3MaxSeconds, DEFAULT_REST_STAGE3_MAX_SECONDS);
    }

    public int effectiveRestMaxConsecutive() {
        int value = restMaxConsecutive == null ? DEFAULT_REST_MAX_CONSECUTIVE : restMaxConsecutive;
        return Math.max(MIN_REST_CONSECUTIVE, Math.min(value, MAX_REST_CONSECUTIVE));
    }

    private int clampRestSeconds(Integer value, int fallback) {
        int seconds = value == null ? fallback : value;
        return Math.max(MIN_REST_SECONDS, Math.min(seconds, MAX_REST_SECONDS));
    }

    private int atLeast(int min, Integer value, int fallback) {
        int seconds = value == null ? fallback : value;
        return Math.max(min, Math.min(seconds, MAX_REST_SECONDS));
    }


    // 注意：已改为在 Job51JobService 中通过 ConfigService 构建配置
    // 保留空的 init 以兼容旧调用，但建议不要再使用
    @SneakyThrows
    public static Job51Config init() {
        throw new UnsupportedOperationException("请在 Job51JobService 中通过 ConfigService 构建配置");
    }

}
