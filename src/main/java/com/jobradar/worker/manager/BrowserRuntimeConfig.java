package com.jobradar.worker.manager;

import java.util.Locale;

/** Runtime choices for browser ownership, transport, and page lifecycle. */
public final class BrowserRuntimeConfig {
    public enum TransportMode {
        BROWSER,
        HYBRID
    }

    public enum Engine {
        AUTO,
        SYSTEM_CDP,
        BUNDLED,
        HEADLESS_SHELL
    }

    public enum PagePolicy {
        EAGER,
        LAZY
    }

    private BrowserRuntimeConfig() {
    }

    public static TransportMode normalizeTransport(String raw) {
        return "browser".equalsIgnoreCase(trim(raw))
                ? TransportMode.BROWSER
                : TransportMode.HYBRID;
    }

    public static Engine normalizeEngine(String raw) {
        return switch (trim(raw).toLowerCase(Locale.ROOT)) {
            case "system-cdp", "system", "chrome" -> Engine.SYSTEM_CDP;
            case "bundled", "chromium" -> Engine.BUNDLED;
            case "headless-shell", "shell" -> Engine.HEADLESS_SHELL;
            default -> Engine.AUTO;
        };
    }

    public static PagePolicy normalizePagePolicy(String raw) {
        return "eager".equalsIgnoreCase(trim(raw))
                ? PagePolicy.EAGER
                : PagePolicy.LAZY;
    }

    public static boolean isBlank(String raw) {
        return trim(raw).isBlank();
    }

    private static String trim(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
