package com.getjobs.worker.manager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Common observable state for platform read paths and browser fallbacks. */
public record ReadRequestStatus(String route, boolean fallback, String detail) {
    public ReadRequestStatus {
        route = route == null || route.isBlank()
                ? "browser"
                : route.trim().toLowerCase(Locale.ROOT);
        detail = detail == null || detail.isBlank() ? "" : detail.trim();
    }

    public static ReadRequestStatus http(String detail) {
        return new ReadRequestStatus("http", false, detail);
    }

    public static ReadRequestStatus browser(boolean fallback, String detail) {
        return new ReadRequestStatus("browser", fallback, detail);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", route);
        result.put("fallback", fallback);
        result.put("detail", detail);
        return result;
    }
}
