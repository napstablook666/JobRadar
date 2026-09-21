package com.jobradar.worker.zhilian;

import com.jobradar.worker.utils.JobUtils;
import lombok.Data;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Objects;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/napstablook666/JobRadar">https://github.com/napstablook666/JobRadar</a>
 */
@Data
public class ZhilianConfig {
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

    /** 是否启用逐岗位 AI JD 筛选，默认开启以覆盖详情页整页 JD 分析。 */
    private Boolean enableAiScreening = true;

    /** AI JD 筛选最低通过分数。 */
    private Integer aiMinScore = 70;

    public boolean isAiScreeningEnabled() {
        return Boolean.TRUE.equals(enableAiScreening);
    }

    public int effectiveAiMinScore() {
        int score = aiMinScore == null ? 70 : aiMinScore;
        return Math.max(0, Math.min(100, score));
    }


    // 注意：已改为在 ZhilianJobService 中通过 ConfigService 构建配置
    // 保留空的 init 以兼容旧调用，但建议不要再使用
    @SneakyThrows
    public static ZhilianConfig init() {
        throw new UnsupportedOperationException("请在 ZhilianJobService 中通过 ConfigService 构建配置");
    }

}
