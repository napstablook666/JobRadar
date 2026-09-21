package com.getjobs.worker.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/** Parses keyword input from the UI and legacy stored formats. */
public final class KeywordParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KeywordParser() {
    }

    public static List<String> parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();

        String text = raw.trim().replace('，', ',');
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                JsonNode node = MAPPER.readTree(text);
                if (node.isArray()) {
                    List<String> values = new ArrayList<>();
                    node.forEach(item -> {
                        String value = repairUtf8Mojibake(item.asText("").trim());
                        if (!value.isEmpty()) values.add(value);
                    });
                    return values;
                }
            } catch (Exception ignored) {
                // Fall through for legacy bracket lists such as [a,b].
            }
            text = text.substring(1, text.length() - 1);
        }

        return Arrays.stream(text.split("[,\\r\\n]+"))
                .map(String::trim)
                .map(KeywordParser::stripWrapperQuotes)
                .map(KeywordParser::repairUtf8Mojibake)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }

    /** Restores legacy text that was repeatedly decoded as Latin-1 instead of UTF-8. */
    public static String repairUtf8Mojibake(String value) {
        if (value == null || value.isEmpty()) return value;

        String current = value;
        for (int round = 0; round < 3; round++) {
            if (!isLatin1(current)) break;
            byte[] bytes = new byte[current.length()];
            for (int index = 0; index < current.length(); index++) {
                bytes[index] = (byte) current.charAt(index);
            }
            String candidate = new String(bytes, StandardCharsets.UTF_8);
            if (candidate.indexOf('\uFFFD') >= 0 || candidate.equals(current)) break;
            current = candidate;
        }
        return current;
    }

    /** Serializes parsed keywords so the next configuration save removes legacy corruption. */
    public static String toStorage(String raw) {
        try {
            return MAPPER.writeValueAsString(parse(raw));
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private static boolean isLatin1(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0xFF) return false;
        }
        return true;
    }

    private static String stripWrapperQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
