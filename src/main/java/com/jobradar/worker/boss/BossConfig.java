package com.jobradar.worker.boss;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/napstablook666/JobRadar">https://github.com/napstablook666/JobRadar</a>
 *
 * Boss配置数据类
 * 配置加载由 BossConfigLoaderService 负责
 */
@Data
public class BossConfig {
    /**
     * 用于打招呼的语句
     */
    private String sayHi;

    /**
     * 开发者模式
     */
    private Boolean debugger;

    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /**
     * 城市编码
     */
    private List<String> cityCode;

    /**
     * 自定义城市编码映射
     */
    private Map<String, String> customCityCode;

    /**
     * 行业列表
     */
    private List<String> industry;

    /**
     * 工作经验要求
     */
    private List<String> experience;

    /**
     * 工作类型
     */
    private String jobType;

    /**
     * 薪资范围（多选）
     */
    private java.util.List<String> salary;

    /**
     * 学历要求列表
     */
    private List<String> degree;

    /**
     * 公司规模列表
     */
    private List<String> scale;

    /**
     * 公司融资阶段列表
     */
    private List<String> stage;

    /**
     * 是否开放AI检测
     */
    private Boolean enableAI;

    /** 是否启用岗位级 AI 筛选 */
    private Boolean enableAiScreening = false;

    /** AI 筛选最低通过分数 */
    private Integer aiMinScore = 70;

    /**
     * 是否过滤不活跃hr
     */
    private Boolean filterDeadHR;

    /**
     * 是否发送图片简历
     */
    private Boolean sendImgResume;

    /**
     * 目标薪资
     */
    private List<Integer> expectedSalary;

    /**
     * 等待时间
     */
    private String waitTime;

    /**
     * HR未上线状态
     */
    private List<String> deadStatus;

    public int effectiveAiMinScore() {
        int score = aiMinScore == null ? 70 : aiMinScore;
        return Math.max(0, Math.min(100, score));
    }
}
