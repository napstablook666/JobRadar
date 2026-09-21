package com.getjobs.worker.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
                        String value = item.asText("").trim();
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
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }

    private static String stripWrapperQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
