package com.getjobs.worker.manager;

import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BrowserSessionSnapshotTest {
    @Test
    void filtersCookieHeaderByDomainAndExposesNamedValues() {
        Cookie session = new Cookie("session", "abc");
        session.domain = "api.liepin.com";
        Cookie xsrf = new Cookie("XSRF-TOKEN", "token");
        xsrf.domain = ".liepin.com";
        Cookie other = new Cookie("other", "ignored");
        other.domain = "example.com";

        BrowserSessionSnapshot snapshot = new BrowserSessionSnapshot(List.of(session, xsrf, other));

        assertEquals("session=abc; XSRF-TOKEN=token", snapshot.cookieHeader("liepin.com"));
        assertEquals("token", snapshot.value("XSRF-TOKEN"));
        assertFalse(snapshot.cookieHeader("other.com").contains("session=abc"));
    }

    @Test
    void exposesUnifiedReadFallbackState() {
        ReadRequestStatus status = ReadRequestStatus.browser(true, "HTTP响应为空");

        assertEquals("browser", status.route());
        assertEquals(true, status.fallback());
        assertEquals("HTTP响应为空", status.asMap().get("detail"));
    }
}
