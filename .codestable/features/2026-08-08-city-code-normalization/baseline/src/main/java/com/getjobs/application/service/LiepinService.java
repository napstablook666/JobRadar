package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.LiepinEntity;
import com.getjobs.application.entity.LiepinConfigEntity;
import com.getjobs.application.entity.LiepinOptionEntity;
import com.getjobs.application.mapper.LiepinConfigMapper;
import com.getjobs.application.mapper.LiepinOptionMapper;
import com.getjobs.application.mapper.LiepinMapper;
import com.getjobs.worker.liepin.LiepinConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.stream.Collectors;

/**
 * 猎聘数据服务
 * 统一管理所有猎聘相关的数据访问和配置加载
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LiepinService {

    private final LiepinConfigMapper liepinConfigMapper;
    private final LiepinOptionMapper liepinOptionMapper;
    // 记录持久化相关依赖（整合自 LiepinRecordService）
    private final LiepinMapper liepinMapper;
    private final DataSource dataSource;

    // ==================== 记录表初始化与快照保存 ====================

    @PostConstruct
    public void ensureTableExists() {
        String createSql = "CREATE TABLE IF NOT EXISTS liepin_data (" +
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
                " hr_im_id          VARCHAR(64)," +
                " delivered         INTEGER DEFAULT 0," +
                " create_time       DATETIME," +
                " update_time       DATETIME" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);
            // 兼容旧库：尝试添加 delivered 列（如已存在则忽略错误）
            try {
                stmt.execute("ALTER TABLE liepin_data ADD COLUMN delivered INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_data ADD COLUMN job_description TEXT");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN enable_ai INTEGER DEFAULT 1");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN auto_ai_delivery INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN ai_delivery_mode TEXT DEFAULT 'MANUAL'");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN ai_min_score INTEGER DEFAULT 70");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN ai_review_min_score INTEGER DEFAULT 60");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN ai_batch_size INTEGER DEFAULT 5");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN ai_timeout_retry_enabled INTEGER DEFAULT 1");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN ai_timeout_max_retries INTEGER DEFAULT 3");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN ai_timeout_retry_delay_seconds INTEGER DEFAULT 3");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN max_per_run INTEGER DEFAULT 10");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN min_delay_seconds INTEGER DEFAULT 30");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN max_delay_seconds INTEGER DEFAULT 60");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN search_min_delay_seconds INTEGER DEFAULT 10");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN search_max_delay_seconds INTEGER DEFAULT 20");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN page_min_delay_seconds INTEGER DEFAULT 5");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN page_max_delay_seconds INTEGER DEFAULT 10");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN detail_min_delay_seconds INTEGER DEFAULT 8");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN detail_max_delay_seconds INTEGER DEFAULT 15");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN rate_guard_batch_size INTEGER DEFAULT 5");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN batch_cooldown_min_seconds INTEGER DEFAULT 300");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE liepin_config ADD COLUMN batch_cooldown_max_seconds INTEGER DEFAULT 450");
            } catch (Exception ignored) {}
            // 分页断点续跑进度表
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS liepin_page_progress (" +
                            " keyword TEXT NOT NULL," +
                            " city_code TEXT NOT NULL DEFAULT ''," +
                            " salary_code TEXT NOT NULL DEFAULT ''," +
                            " last_completed_page INTEGER NOT NULL," +
                            " updated_at DATETIME," +
                            " PRIMARY KEY (keyword, city_code, salary_code)" +
                            ")"
            );
            // 兼容旧库：尝试移除无数据列（SQLite 3.35+ 支持；不支持则忽略错误）
            try { stmt.execute("ALTER TABLE liepin_data DROP COLUMN job_function"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE liepin_data DROP COLUMN job_city"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE liepin_data DROP COLUMN comp_full_name"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE liepin_data DROP COLUMN comp_kind"); } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE ai ADD COLUMN screen_prompt TEXT");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE ai ADD COLUMN message_prompt TEXT");
            } catch (Exception ignored) {}
            stmt.execute("CREATE TABLE IF NOT EXISTS liepin_ai_screen_cache (" +
                    "job_id BIGINT NOT NULL, input_hash TEXT NOT NULL, model TEXT, " +
                    "score INTEGER, decision TEXT, reason_codes TEXT, reason TEXT, " +
                    "latency_ms INTEGER, updated_at DATETIME, " +
                    "PRIMARY KEY (job_id, input_hash))");
            stmt.execute("CREATE TABLE IF NOT EXISTS liepin_ai_message_cache (" +
                    "job_id BIGINT NOT NULL, input_hash TEXT NOT NULL, model TEXT, " +
                    "message TEXT, latency_ms INTEGER, updated_at DATETIME, " +
                    "PRIMARY KEY (job_id, input_hash))");
            stmt.execute("CREATE TABLE IF NOT EXISTS liepin_retry_queue (" +
                    "job_id BIGINT PRIMARY KEY, retry_reason TEXT NOT NULL, " +
                    "created_at DATETIME, updated_at DATETIME)");
            log.info("确保 liepin_data 表已存在");
        } catch (Exception e) {
            log.warn("创建 liepin_data 表失败: {}", e.getMessage());
        }
    }

    /**
     * 保存或更新一条岗位快照（以 job_id 作为主键）
     */
    public void saveOrUpdateSnapshot(LiepinEntity entity) {
        if (entity == null || entity.getJobId() == null) {
            return;
        }
        try {
            LiepinEntity existing = liepinMapper.selectById(entity.getJobId());
            LocalDateTime now = LocalDateTime.now();
            if (existing == null) {
                entity.setCreateTime(now);
                entity.setUpdateTime(now);
                if (entity.getDelivered() == null) entity.setDelivered(0);
                liepinMapper.insert(entity);
            } else {
                // 保留 create_time，更新其他字段与 update_time
                entity.setCreateTime(existing.getCreateTime());
                entity.setUpdateTime(now);
                if (entity.getDelivered() == null) entity.setDelivered(existing.getDelivered());
                liepinMapper.updateById(entity);
            }
        } catch (Exception e) {
            log.warn("保存猎聘岗位快照失败 job_id={}: {}", entity.getJobId(), e.getMessage());
        }
    }

    /**
     * 仅在不存在时插入岗位快照（默认 delivered=0）；存在则跳过
     */
    public void insertSnapshotIfNotExists(LiepinEntity entity) {
        if (entity == null || entity.getJobId() == null) {
            return;
        }
        try {
            LiepinEntity existing = liepinMapper.selectById(entity.getJobId());
            if (existing == null) {
                LocalDateTime now = LocalDateTime.now();
                entity.setCreateTime(now);
                entity.setUpdateTime(now);
                if (entity.getDelivered() == null) entity.setDelivered(0);
                liepinMapper.insert(entity);
            } else {
                // already exists, skip
            }
        } catch (Exception e) {
            log.warn("插入猎聘岗位快照失败 job_id={}: {}", entity.getJobId(), e.getMessage());
        }
    }

    /**
     * 标记岗位为已投递（delivered=1），如存在该记录
     */
    public void markDelivered(Long jobId) {
        if (jobId == null) return;
        try {
            LiepinEntity existing = liepinMapper.selectById(jobId);
            if (existing != null) {
                LiepinEntity update = new LiepinEntity();
                update.setJobId(jobId);
                update.setDelivered(1);
                update.setCreateTime(existing.getCreateTime());
                update.setUpdateTime(LocalDateTime.now());
                liepinMapper.updateById(update);
                clearSuspendedRetry(jobId);
            }
        } catch (Exception e) {
            log.warn("更新投递状态失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    // BEGIN feature: liepin-clear-pending-action
    public record DeliverySummary(long total, long delivered, long pending) {
    }

    public record PendingCleanupResult(long deleted, long total, long delivered, long pending) {
    }

    public record SuspendedRetrySummary(long total, long ai, long network) {
    }

    /** 读取岗位库的全局投递状态统计，不受分析页筛选条件影响。 */
    public DeliverySummary getDeliverySummary() {
        try (Connection conn = dataSource.getConnection()) {
            return readDeliverySummary(conn);
        } catch (Exception e) {
            throw new IllegalStateException("读取猎聘岗位统计失败", e);
        }
    }

    /** 读取本次批量重试的目标快照，只包含当前未投递岗位。 */
    public List<Long> listPendingJobIds() {
        String sql = "SELECT job_id FROM liepin_data " +
                "WHERE delivered = 0 OR delivered IS NULL " +
                "ORDER BY job_id ASC";
        List<Long> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                long jobId = rs.getLong(1);
                if (!rs.wasNull()) {
                    ids.add(jobId);
                }
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("读取待重试岗位失败", e);
        }
    }

    /** 读取已通过前置筛选、但因 AI 或网络问题暂缓的未投递岗位快照。 */
    public List<Long> listSuspendedRetryJobIds() {
        String sql = "SELECT q.job_id FROM liepin_retry_queue q " +
                "INNER JOIN liepin_data d ON d.job_id = q.job_id " +
                "WHERE d.delivered = 0 OR d.delivered IS NULL " +
                "ORDER BY q.updated_at ASC, q.job_id ASC";
        List<Long> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                long jobId = rs.getLong(1);
                if (!rs.wasNull()) {
                    ids.add(jobId);
                }
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("读取暂缓重试岗位失败", e);
        }
    }

    /** 统计暂缓队列，已投递的历史条目不会计入。 */
    public SuspendedRetrySummary getSuspendedRetrySummary() {
        String sql = "SELECT COUNT(*), " +
                "COALESCE(SUM(CASE WHEN q.retry_reason LIKE 'AI_%' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN q.retry_reason LIKE 'NETWORK_%' THEN 1 ELSE 0 END), 0) " +
                "FROM liepin_retry_queue q INNER JOIN liepin_data d ON d.job_id = q.job_id " +
                "WHERE d.delivered = 0 OR d.delivered IS NULL";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            if (!rs.next()) {
                return new SuspendedRetrySummary(0, 0, 0);
            }
            return new SuspendedRetrySummary(rs.getLong(1), rs.getLong(2), rs.getLong(3));
        } catch (Exception e) {
            throw new IllegalStateException("读取暂缓重试岗位统计失败", e);
        }
    }

    /** 将已通过前置筛选、但暂时未完成的岗位加入可重试队列。 */
    public void markSuspendedRetry(Long jobId, String reason) {
        if (jobId == null || reason == null || reason.isBlank()) {
            return;
        }
        String sql = "INSERT INTO liepin_retry_queue (job_id, retry_reason, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT(job_id) DO UPDATE SET " +
                "retry_reason=excluded.retry_reason, updated_at=excluded.updated_at";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            ps.setLong(1, jobId);
            ps.setString(2, reason.trim());
            ps.setTimestamp(3, now);
            ps.setTimestamp(4, now);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("记录猎聘暂缓重试岗位失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    /** 已成功发送或明确不匹配时，移出暂缓重试队列。 */
    public void clearSuspendedRetry(Long jobId) {
        if (jobId == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM liepin_retry_queue WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("清除猎聘暂缓重试岗位失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    /** 原子清除全部未投递快照，保留已投递记录。 */
    public PendingCleanupResult clearPendingSnapshots() {
        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM liepin_retry_queue WHERE job_id IN " +
                                "(SELECT job_id FROM liepin_data WHERE delivered = 0 OR delivered IS NULL)")) {
                    ps.executeUpdate();
                }
                long deleted;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM liepin_data WHERE delivered = 0 OR delivered IS NULL")) {
                    deleted = ps.executeUpdate();
                }

                DeliverySummary summary = readDeliverySummary(conn);
                conn.commit();
                return new PendingCleanupResult(
                        deleted,
                        summary.total(),
                        summary.delivered(),
                        summary.pending()
                );
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (Exception rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw new IllegalStateException("清理未投递岗位失败", e);
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (Exception ignored) {
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("清理未投递岗位失败", e);
        }
    }

    private DeliverySummary readDeliverySummary(Connection conn) throws java.sql.SQLException {
        String sql = "SELECT COUNT(*), " +
                "COALESCE(SUM(CASE WHEN delivered = 1 THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN delivered = 0 OR delivered IS NULL THEN 1 ELSE 0 END), 0) " +
                "FROM liepin_data";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            if (!rs.next()) {
                return new DeliverySummary(0, 0, 0);
            }
            return new DeliverySummary(rs.getLong(1), rs.getLong(2), rs.getLong(3));
        }
    }
    // END feature: liepin-clear-pending-action

    // ==================== 分页断点续跑 ====================

    public static class PageProgressItem {
        public String keyword;
        public String cityCode;
        public String salaryCode;
        public int lastCompletedPage;
        public int nextStartPage;
        public String updatedAt;
    }

    private static String normProgressPart(String value) {
        return com.getjobs.worker.liepin.LiepinPageProgress.normalize(value);
    }

    /** 读取某关键词在当前筛选条件下已完整完成的页码；无记录返回 null。 */
    public Integer getLastCompletedPage(String keyword, String cityCode, String salaryCode) {
        String kw = normProgressPart(keyword);
        if (kw.isEmpty()) return null;
        String city = normProgressPart(cityCode);
        String salary = normProgressPart(salaryCode);
        String sql = "SELECT last_completed_page FROM liepin_page_progress " +
                "WHERE keyword = ? AND city_code = ? AND salary_code = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kw);
            ps.setString(2, city);
            ps.setString(3, salary);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    int page = rs.getInt(1);
                    return page > 0 ? page : null;
                }
            }
        } catch (Exception e) {
            log.warn("读取猎聘分页进度失败 keyword={}: {}", kw, e.getMessage());
        }
        return null;
    }

    /** 保存“已完整处理完”的页码。 */
    public void saveLastCompletedPage(String keyword, String cityCode, String salaryCode, int page) {
        String kw = normProgressPart(keyword);
        if (kw.isEmpty() || page < 1) return;
        String city = normProgressPart(cityCode);
        String salary = normProgressPart(salaryCode);
        String sql = "INSERT INTO liepin_page_progress (keyword, city_code, salary_code, last_completed_page, updated_at) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(keyword, city_code, salary_code) DO UPDATE SET " +
                "last_completed_page = excluded.last_completed_page, updated_at = excluded.updated_at";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kw);
            ps.setString(2, city);
            ps.setString(3, salary);
            ps.setInt(4, page);
            ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("保存猎聘分页进度失败 keyword={}, page={}: {}", kw, page, e.getMessage());
        }
    }

    /** 清除单个关键词在当前筛选下的进度。 */
    public void clearPageProgress(String keyword, String cityCode, String salaryCode) {
        String kw = normProgressPart(keyword);
        if (kw.isEmpty()) return;
        String city = normProgressPart(cityCode);
        String salary = normProgressPart(salaryCode);
        String sql = "DELETE FROM liepin_page_progress WHERE keyword = ? AND city_code = ? AND salary_code = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kw);
            ps.setString(2, city);
            ps.setString(3, salary);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("清除猎聘分页进度失败 keyword={}: {}", kw, e.getMessage());
        }
    }

    /** 清空全部投递页码进度。 */
    public int clearAllPageProgress() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate("DELETE FROM liepin_page_progress");
        } catch (Exception e) {
            log.warn("清空猎聘分页进度失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 列出全部页码进度，供前端展示。 */
    public List<PageProgressItem> listPageProgress() {
        List<PageProgressItem> items = new ArrayList<>();
        String sql = "SELECT keyword, city_code, salary_code, last_completed_page, updated_at " +
                "FROM liepin_page_progress ORDER BY updated_at DESC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                PageProgressItem item = new PageProgressItem();
                item.keyword = rs.getString(1);
                item.cityCode = rs.getString(2);
                item.salaryCode = rs.getString(3);
                item.lastCompletedPage = rs.getInt(4);
                item.nextStartPage = com.getjobs.worker.liepin.LiepinPageProgress.nextStartPage(item.lastCompletedPage);
                Timestamp ts = rs.getTimestamp(5);
                item.updatedAt = ts == null ? null : ts.toLocalDateTime().toString();
                items.add(item);
            }
        } catch (Exception e) {
            log.warn("列出猎聘分页进度失败: {}", e.getMessage());
        }
        return items;
    }

    /** 详情页读取到 JD 后补写岗位快照，不覆盖其他字段。 */
    public void updateJobDescription(Long jobId, String description) {
        if (jobId == null || description == null || description.isBlank()) return;
        try {
            LiepinEntity update = new LiepinEntity();
            update.setJobId(jobId);
            update.setJobDescription(description.trim());
            update.setUpdateTime(LocalDateTime.now());
            liepinMapper.updateById(update);
        } catch (Exception e) {
            log.warn("更新猎聘岗位JD失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    /**
     * 批量插入岗位快照（仅不存在时），减少单次网络响应后的数据库操作时间
     */
    public void insertSnapshotsIfNotExistsBatch(java.util.List<LiepinEntity> entities) {
        if (entities == null || entities.isEmpty()) return;

        // 收集待处理的 jobId
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (LiepinEntity e : entities) {
            if (e != null && e.getJobId() != null) ids.add(e.getJobId());
        }
        if (ids.isEmpty()) return;

        // 批量查询已存在的记录
        java.util.List<Long> idList = new java.util.ArrayList<>(ids);
        java.util.List<LiepinEntity> existing = liepinMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<LiepinEntity>()
                        .in("job_id", idList)
        );
        java.util.Set<Long> existingIds = new java.util.HashSet<>();
        if (existing != null) {
            for (LiepinEntity e : existing) {
                if (e != null && e.getJobId() != null) existingIds.add(e.getJobId());
            }
        }

        // 过滤出需要插入的记录
        java.util.List<LiepinEntity> toInsert = new java.util.ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (LiepinEntity e : entities) {
            if (e == null || e.getJobId() == null) continue;
            if (existingIds.contains(e.getJobId())) continue;
            if (e.getCreateTime() == null) e.setCreateTime(now);
            e.setUpdateTime(now);
            if (e.getDelivered() == null) e.setDelivered(0);
            toInsert.add(e);
        }
        if (toInsert.isEmpty()) return;

        // 使用JDBC批量插入以提升性能
        String sql = "INSERT INTO liepin_data (" +
                "job_id, job_title, job_link, job_description, job_salary_text, job_area, job_edu_req, job_exp_req, job_publish_time, " +
                "comp_id, comp_name, comp_industry, comp_scale, " +
                "hr_id, hr_name, hr_title, hr_im_id, delivered, create_time, update_time) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (LiepinEntity e : toInsert) {
                // 1 job_id
                if (e.getJobId() == null) ps.setNull(1, Types.BIGINT); else ps.setLong(1, e.getJobId());
                // 2 job_title
                if (e.getJobTitle() == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, e.getJobTitle());
                // 3 job_link
                if (e.getJobLink() == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, e.getJobLink());
                // 4 job_description
                if (e.getJobDescription() == null) ps.setNull(4, Types.LONGVARCHAR); else ps.setString(4, e.getJobDescription());
                // 5 job_salary_text
                if (e.getJobSalaryText() == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, e.getJobSalaryText());
                // 6 job_area
                if (e.getJobArea() == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, e.getJobArea());
                // 7 job_edu_req
                if (e.getJobEduReq() == null) ps.setNull(7, Types.VARCHAR); else ps.setString(7, e.getJobEduReq());
                // 8 job_exp_req
                if (e.getJobExpReq() == null) ps.setNull(8, Types.VARCHAR); else ps.setString(8, e.getJobExpReq());
                // 9 job_publish_time
                if (e.getJobPublishTime() == null) ps.setNull(9, Types.VARCHAR); else ps.setString(9, e.getJobPublishTime());
                // 10 comp_id
                if (e.getCompId() == null) ps.setNull(10, Types.BIGINT); else ps.setLong(10, e.getCompId());
                // 11 comp_name
                if (e.getCompName() == null) ps.setNull(11, Types.VARCHAR); else ps.setString(11, e.getCompName());
                // 12 comp_industry
                if (e.getCompIndustry() == null) ps.setNull(12, Types.VARCHAR); else ps.setString(12, e.getCompIndustry());
                // 13 comp_scale
                if (e.getCompScale() == null) ps.setNull(13, Types.VARCHAR); else ps.setString(13, e.getCompScale());
                // 14 hr_id
                if (e.getHrId() == null) ps.setNull(14, Types.VARCHAR); else ps.setString(14, e.getHrId());
                // 15 hr_name
                if (e.getHrName() == null) ps.setNull(15, Types.VARCHAR); else ps.setString(15, e.getHrName());
                // 16 hr_title
                if (e.getHrTitle() == null) ps.setNull(16, Types.VARCHAR); else ps.setString(16, e.getHrTitle());
                // 17 hr_im_id
                if (e.getHrImId() == null) ps.setNull(17, Types.VARCHAR); else ps.setString(17, e.getHrImId());
                // 18 delivered
                if (e.getDelivered() == null) ps.setNull(18, Types.INTEGER); else ps.setInt(18, e.getDelivered());
                // 19 create_time
                if (e.getCreateTime() == null) ps.setNull(19, Types.TIMESTAMP); else ps.setTimestamp(19, Timestamp.valueOf(e.getCreateTime()));
                // 20 update_time
                if (e.getUpdateTime() == null) ps.setNull(20, Types.TIMESTAMP); else ps.setTimestamp(20, Timestamp.valueOf(e.getUpdateTime()));

                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (Exception e) {
            log.warn("批量插入猎聘岗位快照失败: {}", e.getMessage());
        }
    }

    public record AiScreenCache(Integer score, String decision, String reasonCodes, String reason) {
    }

    public record AiMessageCache(String message) {
    }

    public AiScreenCache findAiScreenCache(Long jobId, String inputHash) {
        if (jobId == null || inputHash == null || inputHash.isBlank()) return null;
        String sql = "SELECT score, decision, reason_codes, reason FROM liepin_ai_screen_cache " +
                "WHERE job_id = ? AND input_hash = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            ps.setString(2, inputHash);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new AiScreenCache(rs.getObject(1, Integer.class), rs.getString(2),
                            rs.getString(3), rs.getString(4));
                }
            }
        } catch (Exception e) {
            log.debug("读取 AI 评分缓存失败 job_id={}: {}", jobId, e.getMessage());
        }
        return null;
    }

    public void saveAiScreenCache(Long jobId, String inputHash, String model, int score,
                                  String decision, String reasonCodes, String reason, long latencyMs) {
        if (jobId == null || inputHash == null || inputHash.isBlank()) return;
        String sql = "INSERT INTO liepin_ai_screen_cache " +
                "(job_id, input_hash, model, score, decision, reason_codes, reason, latency_ms, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(job_id, input_hash) DO UPDATE SET model=excluded.model, " +
                "score=excluded.score, decision=excluded.decision, reason_codes=excluded.reason_codes, " +
                "reason=excluded.reason, latency_ms=excluded.latency_ms, updated_at=excluded.updated_at";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            ps.setString(2, inputHash);
            ps.setString(3, model);
            ps.setInt(4, score);
            ps.setString(5, decision);
            ps.setString(6, reasonCodes);
            ps.setString(7, reason);
            ps.setLong(8, latencyMs);
            ps.setTimestamp(9, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("保存 AI 评分缓存失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    public AiMessageCache findAiMessageCache(Long jobId, String inputHash) {
        if (jobId == null || inputHash == null || inputHash.isBlank()) return null;
        String sql = "SELECT message FROM liepin_ai_message_cache " +
                "WHERE job_id = ? AND input_hash = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            ps.setString(2, inputHash);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return new AiMessageCache(rs.getString(1));
            }
        } catch (Exception e) {
            log.debug("读取 AI 话术缓存失败 job_id={}: {}", jobId, e.getMessage());
        }
        return null;
    }

    public void saveAiMessageCache(Long jobId, String inputHash, String model,
                                   String message, long latencyMs) {
        if (jobId == null || inputHash == null || inputHash.isBlank() || message == null) return;
        String sql = "INSERT INTO liepin_ai_message_cache " +
                "(job_id, input_hash, model, message, latency_ms, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(job_id, input_hash) DO UPDATE SET model=excluded.model, " +
                "message=excluded.message, latency_ms=excluded.latency_ms, updated_at=excluded.updated_at";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            ps.setString(2, inputHash);
            ps.setString(3, model);
            ps.setString(4, message);
            ps.setLong(5, latencyMs);
            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("保存 AI 话术缓存失败 job_id={}: {}", jobId, e.getMessage());
        }
    }

    // ==================== Config相关方法 ====================

    /**
     * 获取第一条配置记录（通常只有一条）
     */
    public LiepinConfigEntity getFirstConfig() {
        QueryWrapper<LiepinConfigEntity> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("id");
        wrapper.last("LIMIT 1");
        return liepinConfigMapper.selectOne(wrapper);
    }

    /**
     * 更新配置
     */
    public LiepinConfigEntity updateConfig(LiepinConfigEntity config) {
        applyDeliveryDefaults(config);
        config.setUpdatedAt(LocalDateTime.now());
        liepinConfigMapper.updateById(config);
        return config;
    }

    /**
     * 保存或更新第一条配置（选择性更新）
     * 如果数据库中没有记录，则插入新记录；否则更新第一条记录
     */
    public LiepinConfigEntity saveOrUpdateFirstSelective(LiepinConfigEntity config) {
        LiepinConfigEntity existing = getFirstConfig();
        if (existing == null) {
            // 不存在记录，插入新配置
            applyDeliveryDefaults(config);
            config.setCreatedAt(LocalDateTime.now());
            config.setUpdatedAt(LocalDateTime.now());
            liepinConfigMapper.insert(config);
            return config;
        }
        // 存在记录，选择性更新非null字段
        config.setId(existing.getId());
        if (config.getKeywords() != null) {
            existing.setKeywords(config.getKeywords());
        }
        if (config.getCity() != null) {
            existing.setCity(config.getCity());
        }
        if (config.getSalaryCode() != null) {
            existing.setSalaryCode(config.getSalaryCode());
        }
        if (config.getEnableAi() != null) {
            existing.setEnableAi(config.getEnableAi());
        }
        if (config.getAutoAiDelivery() != null) {
            existing.setAutoAiDelivery(config.getAutoAiDelivery());
        }
        if (config.getAiDeliveryMode() != null) {
            existing.setAiDeliveryMode(config.getAiDeliveryMode());
        }
        if (config.getAiMinScore() != null) {
            existing.setAiMinScore(config.getAiMinScore());
        }
        if (config.getAiReviewMinScore() != null) {
            existing.setAiReviewMinScore(config.getAiReviewMinScore());
        }
        if (config.getAiBatchSize() != null) {
            existing.setAiBatchSize(config.getAiBatchSize());
        }
        if (config.getMaxPerRun() != null) {
            existing.setMaxPerRun(config.getMaxPerRun());
        }
        if (config.getMinDelaySeconds() != null) {
            existing.setMinDelaySeconds(config.getMinDelaySeconds());
        }
        if (config.getMaxDelaySeconds() != null) {
            existing.setMaxDelaySeconds(config.getMaxDelaySeconds());
        }
        if (config.getSearchMinDelaySeconds() != null) {
            existing.setSearchMinDelaySeconds(config.getSearchMinDelaySeconds());
        }
        if (config.getSearchMaxDelaySeconds() != null) {
            existing.setSearchMaxDelaySeconds(config.getSearchMaxDelaySeconds());
        }
        if (config.getPageMinDelaySeconds() != null) {
            existing.setPageMinDelaySeconds(config.getPageMinDelaySeconds());
        }
        if (config.getPageMaxDelaySeconds() != null) {
            existing.setPageMaxDelaySeconds(config.getPageMaxDelaySeconds());
        }
        if (config.getDetailMinDelaySeconds() != null) {
            existing.setDetailMinDelaySeconds(config.getDetailMinDelaySeconds());
        }
        if (config.getDetailMaxDelaySeconds() != null) {
            existing.setDetailMaxDelaySeconds(config.getDetailMaxDelaySeconds());
        }
        if (config.getRateGuardBatchSize() != null) {
            existing.setRateGuardBatchSize(config.getRateGuardBatchSize());
        }
        if (config.getBatchCooldownMinSeconds() != null) {
            existing.setBatchCooldownMinSeconds(config.getBatchCooldownMinSeconds());
        }
        if (config.getBatchCooldownMaxSeconds() != null) {
            existing.setBatchCooldownMaxSeconds(config.getBatchCooldownMaxSeconds());
        }
        applyDeliveryDefaults(existing);
        existing.setUpdatedAt(LocalDateTime.now());
        liepinConfigMapper.updateById(existing);
        return existing;
    }

    private void applyDeliveryDefaults(LiepinConfigEntity config) {
        if (config == null) return;
        if (config.getEnableAi() == null) config.setEnableAi(1);
        if (config.getAutoAiDelivery() == null) config.setAutoAiDelivery(0);
        if (config.getAiDeliveryMode() == null || config.getAiDeliveryMode().isBlank()) {
            config.setAiDeliveryMode(config.getAutoAiDelivery() == 1 ? "SINGLE_AUTO" : "MANUAL");
        }
        config.setAiDeliveryMode(normalizeDeliveryMode(config.getAiDeliveryMode()));
        config.setAutoAiDelivery("MANUAL".equals(config.getAiDeliveryMode())
                || "BATCH_SHADOW".equals(config.getAiDeliveryMode()) ? 0 : 1);
        if (config.getAiMinScore() == null) config.setAiMinScore(LiepinConfig.DEFAULT_AI_MIN_SCORE);
        if (config.getAiReviewMinScore() == null) {
            config.setAiReviewMinScore(LiepinConfig.DEFAULT_AI_REVIEW_MIN_SCORE);
        }
        if (config.getAiBatchSize() == null) config.setAiBatchSize(LiepinConfig.DEFAULT_AI_BATCH_SIZE);
        config.setAutoAiDelivery(config.getAutoAiDelivery() == 0 ? 0 : 1);
        config.setAiMinScore(Math.max(LiepinConfig.MIN_AI_SCORE,
                Math.min(LiepinConfig.MAX_AI_SCORE, config.getAiMinScore())));
        config.setAiReviewMinScore(Math.max(LiepinConfig.MIN_AI_SCORE,
                Math.min(config.getAiMinScore(), config.getAiReviewMinScore())));
        config.setAiBatchSize(Math.max(LiepinConfig.MIN_AI_BATCH_SIZE,
                Math.min(LiepinConfig.MAX_AI_BATCH_SIZE, config.getAiBatchSize())));
        if (config.getMaxPerRun() == null) config.setMaxPerRun(LiepinConfig.DEFAULT_MAX_PER_RUN);
        if (config.getMinDelaySeconds() == null) config.setMinDelaySeconds(LiepinConfig.DEFAULT_MIN_DELAY_SECONDS);
        if (config.getMaxDelaySeconds() == null) config.setMaxDelaySeconds(LiepinConfig.DEFAULT_MAX_DELAY_SECONDS);
        if (config.getSearchMinDelaySeconds() == null) config.setSearchMinDelaySeconds(LiepinConfig.DEFAULT_SEARCH_MIN_DELAY_SECONDS);
        if (config.getSearchMaxDelaySeconds() == null) config.setSearchMaxDelaySeconds(LiepinConfig.DEFAULT_SEARCH_MAX_DELAY_SECONDS);
        if (config.getPageMinDelaySeconds() == null) config.setPageMinDelaySeconds(LiepinConfig.DEFAULT_PAGE_MIN_DELAY_SECONDS);
        if (config.getPageMaxDelaySeconds() == null) config.setPageMaxDelaySeconds(LiepinConfig.DEFAULT_PAGE_MAX_DELAY_SECONDS);
        if (config.getDetailMinDelaySeconds() == null) config.setDetailMinDelaySeconds(LiepinConfig.DEFAULT_DETAIL_MIN_DELAY_SECONDS);
        if (config.getDetailMaxDelaySeconds() == null) config.setDetailMaxDelaySeconds(LiepinConfig.DEFAULT_DETAIL_MAX_DELAY_SECONDS);
        if (config.getRateGuardBatchSize() == null) config.setRateGuardBatchSize(LiepinConfig.DEFAULT_RATE_GUARD_BATCH_SIZE);
        if (config.getBatchCooldownMinSeconds() == null) config.setBatchCooldownMinSeconds(LiepinConfig.DEFAULT_BATCH_COOLDOWN_MIN_SECONDS);
        if (config.getBatchCooldownMaxSeconds() == null) config.setBatchCooldownMaxSeconds(LiepinConfig.DEFAULT_BATCH_COOLDOWN_MAX_SECONDS);
        if (config.getMaxPerRun() < 1) {
            throw new IllegalArgumentException("猎聘单次岗位上限必须大于0");
        }
        config.setMaxPerRun(Math.min(config.getMaxPerRun(), LiepinConfig.MAX_SAFE_PER_RUN));
        config.setMinDelaySeconds(Math.max(config.getMinDelaySeconds(), LiepinConfig.MIN_SAFE_SEND_DELAY_SECONDS));
        config.setMaxDelaySeconds(Math.max(
                Math.max(config.getMaxDelaySeconds(), LiepinConfig.DEFAULT_MAX_DELAY_SECONDS),
                config.getMinDelaySeconds()
        ));
        config.setSearchMinDelaySeconds(clamp(config.getSearchMinDelaySeconds(), LiepinConfig.MIN_RATE_DELAY_SECONDS,
                LiepinConfig.MAX_RATE_DELAY_SECONDS));
        config.setSearchMaxDelaySeconds(Math.max(config.getSearchMinDelaySeconds(),
                clamp(config.getSearchMaxDelaySeconds(), LiepinConfig.MIN_RATE_DELAY_SECONDS, LiepinConfig.MAX_RATE_DELAY_SECONDS)));
        config.setPageMinDelaySeconds(clamp(config.getPageMinDelaySeconds(), LiepinConfig.MIN_RATE_DELAY_SECONDS,
                LiepinConfig.MAX_RATE_DELAY_SECONDS));
        config.setPageMaxDelaySeconds(Math.max(config.getPageMinDelaySeconds(),
                clamp(config.getPageMaxDelaySeconds(), LiepinConfig.MIN_RATE_DELAY_SECONDS, LiepinConfig.MAX_RATE_DELAY_SECONDS)));
        config.setDetailMinDelaySeconds(clamp(config.getDetailMinDelaySeconds(), LiepinConfig.MIN_RATE_DELAY_SECONDS,
                LiepinConfig.MAX_RATE_DELAY_SECONDS));
        config.setDetailMaxDelaySeconds(Math.max(config.getDetailMinDelaySeconds(),
                clamp(config.getDetailMaxDelaySeconds(), LiepinConfig.MIN_RATE_DELAY_SECONDS, LiepinConfig.MAX_RATE_DELAY_SECONDS)));
        config.setRateGuardBatchSize(clamp(config.getRateGuardBatchSize(), LiepinConfig.MIN_RATE_GUARD_BATCH_SIZE,
                LiepinConfig.MAX_RATE_GUARD_BATCH_SIZE));
        config.setBatchCooldownMinSeconds(clamp(config.getBatchCooldownMinSeconds(),
                LiepinConfig.MIN_BATCH_COOLDOWN_SECONDS, LiepinConfig.MAX_BATCH_COOLDOWN_SECONDS));
        config.setBatchCooldownMaxSeconds(Math.max(config.getBatchCooldownMinSeconds(),
                clamp(config.getBatchCooldownMaxSeconds(), LiepinConfig.MIN_BATCH_COOLDOWN_SECONDS,
                        LiepinConfig.MAX_BATCH_COOLDOWN_SECONDS)));
        if (config.getMinDelaySeconds() < 0
                || config.getMaxDelaySeconds() < config.getMinDelaySeconds()
                || config.getMaxDelaySeconds() > LiepinConfig.MAX_RATE_DELAY_SECONDS) {
            throw new IllegalArgumentException("猎聘发送等待需满足 30<=最小秒数<=最大秒数<=300");
        }
    }

    private int clamp(Integer value, int min, int max) {
        int resolved = value == null ? min : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private String normalizeDeliveryMode(String value) {
        if (value == null) return "MANUAL";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SINGLE_AUTO", "BATCH_SHADOW", "BATCH_AUTO" -> normalized;
            default -> "MANUAL";
        };
    }

    // ==================== Option相关方法 ====================

    /**
     * 根据类型获取选项列表
     */
    public List<LiepinOptionEntity> getOptionsByType(String type) {
        QueryWrapper<LiepinOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type);
        wrapper.orderByAsc("sort_order", "id");
        return liepinOptionMapper.selectList(wrapper);
    }

    /**
     * 根据类型和代码获取选项
     */
    public LiepinOptionEntity getOptionByTypeAndCode(String type, String code) {
        QueryWrapper<LiepinOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type);
        wrapper.eq("code", code);
        return liepinOptionMapper.selectOne(wrapper);
    }

    /**
     * 根据类型和名称获取代码
     */
    public String getCodeByTypeAndName(String type, String name) {
        QueryWrapper<LiepinOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type);
        wrapper.eq("name", name);
        LiepinOptionEntity entity = liepinOptionMapper.selectOne(wrapper);
        return entity != null ? entity.getCode() : "";
    }

    /**
     * 根据类型和代码获取名称
     */
    public String getNameByTypeAndCode(String type, String code) {
        LiepinOptionEntity entity = getOptionByTypeAndCode(type, code);
        return entity != null ? entity.getName() : code;
    }

    /**
     * 规范化城市为名称
     * 如果传入的是代码，则查找对应的名称；否则直接返回
     */
    public String normalizeCityToName(String cityCodeOrName) {
        if (cityCodeOrName == null || cityCodeOrName.isEmpty()) {
            return "";
        }
        // 尝试作为代码查找
        LiepinOptionEntity entity = getOptionByTypeAndCode("city", cityCodeOrName);
        if (entity != null) {
            return entity.getName();
        }
        // 如果找不到，直接返回原值（可能是手动输入的城市名）
        return cityCodeOrName;
    }

    // ==================== 分析与列表（参考 BossService） ====================

    public static class SalaryInfo {
        public Integer minK;
        public Integer maxK;
        public Integer months;
        public Double medianK;
        public Long annualTotal;
    }

    public static class SalaryRange {
        public final Integer minK;
        public final Integer maxK;

        public SalaryRange(Integer minK, Integer maxK) {
            this.minK = minK;
            this.maxK = maxK;
        }
    }

    /** 网页自定义薪资输入使用的年薪万元范围。 */
    public record WebSalaryRange(double minWan, double maxWan) {
        public String minText() {
            return formatWebSalaryValue(minWan);
        }

        public String maxText() {
            return formatWebSalaryValue(maxWan);
        }
    }

    public static class SalaryCheck {
        public final boolean allowed;
        public final String reason;

        private SalaryCheck(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static SalaryCheck allow() {
            return new SalaryCheck(true, "在配置范围内");
        }

        public static SalaryCheck deny(String reason) {
            return new SalaryCheck(false, reason);
        }
    }

    /**
     * 解析薪资为统一的月薪 K 区间。
     * 支持月薪 K、万元/月、元/月、年薪万元/元，以及 13 薪等文本。
     */
    public static SalaryInfo parseSalary(String salaryText) {
        if (salaryText == null) return null;
        String s = salaryText.trim()
                .replaceAll("\\s+", "")
                .replace(',', '，')
                .replace("，", "")
                .replace('－', '-')
                .replace('–', '-')
                .replace('—', '-');
        if (s.isEmpty() || s.contains("面议") || isDailySalary(s)) return null;

        int months = parseSalaryMonths(s);
        boolean monthly = isMonthlySalary(s);
        // 猎聘搜索页的“10-15万”是年薪档，只有显式标为“万元/月”时才按月薪处理。
        boolean annual = !monthly && (isAnnualSalary(s) || containsTenThousandUnit(s));
        String cleaned = cleanSalaryBase(s);
        java.util.regex.Pattern rangePattern = java.util.regex.Pattern.compile(
                "^(\\d+(?:\\.\\d+)?)(万元?|人民币|元|[Kk千])?-(\\d+(?:\\.\\d+)?)(万元?|人民币|元|[Kk千])?$");
        java.util.regex.Pattern singlePattern = java.util.regex.Pattern.compile(
                "^(\\d+(?:\\.\\d+)?)(万元?|人民币|元|[Kk千])?$");
        java.util.regex.Matcher range = rangePattern.matcher(cleaned);
        java.util.regex.Matcher single = singlePattern.matcher(cleaned);
        double minMonthlyK;
        double maxMonthlyK;
        if (range.matches()) {
            String minUnit = range.group(2);
            String maxUnit = range.group(4);
            String fallbackUnit = minUnit != null ? minUnit : maxUnit;
            minMonthlyK = toMonthlyK(Double.parseDouble(range.group(1)), minUnit, fallbackUnit, annual, months);
            maxMonthlyK = toMonthlyK(Double.parseDouble(range.group(3)), maxUnit, fallbackUnit, annual, months);
        } else if (single.matches()) {
            String unit = single.group(2);
            double monthlyK = toMonthlyK(Double.parseDouble(single.group(1)), unit, unit, annual, months);
            minMonthlyK = monthlyK;
            maxMonthlyK = monthlyK;
        } else {
            return null;
        }

        if (!Double.isFinite(minMonthlyK) || !Double.isFinite(maxMonthlyK)
                || minMonthlyK < 0 || maxMonthlyK < minMonthlyK) {
            return null;
        }
        int minK = (int) Math.round(minMonthlyK);
        int maxK = (int) Math.round(maxMonthlyK);
        SalaryInfo info = new SalaryInfo();
        info.minK = minK;
        info.maxK = maxK;
        info.months = months;
        info.medianK = (minK + maxK) / 2.0;
        info.annualTotal = Math.round(info.medianK * 1000 * info.months);
        return info;
    }

    private static int parseSalaryMonths(String salaryText) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([0-9]{1,2})[·⋅×xX*]?(?:薪|月)")
                .matcher(salaryText);
        if (!matcher.find()) return 12;
        try {
            int months = Integer.parseInt(matcher.group(1));
            return months > 0 && months <= 24 ? months : 12;
        } catch (NumberFormatException ignored) {
            return 12;
        }
    }

    private static boolean isAnnualSalary(String salaryText) {
        return salaryText.contains("年薪")
                || salaryText.contains("每年")
                || salaryText.contains("/年")
                || salaryText.endsWith("年");
    }

    private static boolean isMonthlySalary(String salaryText) {
        return salaryText.contains("月薪")
                || salaryText.contains("每月")
                || salaryText.contains("/月")
                || salaryText.endsWith("月");
    }

    private static boolean containsTenThousandUnit(String salaryText) {
        return salaryText.contains("万");
    }

    private static String cleanSalaryBase(String salaryText) {
        java.util.regex.Matcher months = java.util.regex.Pattern
                .compile("([0-9]{1,2})[·⋅×xX*]?(?:薪|月)")
                .matcher(salaryText);
        String cleaned = months.find()
                ? salaryText.substring(0, months.start())
                : salaryText;
        return cleaned
                .replaceAll("[·⋅×xX*].*$", "")
                .replaceAll("[（(].*$", "")
                .replace("年薪", "")
                .replace("月薪", "")
                .replace("/年", "")
                .replace("每年", "")
                .replace("/月", "")
                .replace("每月", "")
                .replace("年", "")
                .replace("月", "")
                .replace(":", "")
                .replace("：", "")
                .trim();
    }

    private static double toMonthlyK(double value, String unit, String fallbackUnit,
                                     boolean annual, int months) {
        String resolvedUnit = unit == null ? fallbackUnit : unit;
        if (resolvedUnit == null) {
            resolvedUnit = annual ? (value >= 1000 ? "元" : "万") : (value >= 1000 ? "元" : "K");
        }
        double totalK = switch (resolvedUnit) {
            case "万", "万元" -> value * 10.0;
            case "元", "人民币" -> value / 1000.0;
            case "千", "K", "k" -> value;
            default -> value;
        };
        return annual ? totalK / Math.max(1, months) : totalK;
    }

    /** 解析猎聘薪资范围码，例如“6$10”表示6K-10K。 */
    public static SalaryRange parseSalaryRange(String salaryCode) {
        if (salaryCode == null || salaryCode.isBlank()) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d+)\\$(\\d+)$")
                .matcher(salaryCode.trim().replaceAll("\\s+", ""));
        if (!matcher.matches()) return null;
        try {
            int minK = Integer.parseInt(matcher.group(1));
            int maxK = Integer.parseInt(matcher.group(2));
            return minK <= maxK ? new SalaryRange(minK, maxK) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 将本地月薪 K 配置换算成网页“自定义”年薪万元输入。
     * 统一按 12 薪计算：1K 月薪等价于 1.2 万元年薪。
     */
    public static WebSalaryRange toWebAnnualSalaryRange(String salaryCode) {
        SalaryRange range = parseSalaryRange(salaryCode);
        if (range == null) return null;
        return new WebSalaryRange(
                roundWebSalaryValue(range.minK * 1.2),
                roundWebSalaryValue(range.maxK * 1.2)
        );
    }

    private static double roundWebSalaryValue(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String formatWebSalaryValue(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * 投递前的本地薪资闸门：岗位月薪区间与配置范围有交集即进入后续筛选。
     * 配置为空表示不启用薪资限制；配置非空但无法解析时拒绝岗位。
     */
    public static SalaryCheck checkSalaryInRange(String salaryText, String salaryCode) {
        if (salaryCode == null || salaryCode.isBlank()) return SalaryCheck.allow();

        SalaryRange configured = parseSalaryRange(salaryCode);
        if (configured == null) {
            return SalaryCheck.deny("薪资范围配置无法解析");
        }

        SalaryInfo actual = parseSalary(salaryText);
        if (actual == null) {
            if (salaryText == null || salaryText.isBlank()) {
                return SalaryCheck.deny("岗位薪资缺失");
            }
            if (salaryText.contains("面议")) {
                return SalaryCheck.deny("薪资面议");
            }
            if (isDailySalary(salaryText)) {
                return SalaryCheck.deny("非月薪岗位");
            }
            return SalaryCheck.deny("岗位薪资无法解析");
        }

        boolean overlapsRange = actual.maxK >= configured.minK && actual.minK <= configured.maxK;
        if (!overlapsRange) {
            return SalaryCheck.deny(String.format(
                    "岗位薪资%d-%dK与配置范围%d-%dK无交集",
                    actual.minK, actual.maxK, configured.minK, configured.maxK
            ));
        }
        return SalaryCheck.allow();
    }

    private static boolean isDailySalary(String salaryText) {
        String normalized = salaryText.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return normalized.contains("日薪")
                || normalized.contains("元/天")
                || normalized.contains("/天")
                || normalized.contains("每天")
                || normalized.contains("/day");
    }

    public static class Kpi {
        public long total;
        public long delivered;
        public long pending;
        public long filtered; // 猎聘暂无，置0
        public long failed;   // 猎聘暂无，置0
        public Double avgMonthlyK; // 平均中位数K
    }

    public static class NameValue { public String name; public long value; public NameValue(){} public NameValue(String n,long v){name=n;value=v;} }
    public static class BucketValue { public String bucket; public long value; public BucketValue(){} public BucketValue(String b,long v){bucket=b;value=v;} }

    public static class Charts {
        public List<NameValue> byStatus;
        public List<NameValue> byCity;
        public List<NameValue> byIndustry;
        public List<NameValue> byCompany;
        public List<NameValue> byExperience;
        public List<NameValue> byDegree;
        public List<BucketValue> salaryBuckets;
        public List<NameValue> dailyTrend;
        public List<NameValue> hrActivity;
    }

    public static class StatsResponse {
        public Kpi kpi;
        public Charts charts;
    }

    public static class PagedResult {
        public List<LiepinEntity> items;
        public long total;
        public int page;
        public int size;
    }

    private String nullSafe(String s) { return (s == null || s.trim().isEmpty()) ? "未知" : s.trim(); }

    /**
     * 获取投递分析统计与图表数据（按筛选条件）
     */
    public StatsResponse getLiepinStats(
            List<String> statuses,
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
        charts.byStatus = new ArrayList<>();
        charts.byCity = new ArrayList<>();
        charts.byIndustry = new ArrayList<>();
        charts.byCompany = new ArrayList<>();
        charts.byExperience = new ArrayList<>();
        charts.byDegree = new ArrayList<>();
        charts.salaryBuckets = new ArrayList<>();
        charts.dailyTrend = new ArrayList<>();
        charts.hrActivity = new ArrayList<>();

        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<LiepinEntity> wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();

            // 状态：已投递/未投递 -> delivered 1/0
            if (statuses != null && !statuses.isEmpty()) {
                Set<Integer> deliveredSet = new HashSet<>();
                for (String s : statuses) {
                    if (s != null) {
                        String t = s.trim();
                        if ("已投递".equals(t)) deliveredSet.add(1);
                        if ("未投递".equals(t)) deliveredSet.add(0);
                    }
                }
                if (!deliveredSet.isEmpty()) {
                    wrapper.in("delivered", deliveredSet);
                }
            }
            if (location != null && !location.trim().isEmpty()) wrapper.eq("job_area", location.trim());
            if (experience != null && !experience.trim().isEmpty()) wrapper.eq("job_exp_req", experience.trim());
            if (degree != null && !degree.trim().isEmpty()) wrapper.eq("job_edu_req", degree.trim());

            if (keyword != null && !keyword.trim().isEmpty()) {
                String kw = keyword.trim();
                wrapper.and(w -> w.like("comp_name", kw)
                        .or().like("job_title", kw)
                        .or().like("hr_name", kw));
            }

            wrapper.orderByDesc("create_time");
            List<LiepinEntity> all = liepinMapper.selectList(wrapper);

            // 薪资区间过滤（按中位数K）
            List<LiepinEntity> filtered = new ArrayList<>();
            double sumMedian = 0.0; long countMedian = 0;
            for (LiepinEntity e : all) {
                SalaryInfo info = parseSalary(e.getJobSalaryText());
                boolean passSalary;
                if (minK == null && maxK == null) passSalary = true;
                else {
                    if (info == null || info.medianK == null) passSalary = false;
                    else {
                        boolean ok = true;
                        if (minK != null) ok = ok && (info.medianK >= minK);
                        if (maxK != null) ok = ok && (info.medianK <= maxK);
                        passSalary = ok;
                    }
                }
                if (passSalary) {
                    filtered.add(e);
                    if (info != null && info.medianK != null) { sumMedian += info.medianK; countMedian++; }
                }
            }

            // KPI
            resp.kpi.total = filtered.size();
            resp.kpi.delivered = filtered.stream().filter(e -> Objects.equals(e.getDelivered(), 1)).count();
            resp.kpi.pending = filtered.stream().filter(e -> e.getDelivered() == null || Objects.equals(e.getDelivered(), 0)).count();
            resp.kpi.filtered = 0;
            resp.kpi.failed = 0;
            resp.kpi.avgMonthlyK = countMedian > 0 ? Math.round((sumMedian / countMedian) * 100.0) / 100.0 : null;

            // Charts 聚合
            Map<String, Long> byStatus = filtered.stream()
                    .collect(Collectors.groupingBy(e -> Objects.equals(e.getDelivered(), 1) ? "已投递" : "未投递", Collectors.counting()));
            byStatus.forEach((k, v) -> charts.byStatus.add(new NameValue(k, v)));

            Map<String, Long> byCity = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getJobArea()), Collectors.counting()));
            byCity.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(en -> charts.byCity.add(new NameValue(en.getKey(), en.getValue())));

            Map<String, Long> byIndustry = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getCompIndustry()), Collectors.counting()));
            byIndustry.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(en -> charts.byIndustry.add(new NameValue(en.getKey(), en.getValue())));

            Map<String, Long> byCompany = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getCompName()), Collectors.counting()));
            byCompany.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(en -> charts.byCompany.add(new NameValue(en.getKey(), en.getValue())));

            Map<String, Long> byExp = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getJobExpReq()), Collectors.counting()));
            byExp.forEach((k, v) -> charts.byExperience.add(new NameValue(k, v)));

            Map<String, Long> byDeg = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getJobEduReq()), Collectors.counting()));
            byDeg.forEach((k, v) -> charts.byDegree.add(new NameValue(k, v)));

            Map<String, Long> byDay = filtered.stream()
                    .collect(Collectors.groupingBy(e -> {
                        LocalDateTime t = e.getCreateTime();
                        return t == null ? "未知" : String.format("%04d-%02d-%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth());
                    }, Collectors.counting()));
            byDay.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(en -> charts.dailyTrend.add(new NameValue(en.getKey(), en.getValue())));

            Map<String, Long> hrAct = filtered.stream()
                    .filter(e -> e.getHrName() != null && !e.getHrName().trim().isEmpty())
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getHrName()), Collectors.counting()));
            hrAct.forEach((k, v) -> charts.hrActivity.add(new NameValue(k, v)));

            // 薪资桶
            long b0_10=0,b10_15=0,b15_20=0,b20_top=0,b_ge_top=0;
            double maxMedian = 0.0;
            List<Double> medians = new ArrayList<>();
            for (LiepinEntity e : filtered) {
                SalaryInfo info = parseSalary(e.getJobSalaryText());
                if (info == null || info.medianK == null) continue;
                double m = info.medianK;
                medians.add(m);
                if (m > maxMedian) maxMedian = m;
            }
            int topEdge = (int) Math.ceil(maxMedian / 5.0) * 5;
            if (topEdge <= 20) topEdge = 25;
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
            log.error("获取猎聘统计失败: {}", e.getMessage(), e);
            resp.charts = charts;
            return resp;
        }
    }

    /**
     * 列表查询（分页 + 筛选 + 关键词 + 薪资区间基于中位数K）
     */
    public PagedResult listLiepinJobs(
            List<String> statuses,
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

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<LiepinEntity> wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();

        if (statuses != null && !statuses.isEmpty()) {
            Set<Integer> deliveredSet = new HashSet<>();
            for (String s : statuses) {
                if (s != null) {
                    String t = s.trim();
                    if ("已投递".equals(t)) deliveredSet.add(1);
                    if ("未投递".equals(t)) deliveredSet.add(0);
                }
            }
            if (!deliveredSet.isEmpty()) wrapper.in("delivered", deliveredSet);
        }
        if (location != null && !location.trim().isEmpty()) wrapper.eq("job_area", location.trim());
        if (experience != null && !experience.trim().isEmpty()) wrapper.eq("job_exp_req", experience.trim());
        if (degree != null && !degree.trim().isEmpty()) wrapper.eq("job_edu_req", degree.trim());

        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("comp_name", kw)
                    .or().like("job_title", kw)
                    .or().like("hr_name", kw));
        }

        wrapper.orderByDesc("create_time");
        List<LiepinEntity> all = liepinMapper.selectList(wrapper);

        List<LiepinEntity> filtered = new ArrayList<>();
        for (LiepinEntity e : all) {
            if (minK == null && maxK == null) {
                filtered.add(e);
            } else {
                SalaryInfo info = parseSalary(e.getJobSalaryText());
                if (info == null || info.medianK == null) continue;
                boolean ok = true;
                if (minK != null) ok = ok && (info.medianK >= minK);
                if (maxK != null) ok = ok && (info.medianK <= maxK);
                if (ok) filtered.add(e);
            }
        }

        int total = filtered.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);

        PagedResult pr = new PagedResult();
        pr.items = filtered.subList(from, to);
        pr.total = total;
        pr.page = page;
        pr.size = size;
        return pr;
    }
}
