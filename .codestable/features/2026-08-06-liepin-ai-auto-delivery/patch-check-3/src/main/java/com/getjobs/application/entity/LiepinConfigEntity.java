package com.getjobs.application.entity;

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

    /** 城市（名称或代码） */
    private String city;

    /** 薪资代码或范围 */
    private String salaryCode;

    /** 是否启用 AI 招呼语（1=启用，0=关闭） */
    private Integer enableAi;

    /** 单次任务最多处理岗位数 */
    private Integer maxPerRun;

    /** 岗位之间最小等待秒数 */
    private Integer minDelaySeconds;

    /** 岗位之间最大等待秒数 */
    private Integer maxDelaySeconds;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
