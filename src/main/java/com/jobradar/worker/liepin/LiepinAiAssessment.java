package com.jobradar.worker.liepin;

import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * AI 自动投递评分结果。只接受结构完整、分数在 0-100 之间的 JSON。
 */
public record LiepinAiAssessment(int score, String reason, String message) {

    public static LiepinAiAssessment parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String json = stripMarkdownFence(raw.trim());
        if (!json.startsWith("{") || !json.endsWith("}")) {
            return null;
        }

        try {
            JSONTokener tokenizer = new JSONTokener(json);
            Object parsed = tokenizer.nextValue();
            if (tokenizer.nextClean() != 0) {
                return null;
            }
            if (!(parsed instanceof JSONObject object)) {
                return null;
            }

            Integer score = readScore(object.opt("score"));
            String reason = readText(object.opt("reason"));
            String message = readText(object.opt("message"));
            if (score == null || score < 0 || score > 100 || reason == null || message == null) {
                return null;
            }
            return new LiepinAiAssessment(score, reason, message);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public boolean passes(int minimumScore) {
        return score >= minimumScore;
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
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3).trim();
        }
        return content;
    }

    private static Integer readScore(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) {
                return null;
            }
            return (int) numeric;
        }
        if (value instanceof String text && text.matches("\\d{1,3}")) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String readText(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
