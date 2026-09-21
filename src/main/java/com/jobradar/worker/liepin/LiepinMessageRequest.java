package com.jobradar.worker.liepin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 只负责改写猎聘发送请求中的消息字段，无法唯一定位时保持原文。
 */
public final class LiepinMessageRequest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LiepinMessageRequest() {
    }

    public record RewriteResult(String body, boolean rewritten, String field) {
    }

    public static RewriteResult rewrite(String postData, String message) {
        if (postData == null || postData.isBlank() || message == null || message.isBlank()) {
            return new RewriteResult(postData, false, null);
        }

        try {
            JsonNode root = MAPPER.readTree(postData);
            List<String> fields = new ArrayList<>();
            collectJsonFields(root, fields);
            if (fields.size() != 1) {
                return new RewriteResult(postData, false, null);
            }
            setJsonField(root, fields.get(0), message);
            return new RewriteResult(MAPPER.writeValueAsString(root), true, fields.get(0));
        } catch (Exception ignored) {
            return rewriteForm(postData, message);
        }
    }

    private static RewriteResult rewriteForm(String postData, String message) {
        String[] pairs = postData.split("&", -1);
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < pairs.length; i++) {
            int separator = pairs[i].indexOf('=');
            if (separator < 0) continue;
            String key = decode(pairs[i].substring(0, separator));
            String value = decode(pairs[i].substring(separator + 1));
            if (isMessageField(key) && !value.isBlank()) {
                candidates.add(i);
            }
        }
        if (candidates.size() != 1) {
            return new RewriteResult(postData, false, null);
        }
        int index = candidates.get(0);
        int separator = pairs[index].indexOf('=');
        String key = pairs[index].substring(0, separator);
        pairs[index] = key + "=" + encode(message);
        return new RewriteResult(String.join("&", pairs), true, decode(key));
    }

    private static void collectJsonFields(JsonNode node, List<String> fields) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isTextual() && !value.asText().isBlank() && isMessageField(entry.getKey())) {
                    fields.add(entry.getKey());
                } else {
                    collectJsonFields(value, fields);
                }
            });
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            array.forEach(value -> collectJsonFields(value, fields));
        }
    }

    private static void setJsonField(JsonNode node, String field, String message) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            var fields = object.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getKey().equals(field) && entry.getValue().isTextual()) {
                    object.put(entry.getKey(), message);
                    return;
                }
                setJsonField(entry.getValue(), field, message);
            }
        } else if (node.isArray()) {
            node.forEach(child -> setJsonField(child, field, message));
        }
    }

    private static boolean isMessageField(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "");
        return normalized.equals("message")
                || normalized.equals("msg")
                || normalized.equals("content")
                || normalized.equals("text")
                || normalized.equals("sendcontent")
                || normalized.equals("messagecontent")
                || normalized.equals("greeting")
                || normalized.equals("sayhi");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
