package com.jobradar.worker.manager;

import com.microsoft.playwright.options.Cookie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable cookie view used when a read path leaves the page runtime. */
public record BrowserSessionSnapshot(List<Cookie> cookies) {
    public BrowserSessionSnapshot {
        cookies = cookies == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(cookies));
    }

    public String cookieHeader(String domainSuffix) {
        if (domainSuffix == null || domainSuffix.isBlank()) {
            return "";
        }
        String suffix = domainSuffix.toLowerCase();
        return cookies.stream()
                .filter(cookie -> cookie != null && cookie.name != null && cookie.value != null)
                .filter(cookie -> cookie.domain != null
                        && (cookie.domain.equalsIgnoreCase(suffix)
                        || cookie.domain.toLowerCase().endsWith("." + suffix)))
                .map(cookie -> cookie.name + "=" + cookie.value)
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    public String value(String name) {
        return value(name, null);
    }

    public String value(String name, String domainSuffix) {
        if (name == null) {
            return null;
        }
        String normalizedSuffix = domainSuffix == null || domainSuffix.isBlank()
                ? null
                : domainSuffix.toLowerCase();
        return cookies.stream()
        .filter(cookie -> cookie != null && name.equals(cookie.name))
                .filter(cookie -> normalizedSuffix == null
                        || (cookie.domain != null
                        && (cookie.domain.equalsIgnoreCase(normalizedSuffix)
                        || cookie.domain.toLowerCase().endsWith("." + normalizedSuffix))))
                .map(cookie -> cookie.value)
                .findFirst()
                .orElse(null);
    }
}
