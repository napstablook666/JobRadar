package com.jobradar.worker.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.application.entity.CookieEntity;
import com.jobradar.application.service.CookieService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SameSiteAttribute;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns one platform's Playwright lifecycle. The object is deliberately isolated:
 * no Playwright instance, browser context, page, cookie jar, or access lock is
 * shared with another platform runtime.
 */
@Slf4j
public final class PlatformBrowserRuntime {
    private static final int DEFAULT_TIMEOUT = 30_000;
    private static final String WINDOWS_CHROME_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
    private static final int LIEPIN_LOGIN_STATUS_ATTEMPTS = 3;
    private static final int LIEPIN_LOGIN_STATUS_RETRY_DELAY_MS = 500;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String platform;
    private final String homeUrl;
    private final String domain;
    private final boolean headless;
    private final BrowserRuntimeConfig.Engine engine;
    private final Path executablePath;
    private final CookieService cookieService;
    private final Consumer<Boolean> loginStatusConsumer;
    private final Supplier<Playwright> playwrightFactory;
    private final Path profileDirectory;
    private final ReentrantLock accessLock = new ReentrantLock(true);
    private final Object resourceLifecycleLock = new Object();
    private final Set<Page> configuredPages = ConcurrentHashMap.newKeySet();
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AtomicInteger activeOperations = new AtomicInteger();
    private final AtomicReference<Thread> activeOperationThread = new AtomicReference<>();
    private final AtomicBoolean forceCloseRequested = new AtomicBoolean();
    private final ThreadLocal<Long> operationGeneration = new ThreadLocal<>();

    private volatile Playwright playwright;
    private volatile Browser browser;
    private volatile BrowserContext context;
    private volatile Page page;
    private volatile RuntimeState state = RuntimeState.NEW;
    private volatile boolean initialized;
    private volatile boolean monitoringPaused;
    private volatile boolean loginKnown;
    private volatile boolean loggedIn;
    private volatile boolean pageReady;

    public enum RuntimeState {
        NEW,
        STARTING,
        READY,
        FAILED,
        STOPPING,
        STOPPED
    }

    public PlatformBrowserRuntime(
            String platform,
            String homeUrl,
            String domain,
            boolean headless,
            BrowserRuntimeConfig.Engine engine,
            Path executablePath,
            CookieService cookieService,
            Consumer<Boolean> loginStatusConsumer
    ) {
        this(platform, homeUrl, domain, headless, engine, executablePath,
                cookieService, loginStatusConsumer, Playwright::create);
    }

