package com.jobradar.application.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent, platform-neutral funnel state for one job. */
@Service
@Slf4j
@RequiredArgsConstructor
public class JobFunnelService {
    /** The ten user-visible funnel stages; retryable is an auxiliary queue state. */
    public static final List<String> STAGES = List.of(
            "collected", "detail_link", "jd", "ai_valid", "ai_pass", "review",
            "button_visible", "chat_success", "formal_apply_success", "external_pending"
    );

    private final DataSource dataSource;

    @PostConstruct
    public void ensureTable() {
        String sql = "CREATE TABLE IF NOT EXISTS job_funnel_state ("
                + " platform VARCHAR(32) NOT NULL,"
                + " job_id VARCHAR(128) NOT NULL,"
                + " collected INTEGER NOT NULL DEFAULT 0,"
                + " detail_link INTEGER NOT NULL DEFAULT 0,"
                + " jd INTEGER NOT NULL DEFAULT 0,"
                + " ai_valid INTEGER NOT NULL DEFAULT 0,"
                + " ai_pass INTEGER NOT NULL DEFAULT 0,"
                + " review INTEGER NOT NULL DEFAULT 0,"
                + " button_visible INTEGER NOT NULL DEFAULT 0,"
                + " chat_success INTEGER NOT NULL DEFAULT 0,"
                + " formal_apply_success INTEGER NOT NULL DEFAULT 0,"
                + " external_pending INTEGER NOT NULL DEFAULT 0,"
                + " retryable INTEGER NOT NULL DEFAULT 0,"
                + " last_reason TEXT,"
                + " updated_at DATETIME,"
                + " PRIMARY KEY (platform, job_id)"
                + ")";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize job funnel state", e);
        }
    }

    public void mark(String platform, Object jobId, String stage) {
        if (platform == null || platform.isBlank() || jobId == null || stage == null
                || (!STAGES.contains(stage) && !"retryable".equals(stage))) return;
        String normalizedPlatform = platform.trim();
        String normalizedJobId = String.valueOf(jobId).trim();
        if (normalizedJobId.isEmpty()) return;
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO job_funnel_state (platform, job_id, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
                insert.setString(1, normalizedPlatform);
                insert.setString(2, normalizedJobId);
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE job_funnel_state SET " + stage + " = 1, updated_at = CURRENT_TIMESTAMP WHERE platform = ? AND job_id = ?")) {
                update.setString(1, normalizedPlatform);
                update.setString(2, normalizedJobId);
                update.executeUpdate();
            }
        } catch (Exception e) {
            log.warn("Unable to update funnel state platform={} jobId={} stage={}: {}",
                    normalizedPlatform, normalizedJobId, stage, e.getMessage());
        }
    }

    public void markReason(String platform, Object jobId, String stage, String reason) {
        mark(platform, jobId, stage);
        if (platform == null || jobId == null || reason == null || reason.isBlank()) return;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE job_funnel_state SET last_reason = ?, updated_at = CURRENT_TIMESTAMP WHERE platform = ? AND job_id = ?")) {
            statement.setString(1, reason.trim());
            statement.setString(2, platform.trim());
            statement.setString(3, String.valueOf(jobId).trim());
            statement.executeUpdate();
        } catch (Exception e) {
            log.debug("Unable to save funnel reason: {}", e.getMessage());
        }
    }

    public void collected(String platform, Object jobId) { mark(platform, jobId, "collected"); }
    public void detailLink(String platform, Object jobId) { mark(platform, jobId, "detail_link"); }
    public void jd(String platform, Object jobId) { mark(platform, jobId, "jd"); }
    public void aiValid(String platform, Object jobId) { mark(platform, jobId, "ai_valid"); }
    public void aiPass(String platform, Object jobId) { mark(platform, jobId, "ai_pass"); }
    public void review(String platform, Object jobId, String reason) { markReason(platform, jobId, "review", reason); }
    public void buttonVisible(String platform, Object jobId) { mark(platform, jobId, "button_visible"); }
    public void chatSuccess(String platform, Object jobId) { mark(platform, jobId, "chat_success"); }
    public void formalApplySuccess(String platform, Object jobId) { mark(platform, jobId, "formal_apply_success"); }
    public void externalPending(String platform, Object jobId) { mark(platform, jobId, "external_pending"); }
    public void retryable(String platform, Object jobId, String reason) { markReason(platform, jobId, "retryable", reason); }
    public void clearRetryable(String platform, Object jobId) {
        if (platform == null || jobId == null) return;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE job_funnel_state SET retryable = 0, updated_at = CURRENT_TIMESTAMP WHERE platform = ? AND job_id = ?")) {
            statement.setString(1, platform.trim());
            statement.setString(2, String.valueOf(jobId).trim());
            statement.executeUpdate();
        } catch (Exception e) {
            log.debug("Unable to clear funnel retryable state: {}", e.getMessage());
        }
    }

    public Map<String, Object> getFunnel(String platform) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("platform", platform == null ? "" : platform);
        Map<String, Long> stages = new LinkedHashMap<>();
        for (String stage : STAGES) stages.put(stage, 0L);
        long retryable = 0;
        if (platform != null && !platform.isBlank()) {
            String columns = String.join(", ", STAGES) + ", retryable";
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + columns + " FROM job_funnel_state WHERE platform = ?")) {
                statement.setString(1, platform.trim());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        for (int i = 0; i < STAGES.size(); i++) {
                            String stage = STAGES.get(i);
                            stages.put(stage, stages.get(stage) + rows.getLong(i + 1));
                        }
                        retryable += rows.getLong(STAGES.size() + 1);
                    }
                }
            } catch (Exception e) {
                log.warn("Unable to read funnel state platform={}: {}", platform, e.getMessage());
            }
        }
        response.put("stages", stages);
        response.put("retryable", retryable);
        return response;
    }
}
