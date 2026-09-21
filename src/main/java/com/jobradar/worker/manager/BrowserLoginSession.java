package com.jobradar.worker.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns one visible, persistent browser used only for interactive platform login. */
final class BrowserLoginSession implements AutoCloseable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Result returned by the explicit capture persistence callback. */
    static record PersistenceResult(boolean saved, boolean loggedIn) {
    }

    private final String platform;
    private final String homeUrl;
    private final String domain;
    private final String loginUrl;
    private final Function<String, PersistenceResult> cookiePersistor;
    private final Path profileDirectory;
    private final Supplier<Playwright> playwrightFactory;
    private final boolean systemChrome;

    private Playwright playwright;
    private BrowserContext context;
    private Page page;
    private boolean loggedIn;
    private boolean saved;
    private String lastError;
    private long lastSavedAt;

    BrowserLoginSession(String platform,
                        String homeUrl,
                        String domain,
                        String loginUrl,
                        Function<String, PersistenceResult> cookiePersistor) {
        this(platform, homeUrl, domain, loginUrl, cookiePersistor, "platform-login");
    }

    BrowserLoginSession(String platform,
                        String homeUrl,
                        String domain,
                        String loginUrl,
                        Function<String, PersistenceResult> cookiePersistor,
                        String profileRoot) {
        this(platform, homeUrl, domain, loginUrl, cookiePersistor, profileRoot, false, Playwright::create);
    }

    BrowserLoginSession(String platform,
                        String homeUrl,
                        String domain,
                        String loginUrl,
                        Function<String, PersistenceResult> cookiePersistor,
                        String profileRoot,
                        Supplier<Playwright> playwrightFactory) {
        this(platform, homeUrl, domain, loginUrl, cookiePersistor, profileRoot, false, playwrightFactory);
    }

    BrowserLoginSession(String platform,
                        String homeUrl,
                        String domain,
                        String loginUrl,
                        Function<String, PersistenceResult> cookiePersistor,
                        String profileRoot,
                        boolean systemChrome,
                        Supplier<Playwright> playwrightFactory) {
        this.platform = platform;
        this.homeUrl = homeUrl;
        this.domain = domain;
        this.loginUrl = loginUrl;
        this.cookiePersistor = cookiePersistor;
        this.profileDirectory = Paths.get("browser-data", profileRoot, platform)
                .toAbsolutePath().normalize();
        this.systemChrome = systemChrome;
        this.playwrightFactory = playwrightFactory == null ? Playwright::create : playwrightFactory;
    }

    synchronized void open(BrowserSessionSnapshot initialSnapshot) {
        if (isOpen()) {
            return;
        }
        try {
            Files.createDirectories(profileDirectory);
            playwright = playwrightFactory.get();
            BrowserType.LaunchPersistentContextOptions options = createLaunchOptions();
            context = playwright.chromium().launchPersistentContext(profileDirectory, options);
            page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            page.setDefaultTimeout(30_000);
            if (initialSnapshot == null || initialSnapshot.cookies().isEmpty()) {
                navigateToLogin();
            } else {
                addSnapshotCookies(initialSnapshot);
                navigate(homeUrl);
                if (!pollStatus()) {
                    navigateToLogin();
                }
            }
            lastError = null;
        } catch (Exception e) {
            close();
            throw new IllegalStateException("打开浏览器登录窗口失败: " + platform, e);
        }
    }

    /** Opens a visible, non-persistent verification page with a runtime cookie snapshot. */
    synchronized void openAt(String targetUrl, BrowserSessionSnapshot snapshot) {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("验证页面链接为空");
        }
        if (isOpen()) {
            addSnapshotCookies(snapshot);
            navigate(targetUrl);
            return;
        }
        try {
            Files.createDirectories(profileDirectory);
            playwright = playwrightFactory.get();
            BrowserType.LaunchPersistentContextOptions options = createLaunchOptions();
            context = playwright.chromium().launchPersistentContext(profileDirectory, options);
            page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            page.setDefaultTimeout(30_000);
            addSnapshotCookies(snapshot);
            navigate(targetUrl);
            lastError = null;
        } catch (Exception e) {
            close();
            throw new IllegalStateException("打开验证浏览器窗口失败: " + platform, e);
        }
    }

    synchronized boolean waitForVerificationResolution(long timeoutMs, BooleanSupplier shouldCancel) {
        long deadline = System.currentTimeMillis() + Math.max(1_000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (shouldCancel != null && shouldCancel.getAsBoolean()) return false;
            if (!hasAccessVerification()) return true;
            try {
                page.waitForTimeout(300);
            } catch (Exception e) {
                lastError = "验证窗口已关闭";
                return false;
            }
        }
        lastError = "访问验证等待超时";
        return false;
    }

    synchronized BrowserSessionSnapshot snapshot() {
        if (!isOpen()) return new BrowserSessionSnapshot(List.of());
        try {
            return new BrowserSessionSnapshot(filterCookies(context.cookies()));
        } catch (Exception e) {
            return new BrowserSessionSnapshot(List.of());
        }
    }

    private void addSnapshotCookies(BrowserSessionSnapshot snapshot) {
        if (snapshot == null || snapshot.cookies().isEmpty() || context == null) return;
        List<Cookie> cookies = filterCookies(snapshot.cookies());
        if (!cookies.isEmpty()) context.addCookies(cookies);
    }

    private void navigate(String targetUrl) {
        page.navigate(targetUrl, new Page.NavigateOptions()
                .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
    }

    private BrowserType.LaunchPersistentContextOptions createLaunchOptions() {
        List<String> args = systemChrome
                ? List.of(
                "--no-first-run",
                "--no-default-browser-check",
                "--start-maximized"
        )
                : List.of(
                "--disable-blink-features=AutomationControlled",
                "--disable-infobars",
                "--no-first-run",
                "--no-default-browser-check",
                "--start-maximized"
        );
        BrowserType.LaunchPersistentContextOptions options =
                new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(false)
                        .setSlowMo(50)
                        .setArgs(args)
                        .setLocale("zh-CN")
                        .setTimezoneId("Asia/Shanghai")
                        .setExtraHTTPHeaders(Map.of("Accept-Language", "zh-CN,zh;q=0.9"));
        if (!systemChrome) {
            return options.setIgnoreDefaultArgs(List.of("--enable-automation"));
        }

        Path executable = resolveSystemChromeExecutable();
        if (executable == null) {
            throw new IllegalStateException("未找到系统 Chrome，无法打开 51job 验证窗口");
        }
        return options
                .setExecutablePath(executable)
                .setIgnoreDefaultArgs(List.of("--enable-automation", "--no-sandbox"));
    }

    static Path resolveSystemChromeExecutable() {
        List<Path> candidates = List.of(
                Paths.get(System.getProperty("user.home"), "AppData", "Local", "Google", "Chrome", "Application", "chrome.exe"),
                Paths.get("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"),
                Paths.get("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private boolean hasAccessVerification() {
        try {
            if (page.locator("p.waf-nc-title, script[name^='aliyunwaf_']").count() > 0) return true;
            String body = page.locator("body").innerText();
            return body != null && (body.contains("访问验证")
                    || body.contains("请按住滑块") || body.contains("滑动验证页面"));
        } catch (Exception e) {
            return true;
        }
    }

    /** Refreshes the visible login state without writing or importing cookies. */
    synchronized boolean pollStatus() {
        if (!isOpen()) {
            return false;
        }
        try {
            boolean current = detectLoginStatus();
            loggedIn = current;
            if (current) {
                lastError = null;
            }
            return current;
        } catch (Exception e) {
            lastError = "登录状态检测失败";
            return loggedIn;
        }
    }

    /** Captures the current platform cookies only after an explicit user action. */
    synchronized Map<String, Object> captureAndPersist() {
        Map<String, Object> result = new HashMap<>();
        result.put("platform", platform);
        result.put("open", isOpen());
        if (!isOpen()) {
            result.put("captured", false);
            result.put("saved", false);
            result.put("loggedIn", false);
            result.put("error", "登录浏览器尚未打开");
            return result;
        }

        try {
            try {
                loggedIn = detectLoginStatus();
            } catch (Exception detectionError) {
                loggedIn = false;
                lastError = "登录状态检测失败，但仍会尝试获取 Cookie";
            }

            List<Cookie> cookies = filterCookies(context.cookies());
            if (cookies.isEmpty()) {
                lastError = "当前浏览器没有可保存的该平台 Cookie";
                result.put("captured", false);
                result.put("saved", saved);
                result.put("loggedIn", loggedIn);
                result.put("error", lastError);
                return result;
            }

            String raw = OBJECT_MAPPER.writeValueAsString(cookiesToMaps(cookies));
            PersistenceResult persistence = cookiePersistor.apply(raw);
            if (persistence == null || !persistence.saved()) {
                lastError = "Cookie 已获取，但写入投递运行时失败";
                result.put("captured", true);
                result.put("saved", false);
                result.put("loggedIn", loggedIn);
                result.put("count", cookies.size());
                result.put("error", lastError);
                return result;
            }

            // 导入后的运行时会重新导航并检测登录态；优先采用这次结果，避免
            // 捕获瞬间页面仍停在跳转中的短暂误判。
            loggedIn = loggedIn || persistence.loggedIn();
            saved = true;
            lastSavedAt = System.currentTimeMillis();
            lastError = null;
            result.put("captured", true);
            result.put("saved", true);
            result.put("loggedIn", loggedIn);
            result.put("count", cookies.size());
            return result;
        } catch (Exception e) {
            lastError = "获取 Cookie 失败";
            result.put("captured", false);
            result.put("saved", saved);
            result.put("loggedIn", loggedIn);
            result.put("error", lastError);
            return result;
        }
    }

    synchronized Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("platform", platform);
        result.put("open", isOpen());
        result.put("loggedIn", loggedIn);
        result.put("saved", saved);
        if (lastSavedAt > 0) {
            result.put("savedAt", lastSavedAt);
        }
        if (lastError != null && !lastError.isBlank()) {
            result.put("error", lastError);
        }
        return result;
    }

    synchronized boolean isOpen() {
        return context != null && page != null && !page.isClosed();
    }

    @Override
    public synchronized void close() {
        try {
            if (page != null && !page.isClosed()) {
                page.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (context != null) {
                context.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
        }
        page = null;
        context = null;
        playwright = null;
        loggedIn = false;
        saved = false;
    }

    private void navigateToLogin() {
        if ("51job".equals(platform)) {
            page.navigate(loginUrl, new Page.NavigateOptions()
                    .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            Locator scan = page.locator("[data-sensor-id='sensor_login_wechatScan']").first();
            if (scan.count() > 0 && scan.isVisible()) {
                scan.click();
            }
            return;
        }
        if ("boss".equals(platform)) {
            if (!isBossHomePage()) {
                navigate(homeUrl);
            }
            if (!clickBossLoginEntry()) {
                page.navigate(loginUrl, new Page.NavigateOptions()
                        .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            }
            return;
        }
        page.navigate(loginUrl, new Page.NavigateOptions()
                .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        if ("zhilian".equals(platform)) {
            Locator qr = page.locator("div.zppp-panel-normal-bar__img").first();
            if (qr.count() > 0 && qr.isVisible()) {
                qr.click();
            }
        }
    }

    private boolean isBossHomePage() {
        String current = safeUrl();
        String base = homeUrl.endsWith("/") ? homeUrl.substring(0, homeUrl.length() - 1) : homeUrl;
        base = base.toLowerCase(Locale.ROOT);
        return current.equals(base)
                || current.equals(base + "/")
                || current.startsWith(base + "?")
                || current.startsWith(base + "#");
    }

    private boolean clickBossLoginEntry() {
        List<String> selectors = List.of(
                "li.nav-sign a",
                "li.nav-sign button",
                "a[href*='header-login']",
                ".btns",
                "text=登录/注册",
                "button:has-text('登录')"
        );
        for (String selector : selectors) {
            try {
                Locator entry = page.locator(selector).first();
                if (entry.count() == 0 || !entry.isVisible()) {
                    continue;
                }
                String text = textOf(entry);
                boolean explicitLoginLink = selector.contains("header-login");
                if (!explicitLoginLink && !text.contains("登录") && !text.contains("注册")) {
                    continue;
                }
                entry.click();
                return true;
            } catch (Exception e) {
                // 页面结构可能在异步渲染期间变化，继续尝试下一种入口。
            }
        }
        return false;
    }

    private boolean detectLoginStatus() {
        return switch (platform) {
            case "boss" -> detectBossLogin();
            case "liepin" -> detectLiepinLogin();
            case "51job" -> detectJob51Login();
            case "zhilian" -> detectZhilianLogin();
            default -> false;
        };
    }

    private boolean detectBossLogin() {
        if (visible("li.nav-figure span.label-text", "li.nav-figure")) {
            return true;
        }
        Locator login = page.locator("li.nav-sign a, .btns").first();
        if (login.count() > 0 && login.isVisible() && textOf(login).contains("登录")) {
            return false;
        }
        if (visibleText("登录/注册") || visibleText("注册")) {
            return false;
        }
        return visible("a[href*='/web/user/'], .user-nav, .nav-figure");
    }

    private boolean detectLiepinLogin() {
        String url = safeUrl();
        if (PlatformBrowserRuntime.isSecurityVerificationUrl(url)) {
            return false;
        }
        Locator login = page.locator(
                "#header-quick-menu-login, a[data-key='login'], button[data-key='login']"
        ).first();
        if (login.count() > 0 && login.isVisible() && textOf(login).contains("登录")) {
            return false;
        }
        if (visibleText("登录/注册") || visibleText("注册") || visibleText("安全验证")) {
            return false;
        }
        if (page.locator("#header-quick-menu-user-info, img.header-quick-menu-user-photo, .header-quick-menu-user-photo").count() > 0) {
            return true;
        }
        if (!hasLiepinAuthCookies()) {
            return false;
        }
        String title = safeTitle();
        return title.contains("我的首页") || !url.contains("c.liepin.com");
    }

    private boolean detectJob51Login() {
        Locator login = page.locator("span.login.loginBtnClick").first();
        if (login.count() > 0 && login.isVisible() && textOf(login).contains("登录")) {
            return false;
        }
        if (safeUrl().contains("login.51job.com/login")) {
            return false;
        }
        if (hasJob51AuthCookie()) {
            return true;
        }
        if (visibleText("登录/注册")) {
            return false;
        }
        return visible("a.uname.e_icon.at, a[href*='/pc/my/myjob'], .login-info, .user-info, .username");
    }

    private boolean detectZhilianLogin() {
        if (visible("a.home-header__c-no-login")
                || visibleText("登录/注册")
                || visibleText("安全验证")
                || safeUrl().contains("passport.zhaopin.com/login")) {
            return false;
        }
        if (safeUrl().contains("i.zhaopin.com")) {
            return true;
        }
        return visible(".home-header__c-user, .user-info, [class*='userCenter'], a[href*='/my']");
    }

    private boolean visible(String... selectors) {
        for (String selector : selectors) {
            Locator locator = page.locator(selector).first();
            if (locator.count() > 0 && locator.isVisible()) {
                return true;
            }
        }
        return false;
    }

    private String textOf(Locator locator) {
        try {
            return locator.textContent() == null ? "" : locator.textContent().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean visibleText(String text) {
        try {
            Locator locator = page.locator("text=" + text).first();
            return locator.count() > 0 && locator.isVisible();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String safeUrl() {
        try {
            return page.url() == null ? "" : page.url().toLowerCase();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String safeTitle() {
        try {
            return page.title() == null ? "" : page.title();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasLiepinAuthCookies() {
        return hasNonBlankCookie("lt_auth")
                && (hasNonBlankCookie("UniqueKey") || hasNonBlankCookie("user_name"));
    }

    private boolean hasJob51AuthCookie() {
        return hasNonBlankCookie("51job")
                || (hasNonBlankCookie("uid") && hasNonBlankCookie("guid"));
    }

    private boolean hasNonBlankCookie(String name) {
        try {
            for (Cookie cookie : context.cookies()) {
                if (cookie != null && name.equals(cookie.name)
                        && cookie.value != null && !cookie.value.isBlank()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private List<Cookie> filterCookies(List<Cookie> cookies) {
        List<Cookie> filtered = new ArrayList<>();
        if (cookies == null) {
            return filtered;
        }
        for (Cookie cookie : cookies) {
            if (cookie == null || cookie.name == null || cookie.value == null) {
                continue;
            }
            String cookieDomain = cookie.domain == null ? "" : cookie.domain.toLowerCase();
            if (cookieDomain.equals(domain) || cookieDomain.endsWith("." + domain)) {
                filtered.add(cookie);
            }
        }
        return filtered;
    }

    private List<Map<String, Object>> cookiesToMaps(List<Cookie> cookies) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Cookie cookie : cookies) {
            Map<String, Object> map = new HashMap<>();
            map.put("name", cookie.name);
            map.put("value", cookie.value);
            map.put("domain", cookie.domain);
            map.put("path", cookie.path);
            map.put("expires", cookie.expires);
            map.put("httpOnly", cookie.httpOnly);
            map.put("secure", cookie.secure);
            map.put("sameSite", cookie.sameSite == null ? null : cookie.sameSite.name());
            result.add(map);
        }
        return result;
    }
}
