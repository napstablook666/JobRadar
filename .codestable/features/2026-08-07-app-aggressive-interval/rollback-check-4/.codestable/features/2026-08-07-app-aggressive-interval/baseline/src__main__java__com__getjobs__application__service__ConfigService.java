package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.getjobs.application.entity.ConfigEntity;
import com.getjobs.application.mapper.ConfigMapper;
import com.getjobs.application.entity.LiepinConfigEntity;
import com.getjobs.application.service.LiepinService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.application.service.Job51Service;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.job51.Job51Config;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.zhilian.ZhilianConfig;
import com.getjobs.worker.utils.KeywordParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置服务类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {
    private final ConfigMapper configMapper;
    private final LiepinService liepinService;
    private final BossService bossService;
    private final ZhilianService zhilianService;
    private final Job51Service job51Service;

    /**
     * 获取所有配置（以Map形式返回）
     * @return 配置Map，key为config_key，value为config_value
     */
    public Map<String, String> getAllConfigsAsMap() {
        List<ConfigEntity> configs = configMapper.selectList(null);
        Map<String, String> configMap = new HashMap<>();

        for (ConfigEntity config : configs) {
            configMap.put(config.getConfigKey(), config.getConfigValue());
        }

        return configMap;
    }

    /**
     * 获取所有配置
     * @return 配置列表
     */
    public List<ConfigEntity> getAllConfigs() {
        return configMapper.selectList(null);
    }

    /**
     * 根据配置键获取配置
     * @param configKey 配置键
     * @return 配置实体
     */
    public ConfigEntity getConfigByKey(String configKey) {
        LambdaQueryWrapper<ConfigEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConfigEntity::getConfigKey, configKey);
        return configMapper.selectOne(queryWrapper);
    }

    /**
     * 根据分类获取配置列表
     * @param category 分类
     * @return 配置列表
     */
    public List<ConfigEntity> getConfigsByCategory(String category) {
        LambdaQueryWrapper<ConfigEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConfigEntity::getCategory, category);
        return configMapper.selectList(queryWrapper);
    }

    /**
     * 根据配置键获取配置值（可能为null）
     * @param configKey 配置键
     * @return 配置值或null
     */
    public String getConfigValue(String configKey) {
        ConfigEntity entity = getConfigByKey(configKey);
        return entity != null ? entity.getConfigValue() : null;
    }

    /**
     * 根据配置键获取必填配置值（缺失或空则抛异常）
     * @param configKey 配置键
     * @return 配置值（非空）
     * @throws IllegalStateException 当配置缺失或空白时抛出
     */
    public String requireConfigValue(String configKey) {
        String value = getConfigValue(configKey);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必要配置: " + configKey);
        }
        return value;
    }

    /**
     * 获取AI调用所需的基础配置（BASE_URL, API_KEY, MODEL）
     * @return 配置Map，包含 BASE_URL, API_KEY, MODEL 键
     */
    public Map<String, String> getAiConfigs() {
        Map<String, String> result = new HashMap<>();
        String baseUrl = requireConfigValue("BASE_URL");
        String apiKey = requireConfigValue("API_KEY");
        String model = requireConfigValue("MODEL");
        result.put("BASE_URL", baseUrl);
        result.put("API_KEY", apiKey);
        result.put("MODEL", model);
        return result;
    }

    /**
     * 批量更新配置
     * @param configMap 配置Map，key为config_key，value为config_value
     * @return 更新的配置数量
     */
    @Transactional
    public int batchUpdateConfigs(Map<String, String> configMap) {
        int updateCount = 0;

        for (Map.Entry<String, String> entry : configMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            ConfigEntity config = getConfigByKey(key);

            if (config != null) {
                config.setConfigValue(value);
                config.setUpdatedAt(LocalDateTime.now());
                configMapper.updateById(config);
                updateCount++;
                log.info("更新配置: {} = {}", key, value);
            } else {
                log.warn("配置键不存在: {}", key);
            }
        }

        return updateCount;
    }

    /**
     * 更新单个配置
     * @param configKey 配置键
     * @param configValue 配置值
     * @return 是否更新成功
     */
    @Transactional
    public boolean updateConfig(String configKey, String configValue) {
        ConfigEntity config = getConfigByKey(configKey);

        if (config != null) {
            config.setConfigValue(configValue);
            config.setUpdatedAt(LocalDateTime.now());
            int result = configMapper.updateById(config);

            if (result > 0) {
                log.info("更新配置成功: {} = {}", configKey, configValue);
                return true;
            }
        } else {
            log.warn("配置键不存在: {}", configKey);
        }

        return false;
    }

    /**
     * 创建新配置
     * @param config 配置实体
     * @return 是否创建成功
     */
    @Transactional
    public boolean createConfig(ConfigEntity config) {
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());

        int result = configMapper.insert(config);

        if (result > 0) {
            log.info("创建配置成功: {} = {}", config.getConfigKey(), config.getConfigValue());
            return true;
        }

        return false;
    }

    /**
     * 统一入口：从专表 liepin_config 读取并构建 LiepinConfig
     * 说明：每个平台维持专表，由 ConfigService 暴露统一读取方法供 Worker 使用。
     */
    public LiepinConfig getLiepinConfig() {
        LiepinConfigEntity entity = liepinService.getFirstConfig();

        LiepinConfig config = new LiepinConfig();

        config.setKeywords(KeywordParser.parse(entity == null ? null : entity.getKeywords()));

        // 城市编码：允许传中文名或代码；中文名映射为代码；缺省视为不限
        String cityCode = "";
        if (entity != null && entity.getCity() != null && !entity.getCity().isEmpty()) {
            // 先按中文名查 code；若查不到，尝试该值是否是有效 code
            String codeByName = liepinService.getCodeByTypeAndName("city", entity.getCity());
            if (codeByName == null || codeByName.isEmpty()) {
                String maybeName = liepinService.getNameByTypeAndCode("city", entity.getCity());
                if (maybeName == null || maybeName.isEmpty() || maybeName.equals(entity.getCity())) {
                    throw new IllegalArgumentException("未在数据库中找到城市编码: " + entity.getCity());
                } else {
                    cityCode = entity.getCity();
                }
            } else {
                cityCode = codeByName;
            }
        }
        config.setCityCode(cityCode);

        // 薪资代码：支持中文名或代码
        String salaryCode = entity != null ? entity.getSalaryCode() : null;
        if (salaryCode == null || salaryCode.isBlank()) {
            config.setSalary("");
        } else {
            String codeByName = liepinService.getCodeByTypeAndName("salary", salaryCode.trim());
            config.setSalary((codeByName != null && !codeByName.isEmpty()) ? codeByName : salaryCode.trim());
        }

        config.setEnableAI(entity == null || entity.getEnableAi() == null || entity.getEnableAi() != 0);
        config.setAutoAiDelivery(entity != null && entity.getAutoAiDelivery() != null
                && entity.getAutoAiDelivery() != 0);
        config.setAiDeliveryMode(entity == null ? "MANUAL" : entity.getAiDeliveryMode());
        config.setAiMinScore(entity == null || entity.getAiMinScore() == null
                ? LiepinConfig.DEFAULT_AI_MIN_SCORE : entity.getAiMinScore());
        config.setAiReviewMinScore(entity == null || entity.getAiReviewMinScore() == null
                ? LiepinConfig.DEFAULT_AI_REVIEW_MIN_SCORE : entity.getAiReviewMinScore());
        config.setAiBatchSize(entity == null || entity.getAiBatchSize() == null
                ? LiepinConfig.DEFAULT_AI_BATCH_SIZE : entity.getAiBatchSize());
        config.setMaxPerRun(entity == null || entity.getMaxPerRun() == null
                ? LiepinConfig.DEFAULT_MAX_PER_RUN : entity.getMaxPerRun());
        config.setMinDelaySeconds(entity == null || entity.getMinDelaySeconds() == null
                ? LiepinConfig.DEFAULT_MIN_DELAY_SECONDS : entity.getMinDelaySeconds());
        config.setMaxDelaySeconds(entity == null || entity.getMaxDelaySeconds() == null
                ? LiepinConfig.DEFAULT_MAX_DELAY_SECONDS : entity.getMaxDelaySeconds());
        config.setSearchMinDelaySeconds(entity == null || entity.getSearchMinDelaySeconds() == null
                ? LiepinConfig.DEFAULT_SEARCH_MIN_DELAY_SECONDS : entity.getSearchMinDelaySeconds());
        config.setSearchMaxDelaySeconds(entity == null || entity.getSearchMaxDelaySeconds() == null
                ? LiepinConfig.DEFAULT_SEARCH_MAX_DELAY_SECONDS : entity.getSearchMaxDelaySeconds());
        config.setPageMinDelaySeconds(entity == null || entity.getPageMinDelaySeconds() == null
                ? LiepinConfig.DEFAULT_PAGE_MIN_DELAY_SECONDS : entity.getPageMinDelaySeconds());
        config.setPageMaxDelaySeconds(entity == null || entity.getPageMaxDelaySeconds() == null
                ? LiepinConfig.DEFAULT_PAGE_MAX_DELAY_SECONDS : entity.getPageMaxDelaySeconds());
        config.setDetailMinDelaySeconds(entity == null || entity.getDetailMinDelaySeconds() == null
                ? LiepinConfig.DEFAULT_DETAIL_MIN_DELAY_SECONDS : entity.getDetailMinDelaySeconds());
        config.setDetailMaxDelaySeconds(entity == null || entity.getDetailMaxDelaySeconds() == null
                ? LiepinConfig.DEFAULT_DETAIL_MAX_DELAY_SECONDS : entity.getDetailMaxDelaySeconds());
        config.setRateGuardBatchSize(entity == null || entity.getRateGuardBatchSize() == null
                ? LiepinConfig.DEFAULT_RATE_GUARD_BATCH_SIZE : entity.getRateGuardBatchSize());
        config.setBatchCooldownMinSeconds(entity == null || entity.getBatchCooldownMinSeconds() == null
                ? LiepinConfig.DEFAULT_BATCH_COOLDOWN_MIN_SECONDS : entity.getBatchCooldownMinSeconds());
        config.setBatchCooldownMaxSeconds(entity == null || entity.getBatchCooldownMaxSeconds() == null
                ? LiepinConfig.DEFAULT_BATCH_COOLDOWN_MAX_SECONDS : entity.getBatchCooldownMaxSeconds());

        return config;
    }

    /**
     * 统一入口：从专表 boss_config 读取并构建 BossConfig
     */
    public BossConfig getBossConfig() {
        return bossService.loadBossConfig();
    }

    /**
     * 统一入口：从专表 zhilian_config 读取并构建 ZhilianConfig
     */
    public ZhilianConfig getZhilianConfig() {
        return zhilianService.loadZhilianConfig();
    }

    /**
     * 统一入口：从专表 job51_config 读取并构建 Job51Config
     */
    public Job51Config getJob51Config() {
        return job51Service.loadJob51Config();
    }
}
