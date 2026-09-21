package com.getjobs.worker.manager;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

}