    PlatformBrowserRuntime(
            String platform,
            String homeUrl,
            String domain,
            boolean headless,
            BrowserRuntimeConfig.Engine engine,
            Path executablePath,
            CookieService cookieService,
            Consumer<Boolean> loginStatusConsumer,
            Supplier<Playwright> playwrightFactory
    ) {
        this.platform = requireText(platform, "platform");
        this.homeUrl = requireText(homeUrl, "homeUrl");
        this.domain = requireText(domain, "domain");
        this.headless = headless;
        this.engine = engine == null ? BrowserRuntimeConfig.Engine.AUTO : engine;
        this.executablePath = executablePath;
        this.cookieService = cookieService;
        this.loginStatusConsumer = loginStatusConsumer;
        this.playwrightFactory = playwrightFactory == null ? Playwright::create : playwrightFactory;
        this.profileDirectory = Paths.get("browser-data", "platform-runtimes", platform)
                .toAbsolutePath().normalize();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    public String getPlatform() {
        return platform;
    }

    public Path getProfileDirectory() {
        return profileDirectory;
    }

    public boolean isHeadless() {
        return headless;
    }

    public boolean isInitialized() {
        accessLock.lock();
        try {
            return initialized && isRuntimeHealthyUnlocked();
        } finally {
            accessLock.unlock();
        }
    }

    public boolean isHealthy() {
        accessLock.lock();
        try {
            return initialized && isRuntimeHealthyUnlocked();
        } finally {
            accessLock.unlock();
        }
    }

    public boolean isLoginKnown() {
        return loginKnown;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public Page getPage() {
        return page;
    }

    public Browser getBrowser() {
        return browser;
    }

    public void initialize() {
        accessLock.lock();
        OperationScope operation = beginOperation();
        try {
            initializeUnlocked();
        } finally {
            endOperation(operation);
            accessLock.unlock();
        }
    }

    private OperationScope beginOperation() {
        Long existingGeneration = operationGeneration.get();
        if (existingGeneration != null) {
            return new OperationScope(existingGeneration, false);
        }
        if (forceCloseRequested.getAndSet(false)) {
            if (state == RuntimeState.STOPPED) {
                state = RuntimeState.NEW;
            }
        }
        long generation = lifecycleGeneration.get();
        operationGeneration.set(generation);
        activeOperations.incrementAndGet();
        activeOperationThread.set(Thread.currentThread());
        return new OperationScope(generation, true);
    }

    private void endOperation(OperationScope operation) {
        if (operation == null || !operation.owner()) {
            return;
        }
        operationGeneration.remove();
        if (activeOperations.decrementAndGet() <= 0) {
            activeOperations.set(0);
            activeOperationThread.compareAndSet(Thread.currentThread(), null);
        }
    }

    private void ensureOperationActive() {
        Long expectedGeneration = operationGeneration.get();
        if (expectedGeneration != null && expectedGeneration != lifecycleGeneration.get()) {
            throw new IllegalStateException("平台运行时操作已被停止: " + platform);
        }
    }

    private boolean operationWasStopped() {
        Long expectedGeneration = operationGeneration.get();
        return expectedGeneration != null && expectedGeneration != lifecycleGeneration.get();
    }

    private record OperationScope(long generation, boolean owner) {
    }

    private void initializeUnlocked() {
        ensureOperationActive();
        if (initialized) {
            if (isRuntimeHealthyUnlocked()) {
                return;
            }
            log.warn("平台运行时资源已失联，准备重建: platform={}", platform);
            resetDisconnectedStateUnlocked();
        }
        state = RuntimeState.STARTING;
        try {
            Files.createDirectories(profileDirectory);
            playwright = playwrightFactory.get();

            List<String> args = new ArrayList<>(List.of(
                    "--disable-blink-features=AutomationControlled",
                    "--disable-infobars",
                    "--no-first-run",
                    "--no-default-browser-check"
            ));
            if (headless) {
                args.add("--headless=new");
                args.add("--window-size=1920,1080");
            } else {
                args.add("--start-maximized");
            }

            BrowserType.LaunchPersistentContextOptions options =
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(headless)
                            .setSlowMo(headless ? 0 : 50)
                            .setIgnoreDefaultArgs(List.of("--enable-automation"))
                            .setArgs(args)
                            .setLocale("zh-CN")
                            .setTimezoneId("Asia/Shanghai")
                            .setUserAgent(WINDOWS_CHROME_USER_AGENT)
                            .setExtraHTTPHeaders(Map.of("Accept-Language", "zh-CN,zh;q=0.9"));
            if (executablePath != null && Files.isRegularFile(executablePath)
                    && engine == BrowserRuntimeConfig.Engine.HEADLESS_SHELL) {
                options.setExecutablePath(executablePath);
            }

            context = playwright.chromium().launchPersistentContext(profileDirectory, options);
            ensureOperationActive();
            browser = context.browser();
            configureContext();
            loadCookies();
            ensureOperationActive();
            initialized = true;
            state = RuntimeState.READY;
            log.info("平台运行时已启动: platform={}, headless={}, profile={}",
                    platform, headless, profileDirectory);
        } catch (Exception e) {
            if (operationWasStopped()) {
                state = RuntimeState.STOPPED;
                closeResourcesUnlocked();
                throw new IllegalStateException("平台运行时操作已被停止: " + platform, e);
            }
            state = RuntimeState.FAILED;
            closeResourcesUnlocked();
            throw new IllegalStateException("平台运行时初始化失败: " + platform, e);
        }
    }

    public void withAccess(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("Playwright action 不能为空");
        }
        accessLock.lock();
        OperationScope operation = beginOperation();
        try {
            initializeUnlocked();
            ensureOperationActive();
            action.run();
            ensureOperationActive();
        } finally {
            endOperation(operation);
            accessLock.unlock();
        }
    }

    public boolean withAccessCancellable(BooleanSupplier shouldCancel, Runnable action) {
        if (shouldCancel == null || action == null) {
            throw new IllegalArgumentException("取消检查和 Playwright action 不能为空");
        }
        while (!shouldCancel.getAsBoolean()) {
            try {
                if (!accessLock.tryLock(200, TimeUnit.MILLISECONDS)) {
                    continue;
                }
                try {
                    if (shouldCancel.getAsBoolean()) {
                        return false;
                    }
                    OperationScope operation = beginOperation();
                    try {
                        ensureOperationActive();
                        initializeUnlocked();
                        ensureOperationActive();
                        action.run();
                        return true;
                    } finally {
                        endOperation(operation);
                    }
                } finally {
                    accessLock.unlock();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public Page ensurePageReady() {
        accessLock.lock();
        OperationScope operation = beginOperation();
        try {
            return ensurePageReadyUnlocked();
        } finally {
            endOperation(operation);
            accessLock.unlock();
        }
    }

    private Page ensurePageReadyUnlocked() {
        for (int attempt = 0; attempt < 2; attempt++) {
            initializeUnlocked();
            if (isUsablePage(page)) {
                return page;
            }

            Page recovered = null;
            try {
                recovered = context.newPage();
                configurePage(recovered);
                page = recovered;
                recovered.navigate(homeUrl, new Page.NavigateOptions()
                        .setTimeout(60_000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                try {
                    recovered.waitForLoadState(LoadState.DOMCONTENTLOADED,
                            new Page.WaitForLoadStateOptions().setTimeout(10_000));
                } catch (Exception ignored) {
                    // Navigation succeeded; a delayed page state must not discard the page.
                }
                pageReady = true;
                refreshLoginStatusAfterNavigationUnlocked();
                return recovered;
            } catch (Exception e) {
                if (page == recovered) {
                    page = null;
                }
                pageReady = false;
                closePageQuietly(recovered);
                if (attempt == 0 && isTargetClosedFailure(e)) {
                    resetDisconnectedStateUnlocked();
                    continue;
                }
                throw new IllegalStateException("页面恢复失败: " + platform, e);
            }
        }
        throw new IllegalStateException("页面恢复失败: " + platform);
    }

    private boolean isUsablePage(Page candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            return !candidate.isClosed() && isRuntimeHealthyUnlocked();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isRuntimeHealthyUnlocked() {
        if (playwright == null || context == null) {
            return false;
        }
        if (browser != null && !isBrowserConnected()) {
            return false;
        }
        try {
            context.pages();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBrowserConnected() {
        try {
            return browser != null && browser.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void resetDisconnectedStateUnlocked() {
        closeResourcesUnlocked();
        initialized = false;
        pageReady = false;
        loginKnown = false;
        loggedIn = false;
        state = RuntimeState.NEW;
    }

    private static void closePageQuietly(Page target) {
        if (target == null) {
            return;
        }
        try {
            if (!target.isClosed()) {
                target.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isTargetClosedFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String type = current.getClass().getSimpleName();
            String message = current.getMessage();
            if ((type != null && type.contains("TargetClosed"))
                    || (message != null && message.contains("Target page, context or browser has been closed"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public boolean refreshLoginStatus() {
        accessLock.lock();
        OperationScope operation = beginOperation();
        try {
            initializeUnlocked();
            if (!isUsablePage(page)) {
                ensurePageReadyUnlocked();
            }
            return refreshLoginStatusUnlocked();
        } finally {
            endOperation(operation);
            accessLock.unlock();
        }
    }

    public boolean tryRefreshLoginStatus() {
        if (!accessLock.tryLock()) {
            return loggedIn;
        }
        OperationScope operation = beginOperation();
        try {
            initializeUnlocked();
            if (!isUsablePage(page)) {
                // 懒加载运行时首次查询时建立后台页；若投递已持锁，tryLock() 会在上方直接返回缓存态。
                ensurePageReadyUnlocked();
                return loggedIn;
            }
            return refreshLoginStatusUnlocked();
        } finally {
            endOperation(operation);
            accessLock.unlock();
        }
    }

    private boolean refreshLoginStatusUnlocked() {
        boolean current = detectLoginStatus();
        boolean previous = loggedIn;
        loggedIn = current;
        loginKnown = true;
        if (previous != current && loginStatusConsumer != null) {
            try {
                loginStatusConsumer.accept(current);
            } catch (Exception e) {
                log.warn("回传平台登录状态失败: platform={}, error={}", platform, e.getMessage());
            }
        }
        return current;
    }

    private boolean refreshLoginStatusAfterNavigationUnlocked() {
        boolean current = refreshLoginStatusUnlocked();
        if (current || !"liepin".equals(platform) || !hasLiepinAuthCookies()
                || isSecurityVerificationUrl(safeUrl())) {
            return current;
        }
        for (int attempt = 1; attempt < LIEPIN_LOGIN_STATUS_ATTEMPTS; attempt++) {
            try {
                page.waitForTimeout(LIEPIN_LOGIN_STATUS_RETRY_DELAY_MS);
            } catch (Exception ignored) {
                return current;
            }
            current = refreshLoginStatusUnlocked();
            if (current || isSecurityVerificationUrl(safeUrl())) {
                return current;
            }
        }
        return current;
    }

    public void markLoggedIn(boolean value) {
        loggedIn = value;
        loginKnown = true;
    }

    private boolean detectLoginStatus() {
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            return switch (platform) {
                case "boss" -> detectBossLogin();
                case "liepin" -> detectLiepinLogin();
                case "51job" -> detectJob51Login();
                case "zhilian" -> detectZhilianLogin();
                default -> false;
            };
        } catch (Exception e) {
            log.debug("检测平台登录状态失败: platform={}, error={}", platform, e.getMessage());
            return false;
        }
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
        if (isSecurityVerificationUrl(url)) {
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

    static boolean isSecurityVerificationUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.toLowerCase(Locale.ROOT);
        return url.contains("safe.liepin.com")
                || url.contains("captchapage")
                || url.contains("captcha")
                || url.contains("securityverify")
                || url.contains("security-verification");
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
        // Cookie 存在只代表曾经保存过会话，不能证明当前账号仍可访问详情页。
        // 必须看到首页用户锚点才报告已登录，避免过期 Cookie 触发整轮无效投递。
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
            return page.url() == null ? "" : page.url().toLowerCase(Locale.ROOT);
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

    private void configureContext() {
        if (context == null) {
            return;
        }
        context.onPage(this::configurePage);
        for (Page existing : context.pages()) {
            configurePage(existing);
        }
    }

    private void configurePage(Page target) {
        if (target == null || !configuredPages.add(target)) {
            return;
        }
        try {
            target.setDefaultTimeout(DEFAULT_TIMEOUT);
            target.onDialog(dialog -> {
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {
                }
            });
            target.onClose(closed -> {
                configuredPages.remove(closed);
                if (page == closed) {
                    page = null;
                    pageReady = false;
                }
            });
            target.onFrameNavigated(frame -> {
                if (frame != target.mainFrame() || monitoringPaused) {
                    return;
                }
                tryRefreshLoginStatus();
            });
        } catch (Exception e) {
            configuredPages.remove(target);
            log.debug("注册平台页面处理器失败: platform={}, error={}", platform, e.getMessage());
        }
    }

    private void loadCookies() {
        if (cookieService == null || context == null) {
            return;
        }
        try {
            CookieEntity entity = cookieService.getCookieByPlatform(platform);
            if (entity == null || entity.getCookieValue() == null || entity.getCookieValue().isBlank()) {
                return;
            }
            List<Cookie> cookies = filterCookies(parseCookiesFlexible(entity.getCookieValue(), domain));
            if (!cookies.isEmpty()) {
                context.addCookies(cookies);
                log.info("平台运行时已加载 Cookie: platform={}, count={}", platform, cookies.size());
            }
        } catch (Exception e) {
            log.warn("平台运行时加载 Cookie 失败: platform={}, error={}", platform, e.getMessage());
        }
    }

    public void pauseMonitoring() {
        monitoringPaused = true;
    }

    public void resumeMonitoring() {
        monitoringPaused = false;
    }

    public BrowserSessionSnapshot getSessionSnapshot() {
        accessLock.lock();
        OperationScope operation = beginOperation();
        try {
            if (context == null) {
                return new BrowserSessionSnapshot(List.of());
            }
            return new BrowserSessionSnapshot(context.cookies());
        } catch (Exception e) {
            log.debug("读取平台 Cookie 快照失败: platform={}, error={}", platform, e.getMessage());
            return new BrowserSessionSnapshot(List.of());
        } finally {
            endOperation(operation);
            accessLock.unlock();
        }
    }

    /** Injects cookies into the live runtime without writing them to storage. */
    public void syncCookies(BrowserSessionSnapshot snapshot) {
        if (snapshot == null || snapshot.cookies().isEmpty()) return;
        accessLock.lock();
        OperationScope operation = beginOperation();
        try {
            initializeUnlocked();
            List<Cookie> cookies = filterCookies(snapshot.cookies());
            if (!cookies.isEmpty()) context.addCookies(cookies);
        } finally {
            endOperation(operation);
            accessLock.unlock();
        }
    }

    public void saveCookiesToDatabase(String remark) {
        accessLock.lock();
        OperationScope operation = beginOperation();
        try {
            initializeUnlocked();
            saveCookiesToDatabaseUnlocked(remark);
        } finally {
            endOperation(operation);
            accessLock.unlock();
        }
    }

    private void saveCookiesToDatabaseUnlocked(String remark) {
        if (cookieService == null || context == null) {
            return;
        }
        try {
            List<Cookie> cookies = filterCookies(context.cookies());
            String json = OBJECT_MAPPER.writeValueAsString(cookieMaps(cookies));
            cookieService.saveOrUpdateCookie(platform, json,
                    remark == null || remark.isBlank() ? "runtime save" : remark);
        } catch (Exception e) {
            log.warn("平台运行时保存 Cookie 失败: platform={}, error={}", platform, e.getMessage());
        }
    }

    public void clearCookies() {
        accessLock.lock();
        OperationScope operation = beginOperation();
        try {
            initializeUnlocked();
            context.clearCookies();
            loggedIn = false;
            loginKnown = true;
        } finally {
            endOperation(operation);
            accessLock.unlock();
        }
    }

    public Map<String, Object> importCookies(String raw, String remark) {
        accessLock.lock();
        OperationScope operation = beginOperation();
        try {
            initializeUnlocked();
            List<Cookie> cookies = filterCookies(parseCookiesFlexible(raw, domain));
            if (cookies.isEmpty()) {
                throw new IllegalArgumentException("未能解析出有效 Cookie");
            }
            cookies = addCookiesBestEffort(cookies);
            if (cookies.isEmpty()) {
                throw new IllegalStateException("Cookie 注入失败");
            }
            if (cookieService != null) {
                String json = OBJECT_MAPPER.writeValueAsString(cookieMaps(cookies));
                if (!cookieService.saveOrUpdateCookie(platform, json,
                        remark == null || remark.isBlank() ? "manual import" : remark)) {
                    throw new IllegalStateException("Cookie 写入数据库失败");
                }
            }
            ensurePageReadyUnlocked();
            try {
                page.navigate(homeUrl, new Page.NavigateOptions()
                        .setTimeout(60_000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            } catch (Exception e) {
                log.warn("导入 Cookie 后导航失败: platform={}, error={}", platform, e.getMessage());
            }
            boolean current = refreshLoginStatusAfterNavigationUnlocked();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("platform", platform);
            result.put("count", cookies.size());
            result.put("loggedIn", current);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("导入 Cookie 失败: " + e.getMessage(), e);
        } finally {
            endOperation(operation);
            accessLock.unlock();
        }
    }

    public void triggerLogin() {
        if (headless) {
            throw new IllegalStateException("当前为后台模式，请切换 visible-login 后再扫码登录");
        }
        withAccess(() -> {
            Page target = ensurePageReady();
            try {
                switch (platform) {
                    case "boss" -> target.navigate("https://www.zhipin.com/web/user/?ka=header-login");
                    case "liepin" -> target.navigate("https://www.liepin.com/login");
                    case "51job" -> triggerJob51Login(target);
                    case "zhilian" -> triggerZhilianLogin(target);
                    default -> target.navigate(homeUrl);
                }
            } catch (Exception e) {
                throw new IllegalStateException("触发登录流程失败: " + platform, e);
            }
        });
    }

    private void triggerJob51Login(Page target) {
        target.navigate("https://login.51job.com/login.php", new Page.NavigateOptions()
                .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        Locator scan = target.locator("[data-sensor-id='sensor_login_wechatScan']").first();
        if (scan.count() > 0 && scan.isVisible()) {
            scan.click();
        }
    }

    private void triggerZhilianLogin(Page target) {
        target.navigate(homeUrl, new Page.NavigateOptions()
                .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        Locator noLogin = target.locator("a.home-header__c-no-login").first();
        Locator qr = target.locator("div.zppp-panel-normal-bar__img").first();
        if (noLogin.count() > 0 && noLogin.isVisible() && qr.count() > 0 && qr.isVisible()) {
            qr.click();
        }
    }

    public Map<String, Object> getStatus() {
        accessLock.lock();
        try {
            boolean contextReady = isRuntimeHealthyUnlocked();
            boolean browserConnected = browser == null || isBrowserConnected();
            boolean pageAlive = false;
            try {
                pageAlive = page != null && !page.isClosed();
            } catch (Exception ignored) {
            }
            boolean healthy = initialized && contextReady;
            Map<String, Object> status = new HashMap<>();
            status.put("platform", platform);
            status.put("state", initialized && !healthy && state == RuntimeState.READY
                    ? RuntimeState.FAILED.name() : state.name());
            status.put("initialized", healthy);
            status.put("headless", headless);
            status.put("browserConnected", browserConnected);
            status.put("contextReady", contextReady);
            status.put("pageReady", pageReady && pageAlive && healthy);
            status.put("loginKnown", loginKnown);
            status.put("isLoggedIn", loggedIn);
            status.put("profile", profileDirectory.toString());
            status.put("lockHeld", accessLock.isLocked());
            status.put("activeOperations", activeOperations.get());
            status.put("forceCloseRequested", forceCloseRequested.get());
            return status;
        } finally {
            accessLock.unlock();
        }
    }

    public void close() {
        accessLock.lock();
        try {
            state = RuntimeState.STOPPING;
            closeResourcesUnlocked();
            state = RuntimeState.STOPPED;
            initialized = false;
            pageReady = false;
            loginKnown = false;
            loggedIn = false;
        } finally {
            accessLock.unlock();
        }
    }

    /**
     * Stops the runtime without waiting for the Playwright access lock.
     * A delivery operation may be blocked inside Playwright; closing its page/context
     * makes that operation fail and lets the owning task release the lock normally.
     */
    public void forceClose() {
        lifecycleGeneration.incrementAndGet();
        forceCloseRequested.set(true);
        state = RuntimeState.STOPPING;
        Thread operationThread = activeOperationThread.get();
        if (operationThread != null && operationThread != Thread.currentThread()) {
            operationThread.interrupt();
        }
        closeResourcesUnlocked();
        initialized = false;
        pageReady = false;
        loginKnown = false;
        loggedIn = false;
        state = RuntimeState.STOPPED;
    }

    /** Test and shutdown hook access to the runtime lifecycle state. */
    int activeOperations() {
        return activeOperations.get();
    }

    private void closeResourcesUnlocked() {
        synchronized (resourceLifecycleLock) {
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
                if (browser != null && browser.isConnected()) {
                    browser.close();
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
            browser = null;
            playwright = null;
            configuredPages.clear();
        }
    }

    private List<Cookie> filterCookies(List<Cookie> cookies) {
        if (cookies == null) {
            return new ArrayList<>();
        }
        String suffix = domain.toLowerCase(Locale.ROOT);
        List<Cookie> filtered = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (cookie == null || cookie.name == null || cookie.name.isBlank()
                    || cookie.value == null || cookie.domain == null || cookie.domain.isBlank()) {
                continue;
            }
            String cookieDomain = cookie.domain.toLowerCase(Locale.ROOT);
            if (cookieDomain.equals(suffix) || cookieDomain.endsWith("." + suffix)) {
                filtered.add(cookie);
            }
        }
        return filtered;
    }

    private List<Cookie> addCookiesBestEffort(List<Cookie> cookies) {
        try {
            context.addCookies(cookies);
            return cookies;
        } catch (RuntimeException batchFailure) {
            if (isTargetClosedFailure(batchFailure)) {
                log.warn("Cookie 注入命中失联运行时，先重建再重试: platform={}", platform);
                resetDisconnectedStateUnlocked();
                initializeUnlocked();
                try {
                    context.addCookies(cookies);
                    return cookies;
                } catch (RuntimeException retryFailure) {
                    if (isTargetClosedFailure(retryFailure)) {
                        throw retryFailure;
                    }
                    log.warn("重建后批量注入 Cookie 失败，切换逐条注入: platform={}, count={}, error={}",
                            platform, cookies.size(), retryFailure.getMessage());
                }
            }
            log.warn("批量注入 Cookie 失败，切换逐条注入: platform={}, count={}, error={}",
                    platform, cookies.size(), batchFailure.getMessage());
        }

        List<Cookie> accepted = new ArrayList<>();
        for (Cookie cookie : cookies) {
            try {
                context.addCookies(List.of(cookie));
                accepted.add(cookie);
            } catch (RuntimeException itemFailure) {
                log.warn("跳过无效 Cookie: platform={}, name={}, error={}",
                        platform, cookie.name, itemFailure.getMessage());
            }
        }
        return accepted;
    }

    private static List<Map<String, Object>> cookieMaps(List<Cookie> cookies) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Cookie cookie : cookies) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", cookie.name);
            item.put("value", cookie.value);
            item.put("domain", cookie.domain);
            item.put("path", cookie.path);
            item.put("expires", cookie.expires);
            item.put("httpOnly", cookie.httpOnly);
            item.put("secure", cookie.secure);
            if (cookie.sameSite != null) {
                item.put("sameSite", cookie.sameSite.name());
            }
            result.add(item);
        }
        return result;
    }

    private static List<Cookie> parseCookiesFlexible(String raw, String defaultDomain) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return new ArrayList<>();
        }
        if (value.startsWith("[")) {
            return parseCookieJson(value, defaultDomain);
        }
        List<Cookie> cookies = new ArrayList<>();
        String domain = defaultDomain.startsWith(".") ? defaultDomain : "." + defaultDomain;
        for (String part : value.split(";")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = part.substring(0, separator).trim();
            String cookieValue = part.substring(separator + 1).trim();
            if (name.isEmpty() || Set.of("path", "domain", "expires", "max-age", "secure", "httponly", "samesite")
                    .contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Cookie cookie = new Cookie(name, cookieValue);
            cookie.domain = domain;
            cookie.path = "/";
            cookies.add(cookie);
        }
        return cookies;
    }

    private static List<Cookie> parseCookieJson(String raw, String defaultDomain) {
        List<Cookie> cookies = new ArrayList<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            if (!root.isArray()) {
                return cookies;
            }
            String domain = defaultDomain.startsWith(".") ? defaultDomain : "." + defaultDomain;
            for (JsonNode node : root) {
                if (!node.hasNonNull("name") || !node.hasNonNull("value")) {
                    continue;
                }
                Cookie cookie = new Cookie(node.get("name").asText(), node.get("value").asText());
                cookie.domain = node.hasNonNull("domain") ? node.get("domain").asText() : domain;
                cookie.path = node.hasNonNull("path") ? node.get("path").asText() : "/";
                double expires = node.hasNonNull("expires")
                        ? node.get("expires").asDouble()
                        : node.hasNonNull("expirationDate")
                        ? node.get("expirationDate").asDouble()
                        : -1;
                if (expires > 0) {
                    if (expires <= System.currentTimeMillis() / 1000.0) {
                        continue;
                    }
                    cookie.expires = expires;
                }
                if (node.hasNonNull("httpOnly")) {
                    cookie.httpOnly = node.get("httpOnly").asBoolean();
                }
                if (node.hasNonNull("secure")) {
                    cookie.secure = node.get("secure").asBoolean();
                }
                if (node.hasNonNull("sameSite")) {
                    cookie.sameSite = mapSameSite(node.get("sameSite").asText());
                }
                cookies.add(cookie);
            }
        } catch (Exception e) {
            log.debug("解析 Cookie JSON 失败: {}", e.getMessage());
        }
        return cookies;
    }

    private static SameSiteAttribute mapSameSite(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "strict" -> SameSiteAttribute.STRICT;
            case "lax" -> SameSiteAttribute.LAX;
            case "none", "no_restriction", "unspecified" -> SameSiteAttribute.NONE;
            default -> {
                try {
                    yield SameSiteAttribute.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (Exception ignored) {
                    yield null;
                }
            }
        };
    }

    private boolean hasJob51AuthCookie() {
        return hasNonBlankCookie("51job")
                || (hasNonBlankCookie("uid") && hasNonBlankCookie("guid"));
    }
}
