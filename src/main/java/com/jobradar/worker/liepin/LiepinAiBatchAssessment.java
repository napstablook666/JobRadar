package com.jobradar.worker.liepin;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict parser for the batch AI screening response. */
public final class LiepinAiBatchAssessment {
    private LiepinAiBatchAssessment() {
    }

    private static final Set<String> ALLOWED_REASON_CODES = Set.of(
            "DIRECTION_MATCH", "SKILL_MATCH", "EXPERIENCE_MATCH",
            "SALARY_RISK", "HARD_MISMATCH", "INSUFFICIENT_INFO"
    );

    public record Item(
            String jobId,
            int score,
            String decision,
            List<String> reasonCodes,
            String reason
    ) {
    }

    public record Result(List<Item> items) {
    }

    public static Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Object parsed = new JSONTokener(stripMarkdownFence(raw.trim())).nextValue();
            if (!(parsed instanceof JSONObject root) || !root.has("items")) {
                return null;
            }
            Object rawItems = root.get("items");
            if (!(rawItems instanceof JSONArray items)) {
                return null;
            }
            if (items.isEmpty()) {
                return null;
            }

            List<Item> result = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                Object rawItem = items.get(i);
                if (!(rawItem instanceof JSONObject item)) {
                    return null;
                }
                String jobId = readJobId(item.opt("jobId"));
                Integer score = readScore(item.opt("score"));
                String decision = readDecision(item.opt("decision"));
                String reason = readText(item.opt("reason"));
                JSONArray rawCodes = item.optJSONArray("reasonCodes");
                if (jobId == null || score == null || decision == null || reason == null
                        || reason.length() > 40 || rawCodes == null) {
                    return null;
                }
                List<String> reasonCodes = new ArrayList<>();
                for (int codeIndex = 0; codeIndex < rawCodes.length(); codeIndex++) {
                    String code = readText(rawCodes.opt(codeIndex));
                    if (code == null || !ALLOWED_REASON_CODES.contains(code)) {
                        return null;
                    }
                    reasonCodes.add(code);
                }
                result.add(new Item(jobId, score, decision, List.copyOf(reasonCodes), reason));
            }
            return new Result(List.copyOf(result));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String readJobId(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) return null;
            return String.valueOf(number.longValue());
        }
        return readText(value);
    }

    private static Integer readScore(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double score = number.doubleValue();
        if (!Double.isFinite(score) || score != Math.rint(score) || score < 0 || score > 100) {
            return null;
        }
        return (int) score;
    }

    private static String readDecision(Object value) {
        String decision = readText(value);
        if (decision == null) {
            return null;
        }
        return switch (decision) {
            case "PASS", "REVIEW", "SKIP" -> decision;
            default -> null;
        };
    }

    private static String readText(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String stripMarkdownFence(String raw) {
        if (!raw.startsWith("```")) {
            return raw;
        }
        int firstLineEnd = raw.indexOf('\n');
        if (firstLineEnd < 0) {
            return "";
        }
        String content = raw.substring(firstLineEnd + 1).trim();
        return content.endsWith("```")
                ? content.substring(0, content.length() - 3).trim()
                : content;
    }
}
