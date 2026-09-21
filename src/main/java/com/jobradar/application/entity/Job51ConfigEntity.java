package com.jobradar.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("job51_config")
public class Job51ConfigEntity {
    @TableId(type = IdType.AUTO)
    /** 主键ID */
    private Long id;

    /** 搜索关键词（逗号或括号列表，例如 "[Java,后端]" 或 "Java,后端"） */
    private String keywords;

    /** A=严格放疗应用，B=病理/IVD/临床协调/产品/医疗运营保底。 */
    private String searchProfile;

    /** 城市区域（中文名或代码，列表字符串） */
    private String jobArea;

    /** 薪资范围（中文名或代码，列表字符串） */
    private String salary;

    /** 是否启用逐岗位 AI 打招呼（1=启用，0=关闭） */
    @TableField("enable_ai")
    private Integer enableAi;

    /** AI JD 分析最低通过分数 */
    @TableField("ai_min_score")
    private Integer aiMinScore;

    /** 每轮最多成功投递的岗位数；0 表示不设上限 */
    @TableField("max_per_run")
    private Integer maxPerRun;

    /** 达到每轮成功投递上限后是否停止（1=停止，0=自动继续） */
    @TableField("stop_after_max_per_run")
    private Integer stopAfterMaxPerRun;

    /** 达到每轮上限后自动继续前的短休息区间 */
    @TableField("max_per_run_rest_min_seconds")
    private Integer maxPerRunRestMinSeconds;

    @TableField("max_per_run_rest_max_seconds")
    private Integer maxPerRunRestMaxSeconds;

    /** AI 模式岗位之间的最小等待秒数 */
    @TableField("min_delay_seconds")
    private Integer minDelaySeconds;

    /** AI 模式岗位之间的最大等待秒数 */
    @TableField("max_delay_seconds")
    private Integer maxDelaySeconds;

    /** 是否启用智能休息（1=启用，0=关闭） */
    @TableField("rest_enabled")
    private Integer restEnabled;

    @TableField("rest_stage1_min_seconds")
    private Integer restStage1MinSeconds;

    @TableField("rest_stage1_max_seconds")
    private Integer restStage1MaxSeconds;

    @TableField("rest_stage2_min_seconds")
    private Integer restStage2MinSeconds;

    @TableField("rest_stage2_max_seconds")
    private Integer restStage2MaxSeconds;

    @TableField("rest_stage3_min_seconds")
    private Integer restStage3MinSeconds;

    @TableField("rest_stage3_max_seconds")
    private Integer restStage3MaxSeconds;

    @TableField("rest_max_consecutive")
    private Integer restMaxConsecutive;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
