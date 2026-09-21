package com.jobradar.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("zhilian_config")
public class ZhilianConfigEntity {
    @TableId(type = IdType.AUTO)
    /** 主键ID */
    private Long id;

    /** 搜索关键词（逗号或括号列表，例如 "[Java,后端]" 或 "Java,后端"） */
    private String keywords;

    /** 城市（中文名或代码，单值） */
    private String cityCode;

    /** 薪资范围（中文名或代码，单值） */
    private String salary;

    /** 是否启用岗位级 AI JD 筛选（1=启用，0=关闭） */
    @com.baomidou.mybatisplus.annotation.TableField("enable_ai_screening")
    private Integer enableAiScreening;

    /** AI JD 筛选最低通过分数（0-100） */
    @com.baomidou.mybatisplus.annotation.TableField("ai_min_score")
    private Integer aiMinScore;
    /** 是否已完成 AI JD 筛选迁移或用户已保存过配置（1=是，0/NULL=尚未标记） */
    @com.baomidou.mybatisplus.annotation.TableField("ai_screening_configured")
    private Integer aiScreeningConfigured;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}