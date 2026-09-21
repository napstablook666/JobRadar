package com.jobradar.worker.manager;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PlaywrightManagerModeTest {

    @Test
    void defaultsToBackgroundMode() {
        assertEquals(PlaywrightManager.BrowserMode.BACKGROUND,
                PlaywrightManager.normalizeBrowserMode(null));
        assertEquals(PlaywrightManager.BrowserMode.BACKGROUND,
                PlaywrightManager.normalizeBrowserMode("background"));
    }

    @Test
    void acceptsVisibleLoginAlias() {
        assertEquals(PlaywrightManager.BrowserMode.VISIBLE_LOGIN,
                PlaywrightManager.normalizeBrowserMode("visible-login"));
        assertEquals(PlaywrightManager.BrowserMode.VISIBLE_LOGIN,
                PlaywrightManager.normalizeBrowserMode("visible"));
    }

    @Test
    void unknownModeFallsBackToBackground() {
        assertEquals(PlaywrightManager.BrowserMode.BACKGROUND,
                PlaywrightManager.normalizeBrowserMode("unexpected"));
    }

    @Test
    void backgroundWindowArgsStayHeadless() {
        List<String> args = PlaywrightManager.buildSystemChromeWindowArgs(
                PlaywrightManager.BrowserMode.BACKGROUND);

        org.junit.jupiter.api.Assertions.assertTrue(args.contains("--headless=new"));
        org.junit.jupiter.api.Assertions.assertFalse(args.contains("--start-maximized"));
    }

    @Test
    void visibleLoginWindowArgsKeepAVisibleWindow() {
        List<String> args = PlaywrightManager.buildSystemChromeWindowArgs(
                PlaywrightManager.BrowserMode.VISIBLE_LOGIN);

        org.junit.jupiter.api.Assertions.assertTrue(args.contains("--start-maximized"));
        org.junit.jupiter.api.Assertions.assertFalse(args.contains("--headless=new"));
    }

    @Test
    void normalizesBrowserRuntimeChoices() {
        assertEquals(BrowserRuntimeConfig.TransportMode.HYBRID,
                BrowserRuntimeConfig.normalizeTransport(null));
        assertEquals(BrowserRuntimeConfig.TransportMode.BROWSER,
                BrowserRuntimeConfig.normalizeTransport("browser"));
        assertEquals(BrowserRuntimeConfig.Engine.SYSTEM_CDP,
                BrowserRuntimeConfig.normalizeEngine("chrome"));
        assertEquals(BrowserRuntimeConfig.Engine.HEADLESS_SHELL,
                BrowserRuntimeConfig.normalizeEngine("shell"));
        assertEquals(BrowserRuntimeConfig.PagePolicy.LAZY,
                BrowserRuntimeConfig.normalizePagePolicy("unexpected"));
        assertEquals(BrowserRuntimeConfig.PagePolicy.EAGER,
                BrowserRuntimeConfig.normalizePagePolicy("eager"));
    }

    @Test
    void treatsLiepinSecurityVerificationPagesAsLoggedOut() {
        assertEquals(true, PlatformBrowserRuntime.isSecurityVerificationUrl(
                "https://safe.liepin.com/page/liepin/captchaPage_ip_PC"));
        assertEquals(true, PlatformBrowserRuntime.isSecurityVerificationUrl(
                "https://www.liepin.com/security-verification"));
        assertEquals(false, PlatformBrowserRuntime.isSecurityVerificationUrl(
                "https://www.liepin.com/zhaopin/"));
    }

    @Test
    void platformSnapshotDoesNotWaitForAnotherPlatformRuntime() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        setField(manager, "isolatedRuntime", true);
        setField(manager, "initializationComplete", true);

        PlatformBrowserRuntime job51 = runtime("51job", "51job.com");
        PlatformBrowserRuntime liepin = runtime("liepin", "liepin.com");
        manager.getPlatformRuntimes().put("51job", job51);
        manager.getPlatformRuntimes().put("liepin", liepin);

        ReentrantLock liepinLock = (ReentrantLock) field(liepin, "accessLock");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            liepinLock.lock();
            try {
                locked.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                liepinLock.unlock();
            }
        });
        holder.start();
        assertEquals(true, locked.await(1, TimeUnit.SECONDS));
        try {
            assertTimeoutPreemptively(Duration.ofMillis(250), () -> manager.getSessionSnapshot("51job"));
        } finally {
            release.countDown();
            holder.join(1_000);
        }
    }

    private static PlatformBrowserRuntime runtime(String platform, String domain) {
        return new PlatformBrowserRuntime(platform, "https://www." + domain, domain,
                true, BrowserRuntimeConfig.Engine.AUTO, Path.of("."), null, ignored -> {
                });
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
