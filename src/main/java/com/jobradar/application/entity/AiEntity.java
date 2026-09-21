package com.jobradar.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI配置实体类
 */
@Data
@TableName("ai")
public class AiEntity {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 技能介绍
     */
    @TableField("introduce")
    private String introduce;

    /**
     * AI提示词
     */
    @TableField("prompt")
    private String prompt;

    /** 批量评分提示词模板 */
    @TableField("screen_prompt")
    private String screenPrompt;

    /** AI JD 分析提示词模板 */
    @TableField("jd_analysis_prompt")
    private String jdAnalysisPrompt;
    /** 通过岗位的话术提示词模板 */
    @TableField("message_prompt")
    private String messagePrompt;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
