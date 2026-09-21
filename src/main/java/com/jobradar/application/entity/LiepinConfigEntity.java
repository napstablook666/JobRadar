package com.jobradar.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("liepin_config")
public class LiepinConfigEntity {
    @TableId(type = IdType.AUTO)
    /** 主键ID */
    private Long id;

    /** 搜索关键词 */
    private String keywords;

    /** A=严格放疗应用，B=医疗岗位保底搜索。 */
    private String searchProfile;

    /** 城市（名称或代码） */
    private String city;

    /** 薪资代码或范围 */
    private String salaryCode;

    /** 是否启用 AI 招呼语（1=启用，0=关闭） */
    private Integer enableAi;

    /** 是否启用 AI 评分通过后自动投递（1=启用，0=关闭） */
    private Integer autoAiDelivery;

    /** AI 投递模式：MANUAL、SINGLE_AUTO、BATCH_SHADOW、BATCH_AUTO */
    private String aiDeliveryMode;

    /** 自动投递最低 AI 评分（0-100） */
    private Integer aiMinScore;

    /** AI 复核最低评分（0-100） */
    private Integer aiReviewMinScore;

    /** AI 批量评分的单批岗位数 */
    private Integer aiBatchSize;

    /** 是否自动重试 AI 超时（1=启用，0=关闭） */
    private Integer aiTimeoutRetryEnabled;

    /** AI 超时自动追加重试次数 */
    private Integer aiTimeoutMaxRetries;

    /** AI 超时重试间隔秒数 */
    private Integer aiTimeoutRetryDelaySeconds;

    /** 每轮最多成功投递的岗位数；0 表示不设上限 */
    private Integer maxPerRun;

    /** 达到每轮成功投递上限后是否停止（1=停止，0=自动继续） */
    private Integer stopAfterMaxPerRun;

    /** 达到每轮上限后自动继续前的短休息区间 */
    private Integer maxPerRunRestMinSeconds;
    private Integer maxPerRunRestMaxSeconds;

    /** 岗位之间最小等待秒数 */
    private Integer minDelaySeconds;

    /** 岗位之间最大等待秒数 */
    private Integer maxDelaySeconds;

    /** 搜索动作之间最小等待秒数 */
    private Integer searchMinDelaySeconds;

    /** 搜索动作之间最大等待秒数 */
    private Integer searchMaxDelaySeconds;

    /** 翻页动作之间最小等待秒数 */
    private Integer pageMinDelaySeconds;

    /** 翻页动作之间最大等待秒数 */
    private Integer pageMaxDelaySeconds;

    /** 详情动作之间最小等待秒数 */
    private Integer detailMinDelaySeconds;

    /** 详情动作之间最大等待秒数 */
    private Integer detailMaxDelaySeconds;

    /** 成功发送多少次后进入批次冷却 */
    private Integer rateGuardBatchSize;

    /** 批次冷却最小等待秒数 */
    private Integer batchCooldownMinSeconds;

    /** 批次冷却最大等待秒数 */
    private Integer batchCooldownMaxSeconds;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
