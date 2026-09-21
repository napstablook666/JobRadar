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

    /** 单次任务最多处理的合格岗位数 */
    private Integer maxPerRun = DEFAULT_MAX_PER_RUN;

    /** 岗位之间的最小随机等待秒数 */
    private Integer minDelaySeconds = DEFAULT_MIN_DELAY_SECONDS;

    /** 岗位之间的最大随机等待秒数 */
    private Integer maxDelaySeconds = DEFAULT_MAX_DELAY_SECONDS;

    public boolean isAiEnabled() {
        return enableAI == null || enableAI;
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
