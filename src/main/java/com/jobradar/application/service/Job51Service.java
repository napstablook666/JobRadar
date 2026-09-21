package com.jobradar.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jobradar.application.entity.Job51ConfigEntity;
import com.jobradar.application.entity.Job51Entity;
import com.jobradar.application.entity.Job51OptionEntity;
import com.jobradar.application.mapper.Job51ConfigMapper;
import com.jobradar.application.mapper.Job51Mapper;
import com.jobradar.application.mapper.Job51OptionMapper;
import com.jobradar.worker.job51.Job51Config;
import com.jobradar.worker.utils.KeywordParser;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class Job51Service {
    public static final String GREETING_NOT_ATTEMPTED = "NOT_ATTEMPTED";
    public static final String GREETING_SKIPPED = "SKIPPED";
    public static final String GREETING_SENT = "SENT";
    public static final String GREETING_FAILED = "FAILED";
    public static final String APPLICATION_ROUTE_INTERNAL = "INTERNAL";
    public static final String APPLICATION_ROUTE_EXTERNAL = "EXTERNAL";
    public static final String EXTERNAL_APPLY_PENDING = "PENDING";
    public static final String EXTERNAL_APPLY_APPLIED = "APPLIED";

    private final Job51ConfigMapper job51ConfigMapper;
    private final Job51OptionMapper job51OptionMapper;
    private final Job51Mapper job51Mapper;
    private final DataSource dataSource;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private JobFunnelService jobFunnelService;

    /** 获取第一条配置（通常只有一条） */
    public Job51ConfigEntity getFirstConfig() {
        QueryWrapper<Job51ConfigEntity> wrapper = new QueryWrapper<>();
        wrapper.last("LIMIT 1");
        return job51ConfigMapper.selectOne(wrapper);
    }

    /** 从专表构建 Job51Config */
    public Job51Config loadJob51Config() {
        Job51ConfigEntity entity = getFirstConfig();
        Job51Config config = new Job51Config();
        if (entity == null) {
            log.warn("job51_config 表为空，使用默认空配置");
            config.setKeywords(new ArrayList<>());
            config.setJobArea(new ArrayList<>());
            config.setSalary(new ArrayList<>());
            config.setEnableAI(false);
            return config;
        }

        // 关键词解析；未配置时按 A/B 搜索方案提供可运行的默认关键词。
        List<String> storedKeywords = parseListString(entity.getKeywords());
        String profile = SearchProfileKeywords.normalize(entity.getSearchProfile(), !storedKeywords.isEmpty());
        config.setSearchProfile(profile);
        config.setKeywords(storedKeywords.isEmpty()
                ? new ArrayList<>(SearchProfileKeywords.defaults(profile)) : storedKeywords);
        // 城市区域：中文名或代码列表 -> 统一为代码列表（优先使用数据库映射）
        List<String> areaInputs = parseListString(entity.getJobArea());
        List<String> areaCodes = new ArrayList<>();
        for (String input : areaInputs) {
            if (input == null || input.isEmpty()) continue;
            String code = normalizeOptionCode("jobArea", input);
            areaCodes.add(code);
        }
        config.setJobArea(areaCodes);

        // 薪资范围：中文名或代码列表 -> 统一为代码列表（优先使用数据库映射）
        List<String> salaryInputs = parseListString(entity.getSalary());
        List<String> salaryCodes = new ArrayList<>();
        for (String input : salaryInputs) {
            if (input == null || input.isEmpty()) continue;
            String code = normalizeOptionCode("salary", input);
            salaryCodes.add(code);
        }
        config.setSalary(salaryCodes);

        config.setEnableAI(entity.getEnableAi() != null && entity.getEnableAi() != 0);
        config.setAiMinScore(entity.getAiMinScore() == null
                ? Job51Config.DEFAULT_AI_MIN_SCORE : entity.getAiMinScore());
        config.setMaxPerRun(entity.getMaxPerRun() == null
                ? Job51Config.DEFAULT_MAX_PER_RUN : entity.getMaxPerRun());
        config.setStopAfterMaxPerRun(entity.getStopAfterMaxPerRun() != null
                && entity.getStopAfterMaxPerRun() != 0);
        config.setMaxPerRunRestMinSeconds(entity.getMaxPerRunRestMinSeconds() == null
                ? Job51Config.DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS : entity.getMaxPerRunRestMinSeconds());
        config.setMaxPerRunRestMaxSeconds(entity.getMaxPerRunRestMaxSeconds() == null
                ? Job51Config.DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS : entity.getMaxPerRunRestMaxSeconds());
        config.setMinDelaySeconds(entity.getMinDelaySeconds() == null
                ? Job51Config.DEFAULT_MIN_DELAY_SECONDS : entity.getMinDelaySeconds());
        config.setMaxDelaySeconds(entity.getMaxDelaySeconds() == null
                ? Job51Config.DEFAULT_MAX_DELAY_SECONDS : entity.getMaxDelaySeconds());
        config.setRestEnabled(entity.getRestEnabled() == null || entity.getRestEnabled() != 0);
        config.setRestStage1MinSeconds(entity.getRestStage1MinSeconds() == null
                ? Job51Config.DEFAULT_REST_STAGE1_MIN_SECONDS : entity.getRestStage1MinSeconds());
        config.setRestStage1MaxSeconds(entity.getRestStage1MaxSeconds() == null
                ? Job51Config.DEFAULT_REST_STAGE1_MAX_SECONDS : entity.getRestStage1MaxSeconds());
        config.setRestStage2MinSeconds(entity.getRestStage2MinSeconds() == null
                ? Job51Config.DEFAULT_REST_STAGE2_MIN_SECONDS : entity.getRestStage2MinSeconds());
        config.setRestStage2MaxSeconds(entity.getRestStage2MaxSeconds() == null
                ? Job51Config.DEFAULT_REST_STAGE2_MAX_SECONDS : entity.getRestStage2MaxSeconds());
        config.setRestStage3MinSeconds(entity.getRestStage3MinSeconds() == null
                ? Job51Config.DEFAULT_REST_STAGE3_MIN_SECONDS : entity.getRestStage3MinSeconds());
        config.setRestStage3MaxSeconds(entity.getRestStage3MaxSeconds() == null
                ? Job51Config.DEFAULT_REST_STAGE3_MAX_SECONDS : entity.getRestStage3MaxSeconds());
        config.setRestMaxConsecutive(entity.getRestMaxConsecutive() == null
                ? Job51Config.DEFAULT_REST_MAX_CONSECUTIVE : entity.getRestMaxConsecutive());

        return config;
    }

    public List<String> parseListString(String raw) {
        return KeywordParser.parse(raw);
    }

    // ==================== 选项相关 ====================

    /** 根据类型获取选项列表 */
    public List<Job51OptionEntity> getOptionsByType(String type) {
        QueryWrapper<Job51OptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type);
        wrapper.orderByAsc("sort_order", "id");
        return job51OptionMapper.selectList(wrapper);
    }

    /** 按类型和输入（代码或名称）归一化为代码 */
    public String normalizeOptionCode(String type, String input) {
        if (input == null || input.trim().isEmpty()) return "";
        String v = input.trim();
        // 先按code匹配
        QueryWrapper<Job51OptionEntity> byCode = new QueryWrapper<>();
        byCode.eq("type", type).eq("code", v);
        Job51OptionEntity c = job51OptionMapper.selectOne(byCode);
        if (c != null) return c.getCode();
        // 再按name匹配
        QueryWrapper<Job51OptionEntity> byName = new QueryWrapper<>();
        byName.eq("type", type).eq("name", v);
        Job51OptionEntity n = job51OptionMapper.selectOne(byName);
        if (n != null) return n.getCode();
        // 不再使用枚举兜底，保留原值（可能已是代码）
        return v;
    }

    // ==================== 表初始化与数据导入 ====================

    @PostConstruct
    public void ensureJob51OptionTableAndData() {
        ensureJob51ConfigSchema();
        // 仅确保表存在；城市选项完全由数据库维护
        ensureJob51OptionTable();
        ensureJob51DataTable();
    }

    private void ensureJob51ConfigSchema() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN enable_ai INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN search_profile VARCHAR(16) DEFAULT 'A'"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN ai_min_score INTEGER NOT NULL DEFAULT 70"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN max_per_run INTEGER NOT NULL DEFAULT 8"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN stop_after_max_per_run INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN max_per_run_rest_min_seconds INTEGER NOT NULL DEFAULT 120"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN max_per_run_rest_max_seconds INTEGER NOT NULL DEFAULT 240"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN min_delay_seconds INTEGER NOT NULL DEFAULT 20"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN max_delay_seconds INTEGER NOT NULL DEFAULT 40"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN rest_enabled INTEGER NOT NULL DEFAULT 1"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN rest_stage1_min_seconds INTEGER NOT NULL DEFAULT 180"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN rest_stage1_max_seconds INTEGER NOT NULL DEFAULT 300"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN rest_stage2_min_seconds INTEGER NOT NULL DEFAULT 600"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN rest_stage2_max_seconds INTEGER NOT NULL DEFAULT 900"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN rest_stage3_min_seconds INTEGER NOT NULL DEFAULT 1800"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN rest_stage3_max_seconds INTEGER NOT NULL DEFAULT 2700"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_config ADD COLUMN rest_max_consecutive INTEGER NOT NULL DEFAULT 3"); } catch (Exception ignored) {}
        } catch (Exception e) {
            log.warn("补充 job51_config AI 字段失败: {}", e.getMessage());
        }
    }

    private void ensureJob51OptionTable() {
        String createSql = "CREATE TABLE IF NOT EXISTS job51_option (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " type VARCHAR(50)," +
                " name VARCHAR(100)," +
                " code VARCHAR(100)," +
                " sort_order INTEGER," +
                " created_at DATETIME," +
                " updated_at DATETIME" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);
        } catch (Exception e) {
            log.warn("创建 job51_option 表失败: {}", e.getMessage());
        }
    }

    // 初始化逻辑移除：数据由外部迁移并在数据库维护，无需自动填充

    private void insertOption(String type, String name, String code, int sortOrder, LocalDateTime now) {
        try {
            Job51OptionEntity e = new Job51OptionEntity();
            e.setType(type);
            e.setName(name);
            e.setCode(code);
            e.setSortOrder(sortOrder);
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            job51OptionMapper.insert(e);
        } catch (Exception ex) {
            log.warn("写入选项失败 type={} name={} code={}: {}", type, name, code, ex.getMessage());
        }
    }

    /**
     * 选择性更新：若传入 ID 则按 ID 更新；否则更新第一条记录（不存在则插入）
     */
    public Job51ConfigEntity updateConfig(Job51ConfigEntity config) {
        if (config == null) return null;
        validateConfig(config);
        if (config.getId() != null) {
            // 设置更新时间
            config.setUpdatedAt(java.time.LocalDateTime.now());
            job51ConfigMapper.updateById(config);
            return job51ConfigMapper.selectById(config.getId());
        }
        return saveOrUpdateFirstSelective(config);
    }

    /**
     * 保存或选择性更新第一条记录（仅覆盖非空字段）
     */
    public Job51ConfigEntity saveOrUpdateFirstSelective(Job51ConfigEntity incoming) {
        validateConfig(incoming);
        Job51ConfigEntity first = getFirstConfig();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (first == null) {
            Job51ConfigEntity toInsert = new Job51ConfigEntity();
            toInsert.setKeywords(incoming.getKeywords());
            toInsert.setSearchProfile(incoming.getSearchProfile());
            toInsert.setJobArea(incoming.getJobArea());
            toInsert.setSalary(incoming.getSalary());
            toInsert.setEnableAi(incoming.getEnableAi() == null ? 0 : incoming.getEnableAi());
            toInsert.setAiMinScore(incoming.getAiMinScore() == null
                    ? Job51Config.DEFAULT_AI_MIN_SCORE : incoming.getAiMinScore());
            toInsert.setMaxPerRun(incoming.getMaxPerRun() == null ? Job51Config.DEFAULT_MAX_PER_RUN : incoming.getMaxPerRun());
            toInsert.setStopAfterMaxPerRun(incoming.getStopAfterMaxPerRun() == null ? 0 : incoming.getStopAfterMaxPerRun());
            toInsert.setMaxPerRunRestMinSeconds(incoming.getMaxPerRunRestMinSeconds() == null
                    ? Job51Config.DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS : incoming.getMaxPerRunRestMinSeconds());
            toInsert.setMaxPerRunRestMaxSeconds(incoming.getMaxPerRunRestMaxSeconds() == null
                    ? Job51Config.DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS : incoming.getMaxPerRunRestMaxSeconds());
            toInsert.setMinDelaySeconds(incoming.getMinDelaySeconds() == null ? Job51Config.DEFAULT_MIN_DELAY_SECONDS : incoming.getMinDelaySeconds());
            toInsert.setMaxDelaySeconds(incoming.getMaxDelaySeconds() == null ? Job51Config.DEFAULT_MAX_DELAY_SECONDS : incoming.getMaxDelaySeconds());
            toInsert.setRestEnabled(incoming.getRestEnabled() == null ? 1 : incoming.getRestEnabled());
            toInsert.setRestStage1MinSeconds(incoming.getRestStage1MinSeconds() == null ? Job51Config.DEFAULT_REST_STAGE1_MIN_SECONDS : incoming.getRestStage1MinSeconds());
            toInsert.setRestStage1MaxSeconds(incoming.getRestStage1MaxSeconds() == null ? Job51Config.DEFAULT_REST_STAGE1_MAX_SECONDS : incoming.getRestStage1MaxSeconds());
            toInsert.setRestStage2MinSeconds(incoming.getRestStage2MinSeconds() == null ? Job51Config.DEFAULT_REST_STAGE2_MIN_SECONDS : incoming.getRestStage2MinSeconds());
            toInsert.setRestStage2MaxSeconds(incoming.getRestStage2MaxSeconds() == null ? Job51Config.DEFAULT_REST_STAGE2_MAX_SECONDS : incoming.getRestStage2MaxSeconds());
            toInsert.setRestStage3MinSeconds(incoming.getRestStage3MinSeconds() == null ? Job51Config.DEFAULT_REST_STAGE3_MIN_SECONDS : incoming.getRestStage3MinSeconds());
            toInsert.setRestStage3MaxSeconds(incoming.getRestStage3MaxSeconds() == null ? Job51Config.DEFAULT_REST_STAGE3_MAX_SECONDS : incoming.getRestStage3MaxSeconds());
            toInsert.setRestMaxConsecutive(incoming.getRestMaxConsecutive() == null ? Job51Config.DEFAULT_REST_MAX_CONSECUTIVE : incoming.getRestMaxConsecutive());
            toInsert.setCreatedAt(now);
            toInsert.setUpdatedAt(now);
            job51ConfigMapper.insert(toInsert);
            return getFirstConfig();
        } else {
            Job51ConfigEntity toUpdate = new Job51ConfigEntity();
            toUpdate.setId(first.getId());
            // 仅覆盖非空字段
            if (incoming.getKeywords() != null) toUpdate.setKeywords(incoming.getKeywords());
            if (incoming.getSearchProfile() != null) toUpdate.setSearchProfile(incoming.getSearchProfile());
            if (incoming.getJobArea() != null) toUpdate.setJobArea(incoming.getJobArea());
            if (incoming.getSalary() != null) toUpdate.setSalary(incoming.getSalary());
            if (incoming.getEnableAi() != null) toUpdate.setEnableAi(incoming.getEnableAi());
            if (incoming.getAiMinScore() != null) toUpdate.setAiMinScore(incoming.getAiMinScore());
            if (incoming.getMaxPerRun() != null) toUpdate.setMaxPerRun(incoming.getMaxPerRun());
            if (incoming.getStopAfterMaxPerRun() != null) toUpdate.setStopAfterMaxPerRun(incoming.getStopAfterMaxPerRun());
            if (incoming.getMaxPerRunRestMinSeconds() != null) toUpdate.setMaxPerRunRestMinSeconds(incoming.getMaxPerRunRestMinSeconds());
            if (incoming.getMaxPerRunRestMaxSeconds() != null) toUpdate.setMaxPerRunRestMaxSeconds(incoming.getMaxPerRunRestMaxSeconds());
            if (incoming.getMinDelaySeconds() != null) toUpdate.setMinDelaySeconds(incoming.getMinDelaySeconds());
            if (incoming.getMaxDelaySeconds() != null) toUpdate.setMaxDelaySeconds(incoming.getMaxDelaySeconds());
            if (incoming.getRestEnabled() != null) toUpdate.setRestEnabled(incoming.getRestEnabled());
            if (incoming.getRestStage1MinSeconds() != null) toUpdate.setRestStage1MinSeconds(incoming.getRestStage1MinSeconds());
            if (incoming.getRestStage1MaxSeconds() != null) toUpdate.setRestStage1MaxSeconds(incoming.getRestStage1MaxSeconds());
            if (incoming.getRestStage2MinSeconds() != null) toUpdate.setRestStage2MinSeconds(incoming.getRestStage2MinSeconds());
            if (incoming.getRestStage2MaxSeconds() != null) toUpdate.setRestStage2MaxSeconds(incoming.getRestStage2MaxSeconds());
            if (incoming.getRestStage3MinSeconds() != null) toUpdate.setRestStage3MinSeconds(incoming.getRestStage3MinSeconds());
            if (incoming.getRestStage3MaxSeconds() != null) toUpdate.setRestStage3MaxSeconds(incoming.getRestStage3MaxSeconds());
            if (incoming.getRestMaxConsecutive() != null) toUpdate.setRestMaxConsecutive(incoming.getRestMaxConsecutive());
            toUpdate.setCreatedAt(first.getCreatedAt());
            toUpdate.setUpdatedAt(now);
            job51ConfigMapper.updateById(toUpdate);
            return job51ConfigMapper.selectById(first.getId());
        }
    }

    private void validateConfig(Job51ConfigEntity config) {
        if (config == null) return;
        if (config.getMaxPerRun() != null && config.getMaxPerRun() < 0) {
            throw new IllegalArgumentException("51job每轮成功投递上限必须为0或正整数");
        }
        if (config.getAiMinScore() != null
                && (config.getAiMinScore() < Job51Config.MIN_AI_SCORE
                || config.getAiMinScore() > Job51Config.MAX_AI_SCORE)) {
            throw new IllegalArgumentException("51job AI JD分析通过分数必须在0到100之间");
        }
        int min = config.getMaxPerRunRestMinSeconds() == null
                ? Job51Config.DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS : config.getMaxPerRunRestMinSeconds();
        int max = config.getMaxPerRunRestMaxSeconds() == null
                ? Job51Config.DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS : config.getMaxPerRunRestMaxSeconds();
        if (min < Job51Config.MIN_MAX_PER_RUN_REST_SECONDS
                || max < min || max > Job51Config.MAX_MAX_PER_RUN_REST_SECONDS) {
            throw new IllegalArgumentException("51job上限后的短休息需满足30<=最小秒数<=最大秒数<=300");
        }
        int minDelay = config.getMinDelaySeconds() == null
                ? Job51Config.DEFAULT_MIN_DELAY_SECONDS : config.getMinDelaySeconds();
        int maxDelay = config.getMaxDelaySeconds() == null
                ? Job51Config.DEFAULT_MAX_DELAY_SECONDS : config.getMaxDelaySeconds();
        if (minDelay < Job51Config.MIN_DELAY_SECONDS
                || maxDelay < minDelay || maxDelay > Job51Config.MAX_DELAY_SECONDS) {
            throw new IllegalArgumentException("51job AI岗位间隔需满足20<=最小秒数<=最大秒数<=300");
        }
    }

    // ==================== 51job 岗位数据表与持久化 ====================

    /** 创建 job51_data 表（如不存在） */
    private void ensureJob51DataTable() {
        String createSql = "CREATE TABLE IF NOT EXISTS job51_data (" +
                " job_id            BIGINT PRIMARY KEY," +
                " job_title         VARCHAR(200)," +
                " job_link          VARCHAR(300)," +
                " job_description   TEXT," +
                " job_salary_text   VARCHAR(100)," +
                " job_area          VARCHAR(100)," +
                " job_edu_req       VARCHAR(50)," +
                " job_exp_req       VARCHAR(50)," +
                " job_publish_time  VARCHAR(50)," +
                " comp_id           BIGINT," +
                " comp_name         VARCHAR(200)," +
                " comp_industry     VARCHAR(100)," +
                " comp_scale        VARCHAR(50)," +
                " hr_id             VARCHAR(64)," +
                " hr_name           VARCHAR(50)," +
                " hr_title          VARCHAR(100)," +
                " delivered         INTEGER DEFAULT 0," +
                " greeting_status   VARCHAR(32) DEFAULT 'NOT_ATTEMPTED'," +
                " greeting_error    TEXT," +
                " greeting_time    TEXT," +
                " screening_status VARCHAR(40)," +
                " screening_reason TEXT," +
                " screening_time   TEXT," +
                " application_route VARCHAR(32) DEFAULT 'INTERNAL'," +
                " application_url  VARCHAR(500)," +
                " external_apply_status VARCHAR(32)," +
                " external_apply_time TEXT," +
                " create_time       TEXT," +
                " update_time       TEXT" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);
            // 兼容旧库：添加 delivered 列（已存在则忽略）
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN delivered INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN job_description TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN screening_status VARCHAR(40)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN screening_reason TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN screening_time TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN greeting_status VARCHAR(32) DEFAULT 'NOT_ATTEMPTED'"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN greeting_error TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN greeting_time TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN application_route VARCHAR(32) DEFAULT 'INTERNAL'"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN application_url VARCHAR(500)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN external_apply_status VARCHAR(32)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE job51_data ADD COLUMN external_apply_time TEXT"); } catch (Exception ignored) {}
            // 兼容旧库：尝试移除 account_id（已不存在或不支持则忽略）
            try { stmt.execute("ALTER TABLE job51_data DROP COLUMN account_id"); } catch (Exception ignored) {}
            log.info("确保 job51_data 表已存在");
        } catch (Exception e) {
            log.warn("创建 job51_data 表失败: {}", e.getMessage());
        }
    }

    /** 批量插入（仅不存在时），默认 delivered=0 */
    public void batchInsertIfNotExists(List<Job51Entity> entities) {
        if (entities == null || entities.isEmpty()) return;

        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Job51Entity e : entities) {
            if (e != null && e.getJobId() != null) ids.add(e.getJobId());
        }
        if (ids.isEmpty()) return;

        java.util.List<Long> idList = new java.util.ArrayList<>(ids);
        java.util.List<Job51Entity> existing = job51Mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Job51Entity>().in("job_id", idList)
        );
        java.util.Set<Long> existingIds = new java.util.HashSet<>();
        Map<Long, Job51Entity> existingById = new LinkedHashMap<>();
        if (existing != null) {
            for (Job51Entity e : existing) {
                if (e != null && e.getJobId() != null) {
                    existingIds.add(e.getJobId());
                    existingById.put(e.getJobId(), e);
                }
            }
        }

        java.util.List<Job51Entity> toInsert = new java.util.ArrayList<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String nowIso = now.toString();
        for (Job51Entity e : entities) {
            if (e == null || e.getJobId() == null) continue;
            if (existingIds.contains(e.getJobId())) continue;
            if (e.getCreateTime() == null) e.setCreateTime(nowIso);
            e.setUpdateTime(nowIso);
            if (e.getDelivered() == null) e.setDelivered(0);
            toInsert.add(e);
        }
        mergeExistingSnapshots(entities, existingById);
        if (toInsert.isEmpty()) return;

        String sql = "INSERT INTO job51_data (" +
                "job_id, job_title, job_link, job_description, job_salary_text, job_area, job_edu_req, job_exp_req, job_publish_time, " +
                "comp_id, comp_name, comp_industry, comp_scale, " +
                "hr_id, hr_name, hr_title, delivered, greeting_status, application_route, application_url, " +
                "external_apply_status, external_apply_time, create_time, update_time" +
                ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = dataSource.getConnection(); java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (Job51Entity e : toInsert) {
                if (e.getJobId() == null) ps.setNull(1, java.sql.Types.BIGINT); else ps.setLong(1, e.getJobId());
                if (e.getJobTitle() == null) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, e.getJobTitle());
                if (e.getJobLink() == null) ps.setNull(3, java.sql.Types.VARCHAR); else ps.setString(3, e.getJobLink());
                if (e.getJobDescription() == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, e.getJobDescription());
                if (e.getJobSalaryText() == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, e.getJobSalaryText());
                if (e.getJobArea() == null) ps.setNull(6, java.sql.Types.VARCHAR); else ps.setString(6, e.getJobArea());
                if (e.getJobEduReq() == null) ps.setNull(7, java.sql.Types.VARCHAR); else ps.setString(7, e.getJobEduReq());
                if (e.getJobExpReq() == null) ps.setNull(8, java.sql.Types.VARCHAR); else ps.setString(8, e.getJobExpReq());
                if (e.getJobPublishTime() == null) ps.setNull(9, java.sql.Types.VARCHAR); else ps.setString(9, e.getJobPublishTime());
                if (e.getCompId() == null) ps.setNull(10, java.sql.Types.BIGINT); else ps.setLong(10, e.getCompId());
                if (e.getCompName() == null) ps.setNull(11, java.sql.Types.VARCHAR); else ps.setString(11, e.getCompName());
                if (e.getCompIndustry() == null) ps.setNull(12, java.sql.Types.VARCHAR); else ps.setString(12, e.getCompIndustry());
                if (e.getCompScale() == null) ps.setNull(13, java.sql.Types.VARCHAR); else ps.setString(13, e.getCompScale());
                if (e.getHrId() == null) ps.setNull(14, java.sql.Types.VARCHAR); else ps.setString(14, e.getHrId());
                if (e.getHrName() == null) ps.setNull(15, java.sql.Types.VARCHAR); else ps.setString(15, e.getHrName());
                if (e.getHrTitle() == null) ps.setNull(16, java.sql.Types.VARCHAR); else ps.setString(16, e.getHrTitle());
                if (e.getDelivered() == null) ps.setNull(17, java.sql.Types.INTEGER); else ps.setInt(17, e.getDelivered());
                if (e.getGreetingStatus() == null) ps.setNull(18, java.sql.Types.VARCHAR); else ps.setString(18, e.getGreetingStatus());
                ps.setString(19, applicationRoute(e.getApplicationRoute()));
                if (e.getApplicationUrl() == null) ps.setNull(20, java.sql.Types.VARCHAR); else ps.setString(20, e.getApplicationUrl());
                if (e.getExternalApplyStatus() == null) ps.setNull(21, java.sql.Types.VARCHAR); else ps.setString(21, e.getExternalApplyStatus());
                if (e.getExternalApplyTime() == null) ps.setNull(22, java.sql.Types.VARCHAR); else ps.setString(22, e.getExternalApplyTime());
                if (e.getCreateTime() == null) ps.setNull(23, java.sql.Types.VARCHAR); else ps.setString(23, e.getCreateTime());
                if (e.getUpdateTime() == null) ps.setNull(24, java.sql.Types.VARCHAR); else ps.setString(24, e.getUpdateTime());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (Exception e) {
            log.warn("批量插入 51job 岗位快照失败: {}", e.getMessage());
        }
    }

    /** 补齐旧快照缺少的 JD/链接，或替换生成链接，不触碰投递和打招呼状态。 */
    private void mergeExistingSnapshots(List<Job51Entity> incoming, Map<Long, Job51Entity> existingById) {
        if (incoming == null || incoming.isEmpty() || existingById == null || existingById.isEmpty()) return;
        try (Connection conn = dataSource.getConnection()) {
            String now = LocalDateTime.now().toString();
            for (Job51Entity next : incoming) {
                if (next == null || next.getJobId() == null) continue;
                Job51Entity current = existingById.get(next.getJobId());
                if (current == null) continue;

                boolean fillDescription = isBlank(current.getJobDescription())
                        && !isBlank(next.getJobDescription());
                boolean fillLink = isBetterJobLink(current.getJobLink(), next.getJobLink());
                boolean incomingExternal = isExternalApplication(next);
                boolean fillRoute = incomingExternal
                        && !APPLICATION_ROUTE_EXTERNAL.equalsIgnoreCase(current.getApplicationRoute());
                boolean fillApplicationUrl = incomingExternal && !isBlank(next.getApplicationUrl())
                        && !next.getApplicationUrl().equals(current.getApplicationUrl());
                boolean fillExternalStatus = incomingExternal && isBlank(current.getExternalApplyStatus());
                if (!fillDescription && !fillLink && !fillRoute && !fillApplicationUrl && !fillExternalStatus) continue;

                StringBuilder sql = new StringBuilder("UPDATE job51_data SET ");
                List<Object> values = new ArrayList<>();
                if (fillDescription) {
                    sql.append("job_description=?,");
                    values.add(next.getJobDescription().trim());
                }
                if (fillLink) {
                    sql.append("job_link=?,");
                    values.add(next.getJobLink().trim());
                }
                if (fillRoute) {
                    sql.append("application_route=?,");
                    values.add(APPLICATION_ROUTE_EXTERNAL);
                }
                if (fillApplicationUrl) {
                    sql.append("application_url=?,");
                    values.add(next.getApplicationUrl().trim());
                }
                if (fillExternalStatus) {
                    sql.append("external_apply_status=?,");
                    values.add(EXTERNAL_APPLY_PENDING);
                }
                sql.append("update_time=? WHERE job_id=?");
                values.add(now);
                values.add(next.getJobId());

                try (java.sql.PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < values.size(); i++) {
                        Object value = values.get(i);
                        if (value instanceof Long id) ps.setLong(i + 1, id);
                        else ps.setString(i + 1, String.valueOf(value));
                    }
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            log.warn("合并 51job 岗位快照失败: {}", e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean isExternalApplicationRoute(String link) {
        if (isBlank(link)) return false;
        try {
            String normalized = normalizeApplicationUrl(link);
            java.net.URI uri = new java.net.URI(normalized);
            String host = uri.getHost();
            return host != null && !host.equalsIgnoreCase("jobs.51job.com")
                    && !host.equalsIgnoreCase("we.51job.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String normalizeApplicationUrl(String link) {
        if (isBlank(link)) return null;
        String value = link.trim();
        if (value.startsWith("//")) return "https:" + value;
        return value.startsWith("http://") || value.startsWith("https://") ? value : value;
    }

    private static String applicationRoute(String route) {
        return APPLICATION_ROUTE_EXTERNAL.equalsIgnoreCase(route)
                ? APPLICATION_ROUTE_EXTERNAL : APPLICATION_ROUTE_INTERNAL;
    }

    public static boolean isExternalApplication(Job51Entity entity) {
        return entity != null && (APPLICATION_ROUTE_EXTERNAL.equalsIgnoreCase(entity.getApplicationRoute())
                || isExternalApplicationRoute(entity.getApplicationUrl())
                || isExternalApplicationRoute(entity.getJobLink()));
    }

    public static String externalApplyStatus(Job51Entity entity) {
        if (!isExternalApplication(entity)) return null;
        return EXTERNAL_APPLY_APPLIED.equalsIgnoreCase(entity.getExternalApplyStatus())
                ? EXTERNAL_APPLY_APPLIED : EXTERNAL_APPLY_PENDING;
    }

    /** 旧快照可能保留内部/生成链接；搜索接口拿到真实详情链接后应更新它。 */
    private static boolean isBetterJobLink(String current, String incoming) {
        if (isBlank(incoming)) return false;
        if (isBlank(current)) return true;
        return isGeneratedJobLink(current) && !isGeneratedJobLink(incoming);
    }

    private static boolean isGeneratedJobLink(String link) {
        if (isBlank(link)) return true;
        String normalized = link.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("we.51job.com/pc/jobdetail")
                || normalized.matches("https?://jobs\\.51job\\.com/all/\\d+\\.html");
    }

    /** 解析 51job 搜索接口返回 JSON，并批量保存 */
    public void parseAndPersistJob51SearchJson(String json) {
        try {
            List<Job51Entity> entities = parseSearchEntities(json);
            batchInsertIfNotExists(entities);
        } catch (Exception e) {
            log.warn("解析 51job 搜索 JSON 失败: {}", e.getMessage());
        }
    }

    /** 将搜索接口的多种列表形态统一成岗位快照，供生产代码和回归测试复用。 */
    static List<Job51Entity> parseSearchEntities(String json) {
        List<Job51Entity> entities = new ArrayList<>();
        if (json == null || json.isBlank() || json.trim().startsWith("<")) return entities;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode list = findJobList(root);
            if (!list.isArray()) return entities;

            for (com.fasterxml.jackson.databind.JsonNode item : list) {
                Long jobId = readJobId(item);
                if (jobId == null) continue;

                Job51Entity entity = new Job51Entity();
                entity.setJobId(jobId);
                entity.setJobTitle(readFirstText(item, "jobName", "jobTitle", "title"));
                entity.setJobDescription(readFirstText(item,
                        "jobDescribe", "jobDescription", "jobDesc", "postDescription", "description",
                        "jobIntro", "jobDuty", "jobRequirement", "requirement"));
                entity.setJobSalaryText(readFirstText(item,
                        "provideSalaryString", "salaryDesc", "salary", "salaryText"));
                entity.setJobArea(readFirstText(item, "jobAreaString", "jobArea", "cityName"));
                entity.setJobEduReq(readFirstText(item, "degreeString", "degree", "requireEduLevel"));
                entity.setJobExpReq(readFirstText(item, "workYearString", "workYear", "requireWorkYears"));
                entity.setJobPublishTime(readFirstText(item,
                        "issueDateString", "issueDate", "updateDate", "refreshTime"));

                entity.setCompId(readLong(item.path("ctmId"), item.path("companyId"),
                        item.path("company").path("companyId"), item.path("companyInfo").path("companyId")));
                entity.setCompName(readFirstText(item, "fullCompanyName", "companyName", "ctmName"));
                entity.setCompIndustry(readFirstText(item, "industryType1Str", "industry", "compIndustry"));
                entity.setCompScale(readFirstText(item, "companySizeString", "companySize", "compScale"));

                entity.setHrId(readFirstText(item, "hrUid", "recruiterId"));
                entity.setHrName(readFirstText(item, "hrName", "recruiterName"));
                entity.setHrTitle(readFirstText(item, "hrPosition", "recruiterTitle"));

                String jobHref = readFirstText(item, "jobHref", "jobLink", "jobUrl", "url");
                entity.setJobLink(jobHref == null
                        ? "https://jobs.51job.com/all/" + jobId + ".html"
                        : jobHref);
                if (isExternalApplicationRoute(jobHref)) {
                    entity.setApplicationRoute(APPLICATION_ROUTE_EXTERNAL);
                    entity.setApplicationUrl(normalizeApplicationUrl(jobHref));
                    entity.setExternalApplyStatus(EXTERNAL_APPLY_PENDING);
                } else {
                    entity.setApplicationRoute(APPLICATION_ROUTE_INTERNAL);
                }
                entities.add(entity);
            }
        } catch (Exception e) {
            log.warn("构造 51job 搜索岗位快照失败: {}", e.getMessage());
        }
        return entities;
    }

    private static com.fasterxml.jackson.databind.JsonNode findJobList(
            com.fasterxml.jackson.databind.JsonNode root) {
        String[][] paths = {
                {"data", "items"}, {"data", "jobList"}, {"data", "list"}, {"data", "jobs"},
                {"data", "results"}, {"data", "resultbody", "job", "items"},
                {"data", "resultbody", "items"}, {"data", "job", "items"}, {"data", "job"},
                {"resultbody", "job", "items"}, {"resultbody", "jobList"}, {"resultbody", "items"},
                {"job", "items"}, {"items"}, {"list"}, {"results"}
        };
        for (String[] path : paths) {
            com.fasterxml.jackson.databind.JsonNode node = root;
            for (String part : path) node = node.path(part);
            if (node.isArray()) return node;
        }
        return findArrayByKey(root);
    }

    private static com.fasterxml.jackson.databind.JsonNode findArrayByKey(
            com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(java.util.Locale.ROOT).replaceAll("[_-]", "");
                com.fasterxml.jackson.databind.JsonNode value = field.getValue();
                if ((key.equals("items") || key.equals("joblist") || key.equals("jobs")
                        || key.equals("results") || key.equals("list")) && value.isArray()) {
                    return value;
                }
            }
            fields = node.fields();
            while (fields.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode nested = findArrayByKey(fields.next().getValue());
                if (nested != null && nested.isArray()) return nested;
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                com.fasterxml.jackson.databind.JsonNode nested = findArrayByKey(child);
                if (nested != null && nested.isArray()) return nested;
            }
        }
        return node.path("missing");
    }

    private static Long readJobId(com.fasterxml.jackson.databind.JsonNode item) {
        if (item == null || item.isNull() || item.isMissingNode()) return null;
        String[] directKeys = {"jobId", "job_id", "jobID", "jobid", "jobIdStr"};
        Long direct = readLongFromKeys(item, directKeys);
        if (direct != null) return direct;
        String[] nestedKeys = {"jobInfo", "jobDetail", "job", "detail", "position", "jobData"};
        for (String key : nestedKeys) {
            Long nested = readJobId(item.path(key));
            if (nested != null) return nested;
        }
        return null;
    }

    private static Long readLongFromKeys(com.fasterxml.jackson.databind.JsonNode item, String... keys) {
        for (String key : keys) {
            Long value = readLong(item.path(key));
            if (value != null && value > 0) return value;
        }
        return null;
    }

    private static String readFirstText(com.fasterxml.jackson.databind.JsonNode item, String... keys) {
        String direct = readText(item, keys);
        if (direct != null) return direct;
        String[] nested = {"jobInfo", "jobDetail", "job", "detail", "position", "jobData",
                "company", "companyInfo", "enterprise", "recruiter"};
        for (String name : nested) {
            com.fasterxml.jackson.databind.JsonNode child = item.path(name);
            if (child.isObject()) {
                String value = readText(child, keys);
                if (value != null) return value;
            }
        }
        return null;
    }

    // 读取辅助
    private static String readText(com.fasterxml.jackson.databind.JsonNode item, String... keys) {
        for (String k : keys) {
            String v = safeText(item.path(k));
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String safeText(com.fasterxml.jackson.databind.JsonNode node) {
        try {
            String v = node.asText(null);
            return (v == null || v.equals("null") || v.isBlank()) ? null : v.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long readLong(com.fasterxml.jackson.databind.JsonNode... nodes) {
        for (com.fasterxml.jackson.databind.JsonNode n : nodes) {
            try {
                if (n == null) continue;
                String v = n.asText(null);
                if (v != null && !v.isEmpty() && !"null".equalsIgnoreCase(v)) {
                    long value = Long.parseLong(v.trim());
                    return value > 0 ? value : null;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ==================== 投递状态写回 ====================

    /** 将指定 jobId 标记为已投递 */
    public void markDelivered(Long jobId) {
        if (jobId == null) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET delivered=1, update_time=? WHERE job_id=?")) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            ps.setString(1, now.toString());
            ps.setLong(2, jobId);
            boolean updated = ps.executeUpdate() > 0;
            if (updated && jobFunnelService != null) {
                jobFunnelService.formalApplySuccess("51job", jobId);
                jobFunnelService.clearRetryable("51job", jobId);
            }
        } catch (Exception e) {
            log.warn("标记 51job 已投递失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    public Job51Entity findByJobId(Long jobId) {
        return jobId == null ? null : job51Mapper.selectById(jobId);
    }

    public void updateJobDescription(Long jobId, String description) {
        if (jobId == null || description == null || description.isBlank()) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET job_description=?, update_time=? WHERE job_id=?")) {
            ps.setString(1, description.trim());
            ps.setString(2, LocalDateTime.now().toString());
            ps.setLong(3, jobId);
            boolean updated = ps.executeUpdate() > 0;
            if (updated && jobFunnelService != null) jobFunnelService.jd("51job", jobId);
        } catch (Exception e) {
            log.warn("保存 51job 岗位 JD 失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    public void updateJobLink(Long jobId, String link) {
        if (jobId == null || link == null || link.isBlank()) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET job_link=?, update_time=? WHERE job_id=?")) {
            ps.setString(1, link.trim());
            ps.setString(2, LocalDateTime.now().toString());
            ps.setLong(3, jobId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("保存 51job 岗位详情链接失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    /** 将岗位放入外部申请待办；重复识别只刷新链接，不覆盖已完成状态。 */
    public void markExternalApplicationPending(Long jobId, String link) {
        if (jobId == null || isBlank(link)) return;
        String normalized = normalizeApplicationUrl(link);
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET application_route=?, application_url=?, " +
                             "external_apply_status=CASE WHEN external_apply_status=? THEN external_apply_status ELSE ? END, " +
                             "update_time=? WHERE job_id=?")) {
            String now = LocalDateTime.now().toString();
            ps.setString(1, APPLICATION_ROUTE_EXTERNAL);
            ps.setString(2, normalized);
            ps.setString(3, EXTERNAL_APPLY_APPLIED);
            ps.setString(4, EXTERNAL_APPLY_PENDING);
            ps.setString(5, now);
            ps.setLong(6, jobId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("保存外部申请待办失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    /** 手动确认外部岗位已申请，幂等且只作用于外部申请路由。 */
    public boolean markExternalApplied(Long jobId) {
        Job51Entity current = findByJobId(jobId);
        if (!isExternalApplication(current)) return false;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET application_route=?, external_apply_status=?, " +
                             "external_apply_time=?, update_time=? WHERE job_id=?")) {
            String now = LocalDateTime.now().toString();
            ps.setString(1, APPLICATION_ROUTE_EXTERNAL);
            ps.setString(2, EXTERNAL_APPLY_APPLIED);
            ps.setString(3, now);
            ps.setString(4, now);
            ps.setLong(5, jobId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("标记外部岗位已申请失败 job_id={}: {}", jobId, e.getMessage());
            return false;
        }
    }

    public void updateGreetingState(Long jobId, String status, String error) {
        if (jobId == null || status == null || status.isBlank()) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET greeting_status=?, greeting_error=?, greeting_time=?, update_time=? WHERE job_id=?")) {
            String now = LocalDateTime.now().toString();
            ps.setString(1, status);
            ps.setString(2, error == null || error.isBlank() ? null : error.trim());
            ps.setString(3, now);
            ps.setString(4, now);
            ps.setLong(5, jobId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("更新 51job 打招呼状态失败 job_id={} status={}: {}", jobId, status, e.getMessage());
        }
    }

    public void markScreeningStatus(Long jobId, String status, String reason) {
        if (jobId == null || status == null || status.isBlank()) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET screening_status=?, screening_reason=?, screening_time=?, update_time=? WHERE job_id=?")) {
            String now = LocalDateTime.now().toString();
            ps.setString(1, status.trim());
            ps.setString(2, reason == null || reason.isBlank() ? null : reason.trim());
            ps.setString(3, now);
            ps.setString(4, now);
            ps.setLong(5, jobId);
            ps.executeUpdate();
            if (jobFunnelService != null) {
                if ("REVIEW".equalsIgnoreCase(status)) jobFunnelService.review("51job", jobId, reason);
                if ("AI_FAILURE_RETRYABLE".equalsIgnoreCase(status)) jobFunnelService.retryable("51job", jobId, reason);
            }
        } catch (Exception e) {
            log.warn("更新 51job 筛选状态失败 job_id={} status={}: {}", jobId, status, e.getMessage());
        }
    }

    public List<Job51Entity> listByScreeningStatus(String status) {
        QueryWrapper<Job51Entity> wrapper = new QueryWrapper<>();
        if (status != null && !status.isBlank()) wrapper.eq("screening_status", status.trim());
        wrapper.orderByDesc("update_time");
        return job51Mapper.selectList(wrapper);
    }

    public boolean retryScreening(Long jobId) {
        Job51Entity current = findByJobId(jobId);
        if (current == null || !("REVIEW".equalsIgnoreCase(current.getScreeningStatus())
                || "AI_FAILURE_RETRYABLE".equalsIgnoreCase(current.getScreeningStatus()))) return false;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET screening_status=NULL, screening_reason=NULL, screening_time=NULL, "
                             + "greeting_status='NOT_ATTEMPTED', greeting_error=NULL, update_time=? WHERE job_id=?")) {
            ps.setString(1, LocalDateTime.now().toString());
            ps.setLong(2, jobId);
            boolean updated = ps.executeUpdate() > 0;
            if (updated && jobFunnelService != null) jobFunnelService.clearRetryable("51job", jobId);
            return updated;
        } catch (Exception e) {
            log.warn("重置 51job 待处理状态失败 job_id={}: {}", jobId, e.getMessage());
            return false;
        }
    }

    /** 批量标记为已投递 */
    public void markDeliveredBatch(java.util.Collection<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE job51_data SET delivered=1, update_time=? WHERE job_id=?")) {
            conn.setAutoCommit(false);
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            List<Long> persistedIds = new ArrayList<>();
            for (Long id : jobIds) {
                if (id == null) continue;
                persistedIds.add(id);
                ps.setString(1, now.toString());
                ps.setLong(2, id);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            conn.commit();
            int updated = 0;
            for (int i = 0; i < persistedIds.size() && i < counts.length; i++) {
                int count = counts[i];
                if (count > 0 || count == Statement.SUCCESS_NO_INFO) {
                    Long id = persistedIds.get(i);
                    updated++;
                    if (jobFunnelService != null) {
                        jobFunnelService.formalApplySuccess("51job", id);
                        jobFunnelService.clearRetryable("51job", id);
                    }
                }
            }
            try {
                String sample = jobIds.stream().filter(java.util.Objects::nonNull).limit(5)
                        .map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
                log.info("[51job] 批量标记已投递完成，入参 {} 条，成功更新 {} 条，示例ID: {}",
                        jobIds.size(), updated, sample);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.warn("批量标记 51job 已投递失败: {}", e.getMessage());
        }
    }

    // ==================== 投递分析与列表 ====================

    public static class NameValue { public String name; public long value; public NameValue() {} public NameValue(String name, long value) { this.name = name; this.value = value; } }
    public static class BucketValue { public String bucket; public long value; public BucketValue() {} public BucketValue(String bucket, long value) { this.bucket = bucket; this.value = value; } }
    public static class Charts {
        public java.util.List<NameValue> byStatus;
        public java.util.List<NameValue> byCity;
        public java.util.List<NameValue> byIndustry;
        public java.util.List<NameValue> byCompany;
        public java.util.List<NameValue> byExperience;
        public java.util.List<NameValue> byDegree;
        public java.util.List<BucketValue> salaryBuckets;
        public java.util.List<NameValue> dailyTrend; // date as name
    }
    public static class Kpi {
        public long total;
        public long delivered;
        public long pending;
        public long externalPending;
        public long externalApplied;
        public long filtered;
        public long failed;
        public Double avgMonthlyK;
    }
    public static class StatsResponse { public Kpi kpi; public Charts charts; }

    public static class Job51Row {
        public Long jobId;
        public String companyName;
        public String jobName;
        public String salary;
        public String location;
        public String experience;
        public String degree;
        public String hrName;
        public String deliveryStatus; // 已投递/未投递
        public String jobUrl;
        public String publishTime;
        public String createdAt;
        public String industry;
        public String companyScale;
        public String greetingStatus;
        public String greetingError;
        public String greetingTime;
        public String applicationRoute;
        public String applicationUrl;
        public String externalApplyStatus;
        public String externalApplyTime;
    }
    public static class PagedResult51 {
        public java.util.List<Job51Row> items;
        public long total;
        public int page;
        public int size;
    }

    private boolean matchesStatus(Job51Entity entity, java.util.List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return true;
        boolean external = isExternalApplication(entity);
        String externalStatus = externalApplyStatus(entity);
        for (String status : statuses) {
            if (external && "待外部申请".equals(status) && EXTERNAL_APPLY_PENDING.equals(externalStatus)) return true;
            if (external && "外部已申请".equals(status) && EXTERNAL_APPLY_APPLIED.equals(externalStatus)) return true;
            if (!external && "已投递".equals(status) && entity.getDelivered() != null && entity.getDelivered() == 1) return true;
            if (!external && "未投递".equals(status) && (entity.getDelivered() == null || entity.getDelivered() == 0)) return true;
        }
        return false;
    }

    private String statusLabel(Job51Entity entity) {
        if (isExternalApplication(entity)) {
            return EXTERNAL_APPLY_APPLIED.equals(externalApplyStatus(entity)) ? "外部已申请" : "待外部申请";
        }
        return entity.getDelivered() != null && entity.getDelivered() == 1 ? "已投递" : "未投递";
    }

    /** 获取 51job 投递分析统计与图表数据（按筛选条件） */
    public StatsResponse getJob51Stats(
            java.util.List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword
    ) {
        StatsResponse resp = new StatsResponse();
        resp.kpi = new Kpi();
        Charts charts = new Charts();
        charts.byStatus = new java.util.ArrayList<>();
        charts.byCity = new java.util.ArrayList<>();
        charts.byIndustry = new java.util.ArrayList<>();
        charts.byCompany = new java.util.ArrayList<>();
        charts.byExperience = new java.util.ArrayList<>();
        charts.byDegree = new java.util.ArrayList<>();
        charts.salaryBuckets = new java.util.ArrayList<>();
        charts.dailyTrend = new java.util.ArrayList<>();

        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Job51Entity> wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            if (location != null && !location.trim().isEmpty()) wrapper.eq("job_area", location.trim());
            if (experience != null && !experience.trim().isEmpty()) wrapper.eq("job_exp_req", experience.trim());
            if (degree != null && !degree.trim().isEmpty()) wrapper.eq("job_edu_req", degree.trim());
            if (keyword != null && !keyword.trim().isEmpty()) {
                String kw = keyword.trim();
                wrapper.and(w -> w.like("comp_name", kw).or().like("job_title", kw).or().like("hr_name", kw));
            }
            wrapper.orderByDesc("update_time");

            java.util.List<Job51Entity> all = job51Mapper.selectList(wrapper).stream()
                    .filter(entity -> matchesStatus(entity, statuses))
                    .collect(Collectors.toList());

            // 薪资区间过滤与中位数计算
            java.util.List<Job51Entity> filtered = new java.util.ArrayList<>();
            double sumMedian = 0.0; long countMedian = 0;
            java.util.List<Double> medians = new java.util.ArrayList<>();
            for (Job51Entity e : all) {
                SalaryInfo info = parse51Salary(e.getJobSalaryText());
                boolean passSalary;
                if (minK == null && maxK == null) passSalary = true;
                else {
                    if (info == null || info.medianK == null) passSalary = false;
                    else {
                        boolean ok = true;
                        if (minK != null) ok &= (info.medianK >= minK);
                        if (maxK != null) ok &= (info.medianK <= maxK);
                        passSalary = ok;
                    }
                }
                if (passSalary) {
                    filtered.add(e);
                    if (info != null && info.medianK != null) { sumMedian += info.medianK; countMedian++; medians.add(info.medianK); }
                }
            }

            // KPI
            resp.kpi.total = filtered.size();
            resp.kpi.delivered = filtered.stream().filter(e -> !isExternalApplication(e)
                    && e.getDelivered() != null && e.getDelivered() == 1).count();
            resp.kpi.pending = filtered.stream().filter(e -> !isExternalApplication(e)
                    && (e.getDelivered() == null || e.getDelivered() == 0)).count();
            resp.kpi.externalPending = filtered.stream()
                    .filter(e -> EXTERNAL_APPLY_PENDING.equals(externalApplyStatus(e))).count();
            resp.kpi.externalApplied = filtered.stream()
                    .filter(e -> EXTERNAL_APPLY_APPLIED.equals(externalApplyStatus(e))).count();
            resp.kpi.filtered = 0; // 51 无明确“已过滤”
            resp.kpi.failed = 0;   // 51 无明确“投递失败”
            resp.kpi.avgMonthlyK = countMedian > 0 ? Math.round((sumMedian / countMedian) * 100.0) / 100.0 : null;

            // Charts
            java.util.Map<String, Long> byStatus = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(this::statusLabel, java.util.stream.Collectors.counting()));
            byStatus.forEach((k,v) -> charts.byStatus.add(new NameValue(nullSafe(k), v)));

            java.util.Map<String, Long> byCity = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> nullSafe(e.getJobArea()), java.util.stream.Collectors.counting()));
            byCity.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(), a.getValue())).limit(10).forEach(en -> charts.byCity.add(new NameValue(en.getKey(), en.getValue())));

            java.util.Map<String, Long> byIndustry = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> nullSafe(e.getCompIndustry()), java.util.stream.Collectors.counting()));
            byIndustry.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(), a.getValue())).limit(10).forEach(en -> charts.byIndustry.add(new NameValue(en.getKey(), en.getValue())));

            java.util.Map<String, Long> byCompany = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> nullSafe(e.getCompName()), java.util.stream.Collectors.counting()));
            byCompany.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(), a.getValue())).limit(10).forEach(en -> charts.byCompany.add(new NameValue(en.getKey(), en.getValue())));

            java.util.Map<String, Long> byExp = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> nullSafe(e.getJobExpReq()), java.util.stream.Collectors.counting()));
            byExp.forEach((k,v) -> charts.byExperience.add(new NameValue(k,v)));

            java.util.Map<String, Long> byDeg = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> nullSafe(e.getJobEduReq()), java.util.stream.Collectors.counting()));
            byDeg.forEach((k,v) -> charts.byDegree.add(new NameValue(k,v)));

            java.util.Map<String, Long> byDay = filtered.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> {
                        String t = e.getCreateTime();
                        if (t == null || t.length() < 10) return "未知";
                        return t.substring(0,10);
                    }, java.util.stream.Collectors.counting()));
            byDay.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(en -> charts.dailyTrend.add(new NameValue(en.getKey(), en.getValue())));

            // salaryBuckets 动态上限
            long b0_10=0,b10_15=0,b15_20=0,b20_top=0,b_ge_top=0;
            double maxMedian = 0.0;
            for (double m : medians) { if (m > maxMedian) maxMedian = m; }
            int topEdge = (int) Math.ceil(maxMedian / 5.0) * 5; if (topEdge <= 20) topEdge = 25;
            for (double m : medians) {
                if (m < 10) b0_10++;
                else if (m < 15) b10_15++;
                else if (m < 20) b15_20++;
                else if (m < topEdge) b20_top++;
                else b_ge_top++;
            }
            charts.salaryBuckets.add(new BucketValue("0-10K", b0_10));
            charts.salaryBuckets.add(new BucketValue("10-15K", b10_15));
            charts.salaryBuckets.add(new BucketValue("15-20K", b15_20));
            charts.salaryBuckets.add(new BucketValue("20-" + topEdge + "K", b20_top));
            charts.salaryBuckets.add(new BucketValue(">=" + topEdge + "K", b_ge_top));

            resp.charts = charts;
            return resp;
        } catch (Exception e) {
            resp.charts = charts;
            return resp;
        }
    }

    /** 列表查询（分页 + 筛选 + 关键词 + 薪资区间基于中位数K） */
    public PagedResult51 listJob51(
            java.util.List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword,
            int page,
            int size
    ) {
        if (page <= 0) page = 1;
        if (size <= 0) size = 20;

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Job51Entity> wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        if (location != null && !location.trim().isEmpty()) wrapper.eq("job_area", location.trim());
        if (experience != null && !experience.trim().isEmpty()) wrapper.eq("job_exp_req", experience.trim());
        if (degree != null && !degree.trim().isEmpty()) wrapper.eq("job_edu_req", degree.trim());
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("comp_name", kw).or().like("job_title", kw).or().like("hr_name", kw));
        }
        wrapper.orderByDesc("update_time");

        java.util.List<Job51Entity> all = job51Mapper.selectList(wrapper).stream()
                .filter(entity -> matchesStatus(entity, statuses))
                .collect(Collectors.toList());

        java.util.List<Job51Entity> filtered = new java.util.ArrayList<>();
        for (Job51Entity e : all) {
            if (minK == null && maxK == null) { filtered.add(e); }
            else {
                SalaryInfo info = parse51Salary(e.getJobSalaryText());
                if (info == null || info.medianK == null) continue;
                boolean ok = true;
                if (minK != null) ok &= (info.medianK >= minK);
                if (maxK != null) ok &= (info.medianK <= maxK);
                if (ok) filtered.add(e);
            }
        }

        int total = filtered.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        java.util.List<Job51Entity> pageItems = from >= to ? java.util.Collections.emptyList() : filtered.subList(from, to);

        java.util.List<Job51Row> rows = new java.util.ArrayList<>();
        for (Job51Entity e : pageItems) {
            Job51Row r = new Job51Row();
            r.jobId = e.getJobId();
            r.companyName = e.getCompName();
            r.jobName = e.getJobTitle();
            r.salary = e.getJobSalaryText();
            r.location = e.getJobArea();
            r.experience = e.getJobExpReq();
            r.degree = e.getJobEduReq();
            r.hrName = e.getHrName();
            r.deliveryStatus = statusLabel(e);
            r.jobUrl = e.getJobLink();
            r.publishTime = e.getJobPublishTime();
            r.createdAt = e.getCreateTime();
            r.industry = e.getCompIndustry();
            r.companyScale = e.getCompScale();
            r.greetingStatus = e.getGreetingStatus() == null || e.getGreetingStatus().isBlank()
                    ? GREETING_NOT_ATTEMPTED : e.getGreetingStatus();
            r.greetingError = e.getGreetingError();
            r.greetingTime = e.getGreetingTime();
            r.applicationRoute = isExternalApplication(e)
                    ? APPLICATION_ROUTE_EXTERNAL : APPLICATION_ROUTE_INTERNAL;
            r.applicationUrl = isExternalApplication(e)
                    ? (isBlank(e.getApplicationUrl()) ? normalizeApplicationUrl(e.getJobLink()) : e.getApplicationUrl())
                    : null;
            r.externalApplyStatus = externalApplyStatus(e);
            r.externalApplyTime = e.getExternalApplyTime();
            rows.add(r);
        }

        PagedResult51 result = new PagedResult51();
        result.items = rows;
        result.total = total;
        result.page = page;
        result.size = size;
        return result;
    }

    // ==================== 薪资解析 ====================
    private static class SalaryInfo { Double medianK; }
    private SalaryInfo parse51Salary(String salaryText) {
        if (salaryText == null) return null;
        String s = salaryText.trim().toLowerCase();
        if (s.isEmpty() || s.contains("面议")) return null;
        // 提取数字范围（支持小数）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\s*[-~]\s*(\\d+(?:\\.\\d+)?)").matcher(s);
        Double a = null, b = null;
        if (m.find()) {
            a = Double.valueOf(m.group(1));
            b = Double.valueOf(m.group(2));
        } else {
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
            if (m2.find()) {
                a = Double.valueOf(m2.group(1)); b = a;
            }
        }
        if (a == null || b == null) return null;
        double min = Math.min(a, b), max = Math.max(a, b);
        double factorK = 1.0; // 数值单位到K
        // 单位判断
        if (s.contains("k")) factorK = 1.0;
        else if (s.contains("千") && s.contains("/月")) factorK = 1.0;
        else if (s.contains("万") && s.contains("/月")) factorK = 10.0;
        else if (s.contains("万") && (s.contains("/年") || s.contains("年"))) factorK = 10.0 / 12.0;
        else if (s.contains("元/天")) {
            // 粗略换算：按22个工作日，每天X元 -> 月K
            factorK = (1.0 / 1000.0) * 22.0;
        }
        double medianK = ((min + max) / 2.0) * factorK;
        SalaryInfo info = new SalaryInfo(); info.medianK = medianK; return info;
    }

    private String nullSafe(String s) { return (s == null || s.isEmpty()) ? "未知" : s; }

    /** 刷新 51job 数据：执行 VACUUM 并返回当前总数 */
    public java.util.Map<String, Object> reloadJob51Data() {
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            try (Statement st = conn.createStatement()) {
                try { st.execute("PRAGMA wal_checkpoint(TRUNCATE)"); } catch (Exception ignore) {}
                try { st.execute("VACUUM"); } catch (Exception ignore) {}
            }
            long total = scalarCount(conn, "SELECT COUNT(*) FROM job51_data");
            resp.put("success", true);
            resp.put("message", "刷新完成");
            resp.put("total", total);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "刷新失败: " + e.getMessage());
        } finally { try { if (conn != null) conn.close(); } catch (Exception ignore) {} }
        return resp;
    }

    private long scalarCount(Connection conn, String sql) throws Exception {
        try (java.sql.Statement st = conn.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
