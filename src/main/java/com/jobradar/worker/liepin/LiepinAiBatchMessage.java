package com.jobradar.worker.liepin;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;

/** Strict parser for the batch AI message response. */
public final class LiepinAiBatchMessage {
    private LiepinAiBatchMessage() {
    }

    public record Item(String jobId, String message) {
    }

    public record Result(List<Item> items) {
    }

    public static Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Object parsed = new JSONTokener(stripMarkdownFence(raw.trim())).nextValue();
            if (!(parsed instanceof JSONObject root) || !(root.opt("items") instanceof JSONArray items)) {
                return null;
            }
            List<Item> result = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                if (!(items.get(i) instanceof JSONObject item)) {
                    return null;
                }
                String jobId = readJobId(item.opt("jobId"));
                String message = readText(item.opt("message"));
                if (jobId == null || message == null || message.indexOf('\n') >= 0
                        || message.indexOf('\r') >= 0 || message.length() > 60
                        || message.contains("```") || message.contains("{{")) {
                    return null;
                }
                result.add(new Item(jobId, message));
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
