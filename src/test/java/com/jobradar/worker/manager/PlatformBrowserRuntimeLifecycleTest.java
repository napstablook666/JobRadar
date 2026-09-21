package com.jobradar.worker.manager;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class PlatformBrowserRuntimeLifecycleTest {

    @Test
    void rebuildsDisconnectedBrowserBeforeCreatingPage() {
        Resource first = resource();
        Resource second = resource();
        AtomicInteger created = new AtomicInteger();
        PlatformBrowserRuntime runtime = newRuntime(() -> created.incrementAndGet() == 1
                ? first.playwright : second.playwright);

        runtime.initialize();
        when(first.browser.isConnected()).thenReturn(false);

        Page page = runtime.ensurePageReady();

        assertTrue(page == second.page);
        assertTrue(created.get() == 2);
        verify(second.context).newPage();
    }

    @Test
    void statusMarksClosedRuntimeAsDisconnected() {
        Resource resource = resource();
        PlatformBrowserRuntime runtime = newRuntime(() -> resource.playwright);

        runtime.initialize();
        when(resource.browser.isConnected()).thenReturn(false);

        Map<String, Object> status = runtime.getStatus();

        assertFalse((Boolean) status.get("initialized"));
        assertFalse((Boolean) status.get("browserConnected"));
        assertFalse((Boolean) status.get("contextReady"));
        assertTrue("FAILED".equals(status.get("state")));
    }

    @Test
    void acceptsPersistentContextWithoutBrowserHandle() {
        Resource resource = resource();
        when(resource.context.browser()).thenReturn(null);
        PlatformBrowserRuntime runtime = newRuntime(() -> resource.playwright);

        runtime.initialize();

        assertTrue(runtime.isInitialized());
        assertTrue((Boolean) runtime.getStatus().get("contextReady"));
        assertTrue((Boolean) runtime.getStatus().get("browserConnected"));
    }

    @Test
    void persistentBackgroundContextUsesRegularChromeUserAgent() {
        Resource resource = resource();
        PlatformBrowserRuntime runtime = newRuntime(() -> resource.playwright);

        runtime.initialize();

        ArgumentCaptor<BrowserType.LaunchPersistentContextOptions> options =
                ArgumentCaptor.forClass(BrowserType.LaunchPersistentContextOptions.class);
        verify(resource.browserType).launchPersistentContext(any(Path.class), options.capture());
        String userAgent = options.getValue().userAgent;
        assertNotNull(userAgent);
        assertTrue(userAgent.contains("Chrome/134.0.0.0"));
        assertFalse(userAgent.contains("HeadlessChrome"));
    }

    @Test
    void retriesLiepinSpaUntilHomepageTitleAppears() {
        Resource resource = resource();
        when(resource.context.cookies()).thenReturn(List.of(
                new Cookie("lt_auth", "auth"),
                new Cookie("UniqueKey", "user")
        ));
        when(resource.page.url()).thenReturn("https://c.liepin.com/");
        when(resource.page.title()).thenReturn("", "", "我的首页");
        PlatformBrowserRuntime runtime = newRuntime(
                "liepin", "https://www.liepin.com/", "liepin.com", () -> resource.playwright);

        runtime.initialize();
        runtime.ensurePageReady();

        assertTrue(runtime.isLoggedIn());
        verify(resource.page, atLeastOnce()).waitForTimeout(500);
        verify(resource.page, atMost(2)).waitForTimeout(500);
    }

    @Test
    void loginStatusFallsBackToLoggedOutWhenRuntimeRecoveryFails() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        setField(manager, "isolatedRuntime", true);
        PlatformBrowserRuntime runtime = mock(PlatformBrowserRuntime.class);
        when(runtime.tryRefreshLoginStatus()).thenThrow(new IllegalStateException("TargetClosedError"));
        runtimeMap(manager).put("51job", runtime);

        assertFalse(manager.isLoggedIn("51job"));
    }

    @Test
    void loginStatusUsesNonBlockingRuntimeRefresh() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        setField(manager, "isolatedRuntime", true);
        PlatformBrowserRuntime runtime = mock(PlatformBrowserRuntime.class);
        when(runtime.tryRefreshLoginStatus()).thenReturn(true);
        runtimeMap(manager).put("51job", runtime);

        assertTrue(manager.isLoggedIn("51job"));
        verify(runtime).tryRefreshLoginStatus();
    }

    @Test
    void lazyRuntimeStatusIsUnknownUntilThePlatformIsCreated() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        setField(manager, "isolatedRuntime", true);

        assertFalse(manager.isLoginStatusKnown("51job"));

        PlatformBrowserRuntime runtime = mock(PlatformBrowserRuntime.class);
        when(runtime.isLoginKnown()).thenReturn(true);
        runtimeMap(manager).put("51job", runtime);

        assertTrue(manager.isLoginStatusKnown("51job"));
    }

    @Test
    void browserLoginCookieCallbackReportsSuccessfulRuntimeImport() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        setField(manager, "isolatedRuntime", true);
        PlatformBrowserRuntime runtime = mock(PlatformBrowserRuntime.class);
        when(runtime.importCookies(anyString(), eq("browser login")))
                .thenReturn(Map.of("loggedIn", true));
        runtimeMap(manager).put("51job", runtime);

        Method method = PlaywrightManager.class.getDeclaredMethod(
                "persistBrowserLoginCookies", String.class, String.class);
        method.setAccessible(true);
        BrowserLoginSession.PersistenceResult result =
                (BrowserLoginSession.PersistenceResult) method.invoke(manager, "51job", "[]");

        assertTrue(result.saved());
        assertTrue(result.loggedIn());
    }

    @Test
    void dropsExpiredCookiesButKeepsSessionCookies() throws Exception {
        Method parser = PlatformBrowserRuntime.class.getDeclaredMethod(
                "parseCookieJson", String.class, String.class);
        parser.setAccessible(true);
        String json = "[{"
                + "\"name\":\"expired\",\"value\":\"x\",\"domain\":\".51job.com\",\"expires\":1"
                + "},{\"name\":\"session\",\"value\":\"y\",\"domain\":\".51job.com\",\"expires\":-1"
                + "},{\"name\":\"future\",\"value\":\"z\",\"domain\":\".51job.com\",\"expires\":4102444800}]";

        @SuppressWarnings("unchecked")
        List<com.microsoft.playwright.options.Cookie> cookies =
                (List<com.microsoft.playwright.options.Cookie>) parser.invoke(null, json, "51job.com");

        assertEquals(List.of("session", "future"), cookies.stream().map(cookie -> cookie.name).toList());
    }

    @Test
    void job51AuthCookieFallbackRecognizesStoredSession() throws Exception {
        Resource resource = resource();
        when(resource.context.cookies()).thenReturn(List.of(
                new Cookie("uid", "user"),
                new Cookie("guid", "session")
        ));
        PlatformBrowserRuntime runtime = newRuntime(() -> resource.playwright);
        runtime.initialize();

        Method detector = PlatformBrowserRuntime.class.getDeclaredMethod("hasJob51AuthCookie");
        detector.setAccessible(true);

        assertTrue((Boolean) detector.invoke(runtime));
    }

    @Test
    void forceCloseInterruptsBlockedPlaywrightActionWithoutWaitingForAccessLock() throws Exception {
        Resource resource = resource();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        PlatformBrowserRuntime runtime = newRuntime(() -> resource.playwright);

        CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(() ->
                runtime.withAccessCancellable(() -> false, () -> {
                    actionStarted.countDown();
                    try {
                        releaseAction.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("操作已中断", interrupted);
                    }
                }));

        assertTrue(actionStarted.await(1, TimeUnit.SECONDS));
        runtime.forceClose();

        releaseAction.countDown();
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> running.get(1, TimeUnit.SECONDS));
        assertEquals("STOPPED", runtime.getStatus().get("state"));
        assertFalse((Boolean) runtime.getStatus().get("initialized"));
        assertEquals(0, runtime.activeOperations());
    }

    @Test
    void lazyLiepinRuntimeValidatesStoredCookiesOnFirstLoginCheck() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        setField(manager, "isolatedRuntime", true);
        setField(manager, "initializationComplete", true);

        Resource resource = resource();
        when(resource.context.cookies()).thenReturn(List.of(
                new Cookie("lt_auth", "auth"),
                new Cookie("UniqueKey", "user")
        ));
        when(resource.page.url()).thenReturn("https://c.liepin.com/");
        when(resource.page.title()).thenReturn("我的首页");

        com.jobradar.application.service.CookieService cookies =
                mock(com.jobradar.application.service.CookieService.class);
        com.jobradar.application.entity.CookieEntity stored =
                new com.jobradar.application.entity.CookieEntity();
        stored.setPlatform("liepin");
        stored.setCookieValue("[{\"name\":\"lt_auth\",\"value\":\"auth\",\"domain\":\".liepin.com\"},"
                + "{\"name\":\"UniqueKey\",\"value\":\"user\",\"domain\":\".liepin.com\"}]");
        when(cookies.getCookieByPlatform("liepin")).thenReturn(stored);
        setField(manager, "cookieService", cookies);
        setField(manager, "platformPlaywrightFactory", (java.util.function.Supplier<Playwright>) () -> resource.playwright);

        assertTrue(manager.isLoggedIn("liepin"));
        assertTrue(manager.isLoginStatusKnown("liepin"));
        verify(cookies, atLeastOnce()).getCookieByPlatform("liepin");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, PlatformBrowserRuntime> runtimeMap(PlaywrightManager manager) throws Exception {
        Field field = PlaywrightManager.class.getDeclaredField("platformRuntimes");
        field.setAccessible(true);
        return (Map<String, PlatformBrowserRuntime>) field.get(manager);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static PlatformBrowserRuntime newRuntime(java.util.function.Supplier<Playwright> factory) {
        return newRuntime("51job", "https://www.51job.com", "51job.com", factory);
    }

    private static PlatformBrowserRuntime newRuntime(
            String platform,
            String homeUrl,
            String domain,
            java.util.function.Supplier<Playwright> factory
    ) {
        return new PlatformBrowserRuntime(
                platform,
                homeUrl,
                domain,
                true,
                BrowserRuntimeConfig.Engine.AUTO,
                null,
                null,
                ignored -> {},
                factory
        );
    }

    private static Resource resource() {
        Playwright playwright = mock(Playwright.class);
        BrowserType browserType = mock(BrowserType.class);
        BrowserContext context = mock(BrowserContext.class);
        Browser browser = mock(Browser.class);
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);

        when(playwright.chromium()).thenReturn(browserType);
        when(browserType.launchPersistentContext(any(Path.class),
                any(BrowserType.LaunchPersistentContextOptions.class))).thenReturn(context);
        when(context.browser()).thenReturn(browser);
        when(context.pages()).thenReturn(List.of());
        when(context.newPage()).thenReturn(page);
        when(browser.isConnected()).thenReturn(true);
        when(page.isClosed()).thenReturn(false);
        when(page.locator(anyString())).thenReturn(locator);
        when(locator.first()).thenReturn(locator);
        when(locator.count()).thenReturn(0);
        return new Resource(playwright, browserType, context, browser, page);
    }

    private record Resource(
            Playwright playwright,
            BrowserType browserType,
            BrowserContext context,
            Browser browser,
            Page page
    ) {
    }
}
