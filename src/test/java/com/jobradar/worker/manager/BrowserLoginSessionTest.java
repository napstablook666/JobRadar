package com.jobradar.worker.manager;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserLoginSessionTest {

    @Test
    void restoresStoredCookiesBeforeClickingHomepageLoginEntry() {
        Playwright playwright = mock(Playwright.class);
        BrowserType browserType = mock(BrowserType.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        Locator emptyLocator = mock(Locator.class);
        Locator loginEntry = mock(Locator.class);
        when(playwright.chromium()).thenReturn(browserType);
        when(browserType.launchPersistentContext(any(Path.class),
                any(BrowserType.LaunchPersistentContextOptions.class))).thenReturn(context);
        when(context.pages()).thenReturn(List.of());
        when(context.newPage()).thenReturn(page);
        when(page.isClosed()).thenReturn(false);
        when(page.url()).thenReturn("https://www.zhipin.com");
        when(page.locator(anyString())).thenReturn(emptyLocator);
        when(emptyLocator.first()).thenReturn(emptyLocator);
        when(emptyLocator.count()).thenReturn(0);
        when(page.locator("li.nav-sign a")).thenReturn(loginEntry);
        when(loginEntry.first()).thenReturn(loginEntry);
        when(loginEntry.count()).thenReturn(1);
        when(loginEntry.isVisible()).thenReturn(true);
        when(loginEntry.textContent()).thenReturn("登录/注册");

        BrowserLoginSession session = new BrowserLoginSession(
                "boss", "https://www.zhipin.com", "zhipin.com",
                "https://www.zhipin.com/web/user/?ka=header-login",
                raw -> new BrowserLoginSession.PersistenceResult(false, false),
                "platform-login-test", () -> playwright);
        Cookie cookie = new Cookie("session", "value");
        cookie.domain = ".zhipin.com";
        cookie.path = "/";

        try {
            session.open(new BrowserSessionSnapshot(List.of(cookie)));

            InOrder order = inOrder(context, page);
            order.verify(context).addCookies(List.of(cookie));
            order.verify(page).navigate(eq("https://www.zhipin.com"), any(Page.NavigateOptions.class));
            verify(loginEntry).click();
            verify(page, org.mockito.Mockito.never()).navigate(
                    eq("https://www.zhipin.com/web/user/?ka=header-login"), any(Page.NavigateOptions.class));
        } finally {
            session.close();
        }
        verify(context).close();
    }

    @Test
    void fallsBackToBossLoginUrlWhenHomepageHasNoLoginEntry() {
        Playwright playwright = mock(Playwright.class);
        BrowserType browserType = mock(BrowserType.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(playwright.chromium()).thenReturn(browserType);
        when(browserType.launchPersistentContext(any(Path.class),
                any(BrowserType.LaunchPersistentContextOptions.class))).thenReturn(context);
        when(context.pages()).thenReturn(List.of());
        when(context.newPage()).thenReturn(page);
        when(page.isClosed()).thenReturn(false);
        when(page.url()).thenReturn("about:blank");
        when(page.locator(anyString())).thenReturn(locator);
        when(locator.first()).thenReturn(locator);
        when(locator.count()).thenReturn(0);

        BrowserLoginSession session = new BrowserLoginSession(
                "boss", "https://www.zhipin.com", "zhipin.com",
                "https://www.zhipin.com/web/user/?ka=header-login",
                raw -> new BrowserLoginSession.PersistenceResult(false, false),
                "platform-login-fallback-test", () -> playwright);
        try {
            session.open(new BrowserSessionSnapshot(List.of()));

            InOrder order = inOrder(page);
            order.verify(page).navigate(eq("https://www.zhipin.com"), any(Page.NavigateOptions.class));
            order.verify(page).navigate(eq("https://www.zhipin.com/web/user/?ka=header-login"),
                    any(Page.NavigateOptions.class));
        } finally {
            session.close();
        }
        verify(context).close();
    }

    @Test
    void verificationSessionUsesSystemChromeWithoutNoSandboxDefaultArg() {
        Playwright playwright = mock(Playwright.class);
        BrowserType browserType = mock(BrowserType.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(playwright.chromium()).thenReturn(browserType);
        when(browserType.launchPersistentContext(any(Path.class),
                any(BrowserType.LaunchPersistentContextOptions.class))).thenReturn(context);
        when(context.pages()).thenReturn(List.of());
        when(context.newPage()).thenReturn(page);
        when(page.isClosed()).thenReturn(false);

        BrowserLoginSession session = new BrowserLoginSession(
                "51job", "https://www.51job.com", "51job.com",
                "https://jobs.51job.com/example.html",
                raw -> new BrowserLoginSession.PersistenceResult(false, false),
                "platform-verification-test", true, () -> playwright);
        try {
            session.openAt("https://jobs.51job.com/example.html", new BrowserSessionSnapshot(List.of()));
        } finally {
            session.close();
        }

        org.mockito.ArgumentCaptor<BrowserType.LaunchPersistentContextOptions> options =
                org.mockito.ArgumentCaptor.forClass(BrowserType.LaunchPersistentContextOptions.class);
        verify(browserType).launchPersistentContext(any(Path.class), options.capture());
        org.junit.jupiter.api.Assertions.assertNotNull(options.getValue().executablePath);
        org.junit.jupiter.api.Assertions.assertTrue(options.getValue().ignoreDefaultArgs.contains("--no-sandbox"));
        org.junit.jupiter.api.Assertions.assertFalse(options.getValue().args.contains("--disable-blink-features=AutomationControlled"));
    }
}
