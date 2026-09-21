package com.jobradar.application.service;

import java.util.List;
import java.util.Locale;

/** Shared default keyword sets for strict and fallback medical searches. */
public final class SearchProfileKeywords {
    public static final String STRICT = "A";
    public static final String FALLBACK = "B";
    public static final String CUSTOM = "CUSTOM";

    private SearchProfileKeywords() {
    }

    public static String normalize(String profile, boolean hasStoredKeywords) {
        if (profile == null || profile.isBlank()) return hasStoredKeywords ? CUSTOM : STRICT;
        String value = profile.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case STRICT, FALLBACK, CUSTOM -> value;
            default -> hasStoredKeywords ? CUSTOM : STRICT;
        };
    }

    public static List<String> defaults(String profile) {
        return FALLBACK.equals(normalize(profile, false))
                ? List.of("病理 IVD", "IVD 应用工程师", "临床协调员", "临床项目协调", "医疗器械产品专员", "医疗运营")
                : List.of("放疗应用工程师", "放疗临床应用", "放疗产品应用", "直线加速器应用", "放疗设备应用", "放疗产品专员");
    }
}
