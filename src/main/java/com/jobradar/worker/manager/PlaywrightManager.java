package com.jobradar.worker.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.application.entity.CookieEntity;
import com.jobradar.application.service.CookieService;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Playwright管理器
 * Spring管理的单例Bean，在应用启动时自动初始化Playwright实例
 * 支持4个求职平台的共享BrowserContext和登录状态监控
 * 所有平台在同一个浏览器窗口的不同标签页中运行
 */
@Slf4j
@Getter
@Component
@Lazy
public class PlaywrightManager {

    enum BrowserMode {
        BACKGROUND,
        VISIBLE_LOGIN
    }

    // Playwright实例
    private Playwright playwright;

    // 浏览器实例（所有平台共享）
    private Browser browser;

    // 浏览器上下文（所有平台共享，在同一个窗口中打开多个标签页）
    private BrowserContext context;

    // Boss直聘页面
    private Page bossPage;

    // 猎聘页面
    private volatile Page liepinPage;

    // 51job页面（预留）
    private Page job51Page;

    // 智联招聘页面（预留）
    private Page zhilianPage;

    // 登录状态追踪（平台 -> 是否已登录）
    private final Map<String, Boolean> loginStatus = new ConcurrentHashMap<>();

    // 登录状态监听器
    private final List<Consumer<LoginStatusChange>> loginStatusListeners = new CopyOnWriteArrayList<>();

    // 控制是否暂停对bossPage的后台监控，避免与任务执行并发访问同一页面
    private volatile boolean bossMonitoringPaused = false;
    // 控制是否暂停对liepinPage的后台监控
    private volatile boolean liepinMonitoringPaused = false;

    // 控制是否暂停对51jobPage的后台监控
  private volatile boolean job51MonitoringPaused = false;

    // 控制是否暂停对zhilianPage的后台监控
    private volatile boolean zhilianMonitoringPaused = false;

    // 记录智联招聘是否已处理过未登录引导（仅初始化时执行一次）
    private volatile boolean zhilianLoginGuided = false;

    // 默认超时时间（毫秒）
  private static final int DEFAULT_TIMEOUT = 30000;

    // Playwright调试端口（产品依赖外部连接）
    private static final int CDP_PORT = 7866;

    /** 系统 Chrome + 持久 profile（connectOverCDP 路径） */
    private static final Path CHROME_PROFILE_DIR = Paths.get("browser-data", "chrome-cdp-profile");

    /** 本进程拉起的系统 Chrome；仅在我们 spawn 时负责销毁 */
    private Process managedChromeProcess;

    /** true=走系统 Chrome + CDP 模式（可 detach 登录） */
    private boolean connectedOverCdp;

    /** true=当前 Playwright 已附着 browser/context/pages */
    private volatile boolean playwrightAttached;

    /** 四平台并发初始化是否完成（完成前禁止 detach 登录） */
    private volatile boolean initializationComplete;

    /** Boss hands-off 登录进行中：Playwright 已断开，Chrome 仍活着 */
    private volatile boolean bossHandsOffLoginActive;

    /** 防止并发 hands-off */
    private final AtomicBoolean bossHandsOffLoginGate = new AtomicBoolean(false);

    private final Object cdpLifecycleLock = new Object();

    /**
     * Playwright Java 对象不是线程安全的，所有共享 Browser/Page 操作统一走这把锁。
     * ponytail: 全局锁会牺牲不同平台并行吞吐；当前共享 BrowserContext 只能串行，后续拆独立上下文时再细分。
     */
    private final ReentrantLock playwrightAccessLock = new ReentrantLock(true);

    /** 防止重连或页面恢复时重复注册猎聘登录监控。 */
    private final Set<Page> liepinMonitoredPages = ConcurrentHashMap.newKeySet();
    /** 防止重连或页面恢复时重复注册 51job 登录监控。 */
    private final Set<Page> job51MonitoredPages = ConcurrentHashMap.newKeySet();
    /** 防止页面恢复或 CDP 重连时重复注册通用页面处理器。 */
    private final Set<Page> configuredPages = ConcurrentHashMap.newKeySet();

    /** 独立平台运行时；shared 模式下保持为空，便于兼容旧链路。 */
    private final Map<String, PlatformBrowserRuntime> platformRuntimes = new ConcurrentHashMap<>();
    private Supplier<Playwright> platformPlaywrightFactory = Playwright::create;

    /** 四个平台的可见交互登录会话；与投递运行时隔离，Cookie 仅在用户点击获取后同步。 */
    private final Map<String, BrowserLoginSession> browserLoginSessions = new ConcurrentHashMap<>();
    /** 详情页访问验证专用可见会话；完成后立即关闭，Cookie 只回写当前运行时。 */
    private final Map<String, BrowserLoginSession> verificationSessions = new ConcurrentHashMap<>();

    @Value("${jobradar.browser.mode:background}")
    private String configuredBrowserMode;

    @Value("${jobradar.browser.transport:hybrid}")
    private String configuredTransportMode;

    @Value("${jobradar.browser.engine:auto}")
    private String configuredBrowserEngine;

    @Value("${jobradar.browser.page-policy:lazy}")
    private String configuredPagePolicy;

    @Value("${jobradar.browser.headless-shell-path:}")
    private String configuredHeadlessShellPath;

    @Value("${jobradar.delivery.runtime:isolated}")
    private String configuredDeliveryRuntime;

    private volatile BrowserMode browserMode = BrowserMode.BACKGROUND;
    private volatile BrowserRuntimeConfig.TransportMode transportMode = BrowserRuntimeConfig.TransportMode.HYBRID;
    private volatile BrowserRuntimeConfig.Engine browserEngine = BrowserRuntimeConfig.Engine.AUTO;
    private volatile BrowserRuntimeConfig.PagePolicy pagePolicy = BrowserRuntimeConfig.PagePolicy.LAZY;
    private volatile boolean isolatedRuntime;

    private static final String BOSS_LOGIN_URL = "https://www.zhipin.com/web/user/?ka=header-login";
    private static final long BOSS_HANDS_OFF_TIMEOUT_MS = 15 * 60 * 1000L;

    // 平台URL常量
    private static final String BOSS_URL = "https://www.zhipin.com";
    private static final String LIEPIN_URL = "https://www.liepin.com";
  private static final String JOB51_URL = "https://www.51job.com";
    private static final String ZHILIAN_URL = "https://www.zhaopin.com";
    private static final String BOSS_DOMAIN = "zhipin.com";
    private static final String LIEPIN_DOMAIN = "liepin.com";
    private static final String JOB51_DOMAIN = "51job.com";
    private static final String ZHILIAN_DOMAIN = "zhaopin.com";
    private static final String BOSS_INIT_SCRIPT_RESOURCE = "anti-detection.js";
    // 降噪：51job Cookie保存日志节流状态
    private volatile long last51CookieLogMs = 0L;
    private volatile int last51CookieLogCount = -1;
    private volatile String last51CookieRemark = "";

    @Autowired
    private CookieService cookieService;

    /**
     * 初始化Playwright实例（延迟初始化）
     */
    public void init() {
        if (isInitialized()) {
            return;
        }
        log.info("========================================");
        log.info("  初始化浏览器自动化引擎");
        log.info("========================================");

        try {
            browserMode = resolveBrowserMode();
            transportMode = resolveTransportMode();
            browserEngine = resolveBrowserEngine();
            pagePolicy = resolvePagePolicy();
            isolatedRuntime = resolveDeliveryRuntime();
            log.info("浏览器运行模式: {}", getBrowserModeName());
            log.info("浏览器传输={}, 引擎={}, 页面策略={}",
                    getTransportModeName(), getBrowserEngineName(), getPagePolicyName());
            log.info("投递运行时: {}", getDeliveryRuntimeName());

            if (isolatedRuntime) {
                if (!isLazyPagePolicy()) {
                    initializePlatformRuntimes();
                } else {
                    log.info("独立懒加载模式已启用：启动阶段仅解析配置，平台运行时按需创建");
                }
                initializationComplete = true;
                log.info("✓ 独立浏览器运行时配置完成");
                log.info("========================================");
                return;
            }

            // 启动Playwright
            playwright = Playwright.create();
            log.info("✓ Playwright引擎已启动");

            // 可见登录保留系统 CDP；后台默认走独立无界面引擎，避免常驻可见 profile。
            if (shouldUseSystemCdp() && !attachOrLaunchSystemChromeOverCdp()) {
                launchBackgroundEngine();
            } else if (!shouldUseSystemCdp()) {
                launchBackgroundEngine();
            }
            injectBossInitScript(context);

            if (isLazyPagePolicy()) {
                loadPlatformCookiesWithoutPages();
                log.info("懒加载页面策略已启用：启动阶段只创建 BrowserContext");
            } else {
                // 顺序创建所有Page，兼容旧的 eager 模式。
                log.info("开始创建所有平台的Page...");
                bossPage = context.newPage();
                configurePage(bossPage);
                log.info("✓ Boss Page已创建");

                liepinPage = context.newPage();
                configurePage(liepinPage);
                log.info("✓ 猎聘 Page已创建");

                job51Page = context.newPage();
                configurePage(job51Page);
                log.info("✓ 51job Page已创建");

                zhilianPage = context.newPage();
                configurePage(zhilianPage);
                log.info("✓ 智联招聘 Page已创建");

                // Playwright Java 对共享对象要求串行访问；页面仍可并存，但导航和 DOM 操作不能并发。
                log.info("开始串行初始化所有平台...");
                withPlaywrightAccess(this::setupBossPlatform);
                withPlaywrightAccess(this::setupLiepinPlatform);
                withPlaywrightAccess(this::setup51jobPlatform);
                withPlaywrightAccess(this::setupZhilianPlatform);
            }
            initializationComplete = true;

            // Boss 改为 Cookie 登录：不再自动 hands-off 打开登录页
            if (!Boolean.TRUE.equals(loginStatus.get("boss"))) {
                log.info("Boss 未登录：请在前端粘贴 Cookie 导入登录（已禁用 hands-off 自动登录引导）");
            }

            log.info("✓ 浏览器自动化引擎初始化完成（所有平台已串行初始化）");
            log.info("========================================");
        } catch (Exception e) {
            log.error("✗ 浏览器自动化引擎初始化失败", e);
            throw new RuntimeException("Playwright初始化失败", e);
        }
    }

    /**
     * 在共享 Playwright 访问锁内执行动作。Playwright 调用可能持续数分钟，锁必须覆盖完整的平台任务。
     */
    public void withPlaywrightAccess(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("Playwright action 不能为空");
        }
        playwrightAccessLock.lock();
        try {
            action.run();
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    /**
     * 可取消地等待共享 Playwright 访问权。
     *
     * @return true 表示已取得锁并执行动作，false 表示等待期间收到取消或线程中断
     */
    public boolean withPlaywrightAccessCancellable(BooleanSupplier shouldCancel, Runnable action) {
        if (isolatedRuntime) {
            return withPlatformAccessCancellable("51job", shouldCancel, action);
        }
        if (shouldCancel == null) {
            throw new IllegalArgumentException("取消检查不能为空");
        }
        if (action == null) {
            throw new IllegalArgumentException("Playwright action 不能为空");
        }

        while (!shouldCancel.getAsBoolean()) {
            try {
                if (!playwrightAccessLock.tryLock(200, TimeUnit.MILLISECONDS)) {
                    continue;
                }
                try {
                    if (shouldCancel.getAsBoolean()) {
                        return false;
                    }
                    action.run();
                    return true;
                } finally {
                    playwrightAccessLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean tryWithPlaywrightAccess(Runnable action) {
        if (!playwrightAccessLock.tryLock()) {
            return false;
        }
        try {
            action.run();
            return true;
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    /**
     * 在上下文层统一注入 Boss 脚本，仅对 zhipin.com 生效。
     */
    /**
     * 尝试附着/拉起系统 Chrome 调试实例并 connectOverCDP。
     * @return true 表示 context 已就绪
     */
    private boolean attachOrLaunchSystemChromeOverCdp() {
        try {
            String chromePath = resolveChromeExecutable();
            if (chromePath == null) {
                log.info("未找到系统 Chrome，跳过 connectOverCDP 路径");
                return false;
            }

            if (isBackgroundMode() && isCdpReachable(CDP_PORT)) {
                log.info("后台模式回收现有项目浏览器，避免复用可见窗口");
                killChromeByProfileDir(CHROME_PROFILE_DIR.toAbsolutePath().toString());
                waitForCdpClosed(CDP_PORT, 5000);
                if (isCdpReachable(CDP_PORT)) {
                    log.warn("后台模式无法接管现有项目浏览器，回退到独立无界面实例");
                    return false;
                }
            }

            if (!isCdpReachable(CDP_PORT)) {
                Files.createDirectories(CHROME_PROFILE_DIR);
                List<String> cmd = new ArrayList<>(List.of(
                        chromePath,
                        "--remote-debugging-port=" + CDP_PORT,
                        "--remote-allow-origins=*",
                        "--user-data-dir=" + CHROME_PROFILE_DIR.toAbsolutePath(),
                        "--disable-blink-features=AutomationControlled",
                        "--no-first-run",
                        "--no-default-browser-check",
                        "--disable-infobars"
                ));
                cmd.addAll(buildSystemChromeWindowArgs(browserMode));
                cmd.add("about:blank");
                ProcessBuilder pb = new ProcessBuilder(cmd);
                // Chrome 启动器常秒退并把浏览器托管给子进程；丢弃管道避免阻塞
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                managedChromeProcess = pb.start();
                log.info("已拉起系统 Chrome 调试实例: path={}, profile={}, pid={}",
                        chromePath, CHROME_PROFILE_DIR.toAbsolutePath(), managedChromeProcess.pid());
                if (!waitForCdp(CDP_PORT, 30_000)) {
                    log.warn("系统 Chrome CDP 端口 {} 等待超时，回退捆绑 Chromium", CDP_PORT);
                    destroyManagedChromeQuietly();
                    return false;
                }
            } else {
                String existing = fetchCdpBrowserProduct(CDP_PORT);
                log.info("检测到已有 CDP 服务 :{} product={}", CDP_PORT, existing);
                if (isLikelyBundledChromiumProduct(existing)) {
                    log.warn("CDP :{} 疑似捆绑 Chromium，放弃附着: {}", CDP_PORT, existing);
                    return false;
                }
            }

            browser = playwright.chromium().connectOverCDP("http://127.0.0.1:" + CDP_PORT);
            connectedOverCdp = true;
            if (browser.contexts() != null && !browser.contexts().isEmpty()) {
                context = browser.contexts().get(0);
            } else {
                context = browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(null)
                        .setLocale("zh-CN")
                        .setTimezoneId("Asia/Shanghai"));
            }
            try {
                context.setExtraHTTPHeaders(Map.of("Accept-Language", "zh-CN,zh;q=0.9"));
            } catch (Exception e) {
                log.debug("设置 Accept-Language 失败（可忽略）: {}", e.getMessage());
            }
            configureContext(context);
            String ver = safeBrowserVersion();
            playwrightAttached = true;
            log.info("✓ 已 connectOverCDP 附着浏览器 (port={}, version={})", CDP_PORT, ver);
            return context != null;
        } catch (Exception e) {
            log.warn("connectOverCDP / 系统 Chrome 路径失败，将回退捆绑 Chromium: {}", e.getMessage());
            destroyManagedChromeQuietly();
            connectedOverCdp = false;
            browser = null;
            context = null;
            return false;
        }
    }

    private void launchBackgroundEngine() {
        Path shell = resolveHeadlessShellExecutable();
        if (browserEngine == BrowserRuntimeConfig.Engine.HEADLESS_SHELL
                || (browserEngine == BrowserRuntimeConfig.Engine.AUTO && shell != null)) {
            if (shell != null) {
                launchChromiumWithStealth(shell);
                return;
            }
            log.warn("未找到 headless-shell，回退 Playwright 捆绑 Chromium");
        }
        launchChromiumWithStealth(null);
    }

    /** 回退：Playwright 捆绑 Chromium 或指定 headless-shell + 降自动化特征。 */
    private void launchChromiumWithStealth(Path executablePath) {
        List<String> args = new ArrayList<>();
        if (!isBackgroundMode()) {
            args.add("--remote-debugging-port=" + CDP_PORT);
        }
        if (isBackgroundMode()) {
            args.add("--window-size=1920,1080");
        } else {
            args.add("--start-maximized");
        }
        args.addAll(List.of(
                "--disable-blink-features=AutomationControlled",
                "--disable-infobars",
                "--no-first-run",
                "--no-default-browser-check"
        ));
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(isBackgroundMode())
                .setSlowMo(50)
                .setIgnoreDefaultArgs(List.of("--enable-automation"))
                .setArgs(args);
        if (executablePath != null) {
            options.setExecutablePath(executablePath);
        }
        browser = playwright.chromium().launch(options);
        connectedOverCdp = false;
        playwrightAttached = true;
        String version = safeBrowserVersion();
        String chromeMajor = extractChromeMajor(version, "134");
        String windowsChromeUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + chromeMajor + ".0.0.0 Safari/537.36";
        context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(null)
                .setUserAgent(windowsChromeUa)
                .setLocale("zh-CN")
                .setTimezoneId("Asia/Shanghai")
                .setExtraHTTPHeaders(Map.of(
                        "Accept-Language", "zh-CN,zh;q=0.9",
                        "sec-ch-ua", "\"Chromium\";v=\"" + chromeMajor + "\", \"Not-A.Brand\";v=\"24\"",
                        "sec-ch-ua-mobile", "?0",
                        "sec-ch-ua-platform", "\"Windows\""
                )));
        configureContext(context);
        log.info("✓ Chromium 运行时已启动 (engine={}, port={}, version={}, UA major={})",
                executablePath == null ? "bundled" : "headless-shell", CDP_PORT, version, chromeMajor);
    }

    private Path resolveHeadlessShellExecutable() {
        String configured = configuredHeadlessShellPath;
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("jobradar.browser.headless-shell-path");
        }
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JOBRADAR_HEADLESS_SHELL");
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path candidate = Paths.get(configured.trim()).toAbsolutePath().normalize();
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private String resolveChromeExecutable() {
        List<Path> candidates = List.of(
                Paths.get(System.getProperty("user.home"), "AppData", "Local", "Google", "Chrome", "Application", "chrome.exe"),
                Paths.get("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"),
                Paths.get("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe")
        );
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private boolean isCdpReachable(int port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/json/version").toURL().openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String fetchCdpBrowserProduct(int port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/json/version").toURL().openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() >= 400) {
                return "";
            }
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            }
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Playwright 1.51 捆绑 Chromium 当前约 134.x；本机系统 Chrome 已到 146+。
     * 用 major 版本做启发式区分，避免 connectOverCDP 挂到错误浏览器。
     */
    private boolean isLikelyBundledChromiumProduct(String productJson) {
        if (productJson == null || productJson.isBlank()) {
            return false;
        }
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(productJson);
        if (m.find()) {
            try {
                int major = Integer.parseInt(m.group(1));
                return major > 0 && major < 140;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return productJson.toLowerCase(Locale.ROOT).contains("chromium")
                && !productJson.contains("Google Chrome");
    }

    private boolean waitForCdp(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean launcherExitedLogged = false;
        while (System.currentTimeMillis() < deadline) {
            // Windows 上 Chrome 启动器常快速退出，真实浏览器以子进程存活；
            // 不能把 launcher 退出当成 CDP 失败，只以 /json/version 可达为准。
            if (!launcherExitedLogged && managedChromeProcess != null && !managedChromeProcess.isAlive()) {
                log.info("Chrome 启动器进程已退出（常见），继续等待 CDP :{} ...", port);
                launcherExitedLogged = true;
            }
            if (isCdpReachable(port)) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean waitForCdpClosed(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isCdpReachable(port)) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isCdpReachable(port);
    }

    private BrowserMode resolveBrowserMode() {
        String raw = System.getProperty("jobradar.browser.mode");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("JOBRADAR_BROWSER_MODE");
        }
        if (raw == null || raw.isBlank()) {
            raw = configuredBrowserMode;
        }
        BrowserMode resolved = normalizeBrowserMode(raw);
        if (raw != null && !raw.isBlank()
                && !raw.equalsIgnoreCase("background")
                && !raw.equalsIgnoreCase("visible-login")
                && !raw.equalsIgnoreCase("visible")) {
            log.warn("未知浏览器模式 {}，回退 background", raw);
        }
        return resolved;
    }

    static BrowserMode normalizeBrowserMode(String raw) {
        if (raw != null && (raw.equalsIgnoreCase("visible-login") || raw.equalsIgnoreCase("visible"))) {
            return BrowserMode.VISIBLE_LOGIN;
        }
        return BrowserMode.BACKGROUND;
    }

    static List<String> buildSystemChromeWindowArgs(BrowserMode mode) {
        if (mode == BrowserMode.BACKGROUND) {
            return List.of("--headless=new", "--window-size=1920,1080");
        }
        return List.of("--start-maximized");
    }

    private BrowserRuntimeConfig.TransportMode resolveTransportMode() {
        String raw = firstRuntimeValue(
                "jobradar.browser.transport",
                "JOBRADAR_BROWSER_TRANSPORT",
                configuredTransportMode
        );
        BrowserRuntimeConfig.TransportMode resolved = BrowserRuntimeConfig.normalizeTransport(raw);
        if (raw != null && !raw.isBlank()
                && !raw.equalsIgnoreCase("browser")
                && !raw.equalsIgnoreCase("hybrid")) {
            log.warn("未知浏览器传输模式 {}，回退 hybrid", raw);
        }
        return resolved;
    }

    private BrowserRuntimeConfig.Engine resolveBrowserEngine() {
        String raw = firstRuntimeValue(
                "jobradar.browser.engine",
                "JOBRADAR_BROWSER_ENGINE",
                configuredBrowserEngine
        );
        BrowserRuntimeConfig.Engine resolved = BrowserRuntimeConfig.normalizeEngine(raw);
        if (raw != null && !raw.isBlank()
                && resolved == BrowserRuntimeConfig.Engine.AUTO
                && !raw.equalsIgnoreCase("auto")) {
            log.warn("未知浏览器引擎 {}，回退 auto", raw);
        }
        return resolved;
    }

    private BrowserRuntimeConfig.PagePolicy resolvePagePolicy() {
        String raw = firstRuntimeValue(
                "jobradar.browser.page-policy",
                "JOBRADAR_BROWSER_PAGE_POLICY",
                configuredPagePolicy
        );
        BrowserRuntimeConfig.PagePolicy resolved = BrowserRuntimeConfig.normalizePagePolicy(raw);
        if (raw != null && !raw.isBlank()
                && !raw.equalsIgnoreCase("eager")
                && !raw.equalsIgnoreCase("lazy")) {
            log.warn("未知页面策略 {}，回退 lazy", raw);
        }
        return resolved;
    }

    private String firstRuntimeValue(String propertyName, String envName, String configured) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        if (value == null || value.isBlank()) {
            value = configured;
        }
        return value;
    }

    /** 自动模式只在需要扫码的可见会话中复用系统 Chrome。 */
    private boolean shouldUseSystemCdp() {
        return browserEngine == BrowserRuntimeConfig.Engine.SYSTEM_CDP
                || (browserEngine == BrowserRuntimeConfig.Engine.AUTO
                && browserMode == BrowserMode.VISIBLE_LOGIN);
    }

    private boolean isLazyPagePolicy() {
        return pagePolicy == BrowserRuntimeConfig.PagePolicy.LAZY;
    }

    public String getBrowserModeName() {
        return browserMode.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String getTransportModeName() {
        return transportMode.name().toLowerCase(Locale.ROOT);
    }

    public String getBrowserEngineName() {
        return browserEngine.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String getPagePolicyName() {
        return pagePolicy.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String getDeliveryRuntimeName() {
        return isolatedRuntime ? "isolated" : "shared";
    }

    public boolean isIsolatedRuntime() {
        return isolatedRuntime;
    }

    private PlatformBrowserRuntime platformRuntime(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        PlatformBrowserRuntime runtime = platformRuntimes.get(platform);
        if (runtime == null && isolatedRuntime && initializationComplete && isLazyPagePolicy()) {
            synchronized (platformRuntimes) {
                runtime = platformRuntimes.get(platform);
                if (runtime == null) {
                    registerPlatformRuntime(
                            platform,
                            platformHomeUrl(platform),
                            platformDomain(platform),
                            resolveRuntimeEngine(),
                            resolveHeadlessShellExecutable()
                    );
                    runtime = platformRuntimes.get(platform);
                }
            }
        }
        if (runtime == null) {
            throw new IllegalStateException("平台运行时尚未初始化: " + platform);
        }
        return runtime;
    }

    private BrowserRuntimeConfig.Engine resolveRuntimeEngine() {
        if (browserEngine != BrowserRuntimeConfig.Engine.AUTO) {
            return browserEngine;
        }
        return resolveHeadlessShellExecutable() == null
                ? BrowserRuntimeConfig.Engine.AUTO
                : BrowserRuntimeConfig.Engine.HEADLESS_SHELL;
    }

    /** 打开指定平台的可见登录浏览器；登录状态由定时检测展示，不自动落库。 */
    public Map<String, Object> openBrowserLogin(String platform) {
        ensureSupportedPlatform(platform);
        init();
        BrowserLoginSession session = browserLoginSessions.computeIfAbsent(
                platform,
                this::createBrowserLoginSession
        );
        session.open(storedCookieSnapshot(platform));
        session.pollStatus();
        return browserLoginStatus(platform, session);
    }

    /** 返回浏览器登录会话状态，不返回任何 Cookie 内容。 */
    public Map<String, Object> getBrowserLoginStatus(String platform) {
        ensureSupportedPlatform(platform);
        BrowserLoginSession session = browserLoginSessions.get(platform);
        if (session == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("platform", platform);
            result.put("open", false);
            result.put("loggedIn", isLoggedIn(platform));
            result.put("saved", hasStoredCookie(platform));
            return result;
        }
        session.pollStatus();
        return browserLoginStatus(platform, session);
    }

    /** 获取并保存当前可见登录浏览器的 Cookie，仅由用户明确点击触发。 */
    public Map<String, Object> captureBrowserLogin(String platform) {
        ensureSupportedPlatform(platform);
        BrowserLoginSession session = browserLoginSessions.get(platform);
        if (session == null || !session.isOpen()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("platform", platform);
            result.put("open", false);
            result.put("captured", false);
            result.put("saved", false);
            result.put("loggedIn", false);
            result.put("error", "请先打开浏览器登录");
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>(session.captureAndPersist());
        boolean loggedIn = Boolean.TRUE.equals(result.get("loggedIn"));
        loginStatus.put(platform, loggedIn);
        result.put("stored", hasStoredCookie(platform));
        result.put("needsReimport", Boolean.TRUE.equals(result.get("stored")) && !loggedIn);
        return result;
    }

    /** 打开可见验证窗口，等待人工完成后把 Cookie 仅同步回当前 51job 运行时。 */
    public boolean waitForJob51Verification(String detailUrl,
                                            BrowserSessionSnapshot runtimeSnapshot,
                                            BooleanSupplier shouldCancel,
                                            Runnable openedCallback,
                                            long timeoutMs) {
        init();
        BrowserLoginSession session = verificationSessions.computeIfAbsent("51job", ignored ->
                new BrowserLoginSession(
                        "51job",
                        JOB51_URL,
                        JOB51_DOMAIN,
                        detailUrl,
                        raw -> new BrowserLoginSession.PersistenceResult(false, false),
                        "platform-verification",
                        true,
                        Playwright::create));
        try {
            session.openAt(detailUrl, runtimeSnapshot);
            if (openedCallback != null) {
                openedCallback.run();
            }
            boolean resolved = session.waitForVerificationResolution(timeoutMs, shouldCancel);
            if (resolved) {
                syncCookiesToRuntime("51job", session.snapshot());
            }
            return resolved;
        } finally {
            verificationSessions.remove("51job", session);
            session.close();
        }
    }

    /** 只把会话 Cookie 注入内存中的投递上下文，不触发显式持久化。 */
    private void syncCookiesToRuntime(String platform, BrowserSessionSnapshot snapshot) {
        if (snapshot == null || snapshot.cookies().isEmpty()) return;
        if (isolatedRuntime) {
            platformRuntime(platform).syncCookies(snapshot);
            return;
        }
        playwrightAccessLock.lock();
        try {
            if (context != null) {
                List<Cookie> cookies = filterCookiesByDomain(snapshot.cookies(), platformDomain(platform));
                if (!cookies.isEmpty()) context.addCookies(cookies);
            }
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    /** 在投递停止后关闭指定平台的登录与投递浏览器。 */
    public void closePlatformBrowsersAfterStop(String platform, BooleanSupplier stillRunning) {
        ensureSupportedPlatform(platform);
        CompletableFuture.runAsync(() -> {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (stillRunning != null && stillRunning.getAsBoolean()
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (stillRunning != null && stillRunning.getAsBoolean()) {
                log.warn("平台任务在停止宽限期内未收尾，强制关闭浏览器: platform={}", platform);
                forceClosePlatformBrowsers(platform);
            } else {
                closePlatformBrowsers(platform);
            }
        });
    }

    /** 关闭指定平台运行时，不等待其 Playwright 访问锁。 */
    public void forceClosePlatformBrowsers(String platform) {
        ensureSupportedPlatform(platform);
        BrowserLoginSession loginSession = browserLoginSessions.remove(platform);
        if (loginSession != null) {
            try {
                loginSession.close();
            } catch (Exception e) {
                log.debug("强制关闭可见登录浏览器失败: platform={}, error={}", platform, e.getMessage());
            }
        }
        BrowserLoginSession verificationSession = verificationSessions.remove(platform);
        if (verificationSession != null) {
            try {
                verificationSession.close();
            } catch (Exception e) {
                log.debug("强制关闭验证浏览器失败: platform={}, error={}", platform, e.getMessage());
            }
        }
        PlatformBrowserRuntime runtime = platformRuntimes.get(platform);
        if (runtime != null) {
            runtime.forceClose();
        }
        loginStatus.put(platform, false);
    }

    /** 关闭指定平台的浏览器会话；不触碰用户自行启动的 Chrome。 */
    public void closePlatformBrowsers(String platform) {
        ensureSupportedPlatform(platform);
        BrowserLoginSession loginSession = browserLoginSessions.remove(platform);
        if (loginSession != null) {
            try {
                loginSession.close();
            } catch (Exception e) {
                log.debug("关闭可见登录浏览器失败: platform={}, error={}", platform, e.getMessage());
            }
        }
        BrowserLoginSession verificationSession = verificationSessions.remove(platform);
        if (verificationSession != null) {
            try {
                verificationSession.close();
            } catch (Exception e) {
                log.debug("关闭验证浏览器失败: platform={}, error={}", platform, e.getMessage());
            }
        }
        PlatformBrowserRuntime runtime = platformRuntimes.get(platform);
        if (runtime != null) {
            try {
                runtime.close();
            } catch (Exception e) {
                log.debug("关闭平台投递浏览器失败: platform={}, error={}", platform, e.getMessage());
            }
        }
        loginStatus.put(platform, false);
    }

    /** 关闭全部应用管理的浏览器会话，供 stop.bat 和 Spring 销毁钩子使用。 */
    public void closeAllBrowserSessions() {
        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) {
            try {
                closePlatformBrowsers(platform);
            } catch (Exception e) {
                log.debug("关闭平台浏览器失败: platform={}, error={}", platform, e.getMessage());
            }
        }
        if (!isolatedRuntime) {
            destroyManagedChromeQuietly();
        }
    }

    private BrowserLoginSession createBrowserLoginSession(String platform) {
        return new BrowserLoginSession(
                platform,
                platformHomeUrl(platform),
                platformDomain(platform),
                platformLoginUrl(platform),
                raw -> persistBrowserLoginCookies(platform, raw)
        );
    }

    private BrowserLoginSession.PersistenceResult persistBrowserLoginCookies(String platform, String raw) {
        try {
            Map<String, Object> result = importCookies(platform, raw, "browser login");
            boolean loggedIn = Boolean.TRUE.equals(result.get("loggedIn"));
            loginStatus.put(platform, loggedIn);
            return new BrowserLoginSession.PersistenceResult(true, loggedIn);
        } catch (Exception e) {
            log.warn("浏览器登录 Cookie 保存失败: platform={}, error={}", platform, e.getMessage(), e);
            return new BrowserLoginSession.PersistenceResult(false, false);
        }
    }

    private Map<String, Object> browserLoginStatus(String platform, BrowserLoginSession session) {
        Map<String, Object> result = new LinkedHashMap<>(session.status());
        result.put("stored", hasStoredCookie(platform));
        result.put("needsReimport", Boolean.TRUE.equals(result.get("stored"))
                && !Boolean.TRUE.equals(result.get("loggedIn")));
        return result;
    }

    /** True only after this process has checked the platform's actual login state. */
    public boolean isLoginStatusKnown(String platform) {
        if (isolatedRuntime) {
            PlatformBrowserRuntime runtime = platformRuntimes.get(platform);
            return runtime != null && runtime.isLoginKnown();
        }
        return loginStatus.containsKey(platform);
    }

    private boolean hasStoredCookie(String platform) {
        CookieEntity cookie = cookieService == null ? null : cookieService.getCookieByPlatform(platform);
        return cookie != null && cookie.getCookieValue() != null && !cookie.getCookieValue().isBlank();
    }

    private BrowserSessionSnapshot storedCookieSnapshot(String platform) {
        if (cookieService == null) {
            return new BrowserSessionSnapshot(List.of());
        }
        try {
            CookieEntity cookie = cookieService.getCookieByPlatform(platform);
            if (cookie == null || cookie.getCookieValue() == null || cookie.getCookieValue().isBlank()) {
                return new BrowserSessionSnapshot(List.of());
            }
            List<Cookie> cookies = filterCookiesByDomain(
                    parseCookiesFlexible(cookie.getCookieValue(), platformDomain(platform)),
                    platformDomain(platform));
            return new BrowserSessionSnapshot(cookies);
        } catch (Exception e) {
            log.warn("读取已保存 Cookie 失败: platform={}, error={}", platform, e.getMessage());
            return new BrowserSessionSnapshot(List.of());
        }
    }

    private void ensureSupportedPlatform(String platform) {
        if (!List.of("boss", "liepin", "51job", "zhilian").contains(platform)) {
            throw new IllegalArgumentException("不支持的平台: " + platform);
        }
    }

    private String platformHomeUrl(String platform) {
        return switch (platform) {
            case "boss" -> BOSS_URL;
            case "liepin" -> LIEPIN_URL;
            case "51job" -> JOB51_URL;
            case "zhilian" -> ZHILIAN_URL;
            default -> throw new IllegalArgumentException("不支持的平台: " + platform);
        };
    }

    private String platformDomain(String platform) {
        return switch (platform) {
            case "boss" -> BOSS_DOMAIN;
            case "liepin" -> LIEPIN_DOMAIN;
            case "51job" -> JOB51_DOMAIN;
            case "zhilian" -> ZHILIAN_DOMAIN;
            default -> throw new IllegalArgumentException("不支持的平台: " + platform);
        };
    }

    private String platformLoginUrl(String platform) {
        return switch (platform) {
            case "boss" -> BOSS_LOGIN_URL;
            case "liepin" -> "https://www.liepin.com/login";
            case "51job" -> "https://login.51job.com/login.php";
            case "zhilian" -> ZHILIAN_URL;
            default -> throw new IllegalArgumentException("不支持的平台: " + platform);
        };
    }

    public Map<String, Map<String, Object>> getPlatformRuntimeStatus() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) {
            PlatformBrowserRuntime runtime = platformRuntimes.get(platform);
            if (runtime != null) {
                result.put(platform, runtime.getStatus());
            }
        }
        return result;
    }

    public boolean isHybridTransport() {
        return transportMode == BrowserRuntimeConfig.TransportMode.HYBRID;
    }

    public boolean isHeadlessMode() {
        return isBackgroundMode();
    }

    private boolean isBackgroundMode() {
        return browserMode == BrowserMode.BACKGROUND;
    }

    private void configureContext(BrowserContext targetContext) {
        if (targetContext == null) {
            return;
        }
        try {
            targetContext.onPage(this::configurePage);
            for (Page page : targetContext.pages()) {
                configurePage(page);
            }
        } catch (Exception e) {
            log.debug("注册浏览器上下文页面处理器失败: {}", e.getMessage());
        }
    }

    private void configurePage(Page page) {
        if (page == null || !configuredPages.add(page)) {
            return;
        }
        try {
            page.setDefaultTimeout(DEFAULT_TIMEOUT);
            page.onDialog(dialog -> {
                try {
                    String message = dialog.message();
                    if (message != null && message.length() > 200) {
                        message = message.substring(0, 200) + "...";
                    }
                    log.warn("自动处理网页对话框: type={}, message={}", dialog.type(), message);
                    dialog.dismiss();
                } catch (Exception e) {
                    log.debug("处理网页对话框失败: {}", e.getMessage());
                }
            });
            page.onClose(closedPage -> configuredPages.remove(closedPage));
        } catch (Exception e) {
            configuredPages.remove(page);
            log.debug("注册页面通用处理器失败: {}", e.getMessage());
        }
    }

    private String safeBrowserVersion() {
        try {
            return browser != null ? browser.version() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String extractChromeMajor(String version, String fallback) {
        if (version == null || version.isBlank()) {
            return fallback;
        }
        Matcher m = Pattern.compile("(\\d+)").matcher(version);
        if (m.find()) {
            return m.group(1);
        }
        return fallback;
    }

    private void destroyManagedChromeQuietly() {
        try {
            if (managedChromeProcess != null) {
                try {
                    managedChromeProcess.toHandle().descendants().forEach(ph -> {
                        try { ph.destroy(); } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
                try {
                    managedChromeProcess.destroy();
                    if (!managedChromeProcess.waitFor(2, TimeUnit.SECONDS)) {
                        managedChromeProcess.destroyForcibly();
                    }
                } catch (Exception e) {
                    log.debug("销毁托管 Chrome 启动器失败: {}", e.getMessage());
                }
            }
            killChromeByProfileDir(CHROME_PROFILE_DIR.toAbsolutePath().toString());
        } finally {
            managedChromeProcess = null;
        }
    }

    private void killChromeByProfileDir(String profileDir) {
        if (profileDir == null || profileDir.isBlank()) {
            return;
        }
        try {
            String needle = profileDir.replace("'", "''");
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'chrome|chromium' -and $_.CommandLine -and $_.CommandLine.Contains('" + needle + "') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
            );
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("按 profile 清理 Chrome 失败: {}", e.getMessage());
        }
    }

    private void injectBossInitScript(BrowserContext targetContext) {
        String script = readResourceText(BOSS_INIT_SCRIPT_RESOURCE);
        if (script == null || script.isBlank()) {
            log.warn("Boss 反检测脚本未加载，资源不存在或为空: {}", BOSS_INIT_SCRIPT_RESOURCE);
            return;
        }
        String wrapped = "(function(){try{if(location&&location.origin===\"https://www.zhipin.com\"){"
                + "if(window.__bossAntiDetectInjected){return;}window.__bossAntiDetectInjected=true;"
                + script + "}}catch(e){}})();";
        targetContext.addInitScript(wrapped);
        log.info("Boss 反检测脚本已注入到Context: {}", BOSS_INIT_SCRIPT_RESOURCE);
    }

    private String readResourceText(String resourcePath) {
        try (InputStream input = PlaywrightManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                return null;
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取资源失败: {} - {}", resourcePath, e.getMessage());
            return null;
        }
    }

    private boolean resolveDeliveryRuntime() {
        String raw = firstRuntimeValue(
                "jobradar.delivery.runtime",
                "JOBRADAR_DELIVERY_RUNTIME",
                configuredDeliveryRuntime
        );
        if (raw == null || raw.isBlank() || "isolated".equalsIgnoreCase(raw.trim())) {
            return true;
        }
        if (!"shared".equalsIgnoreCase(raw.trim())) {
            log.warn("未知投递运行时 {}，回退 isolated", raw);
            return true;
        }
        return false;
    }

    /** 在指定平台自己的 Playwright 临界区执行动作。 */
    public void withPlatformAccess(String platform, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("Playwright action 不能为空");
        }
        if (!isolatedRuntime) {
            withPlaywrightAccess(action);
            return;
        }
        platformRuntime(platform).withAccess(action);
    }

    /** 可取消地等待指定平台自己的 Playwright 访问权。 */
    public boolean withPlatformAccessCancellable(String platform,
                                                 BooleanSupplier shouldCancel,
                                                 Runnable action) {
        if (shouldCancel == null || action == null) {
            throw new IllegalArgumentException("取消检查和 Playwright action 不能为空");
        }
        if (!isolatedRuntime) {
            return withPlaywrightAccessCancellable(shouldCancel, action);
        }
        return platformRuntime(platform).withAccessCancellable(shouldCancel, action);
    }

    private void initializePlatformRuntimes() {
        Path shell = resolveHeadlessShellExecutable();
        BrowserRuntimeConfig.Engine runtimeEngine = browserEngine;
        if (runtimeEngine == BrowserRuntimeConfig.Engine.AUTO && shell != null) {
            runtimeEngine = BrowserRuntimeConfig.Engine.HEADLESS_SHELL;
        }
        registerPlatformRuntime("boss", BOSS_URL, BOSS_DOMAIN, runtimeEngine, shell);
        registerPlatformRuntime("liepin", LIEPIN_URL, LIEPIN_DOMAIN, runtimeEngine, shell);
        registerPlatformRuntime("51job", JOB51_URL, JOB51_DOMAIN, runtimeEngine, shell);
        registerPlatformRuntime("zhilian", ZHILIAN_URL, ZHILIAN_DOMAIN, runtimeEngine, shell);
    }

    private void registerPlatformRuntime(String platform, String homeUrl, String domain,
                                         BrowserRuntimeConfig.Engine runtimeEngine, Path shell) {
        PlatformBrowserRuntime previous = platformRuntimes.remove(platform);
        if (previous != null) {
            try {
                previous.close();
            } catch (Exception e) {
                log.debug("替换平台运行时前关闭旧实例失败: platform={}, error={}", platform, e.getMessage());
            }
        }
        PlatformBrowserRuntime runtime = new PlatformBrowserRuntime(
                platform,
                homeUrl,
                domain,
                isBackgroundMode(),
                runtimeEngine,
                shell,
                cookieService,
                loggedIn -> onPlatformLoginStatus(platform, loggedIn),
                platformPlaywrightFactory
        );
        runtime.initialize();
        platformRuntimes.put(platform, runtime);
    }

    private void onPlatformLoginStatus(String platform, boolean loggedIn) {
        setLoginStatus(platform, loggedIn);
    }

    /**
     * 懒加载模式只恢复共享上下文的 Cookie，不创建页面、不触发站点导航。
     * 页面在对应平台第一次执行任务或登录状态检查时再创建。
     */
    private void loadPlatformCookiesWithoutPages() {
        loadPlatformCookies("boss", BOSS_DOMAIN);
        loadPlatformCookies("liepin", LIEPIN_DOMAIN);
        loadPlatformCookies("51job", JOB51_DOMAIN);
        loadPlatformCookies("zhilian", ZHILIAN_DOMAIN);
    }

    private void loadPlatformCookies(String platform, String domain) {
        if (context == null) {
            return;
        }
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform(platform);
            if (cookieEntity == null || cookieEntity.getCookieValue() == null
                    || cookieEntity.getCookieValue().isBlank()) {
                log.debug("懒加载未找到 {} Cookie", platform);
                return;
            }
            List<Cookie> cookies = filterCookiesByDomain(
                    parseCookiesFlexible(cookieEntity.getCookieValue(), domain), domain);
            if (cookies.isEmpty()) {
                log.warn("懒加载解析 {} Cookie 后没有可注入条目", platform);
                return;
            }
            context.addCookies(cookies);
            log.info("懒加载已注入 {} Cookie，共 {} 条", platform, cookies.size());
        } catch (Exception e) {
            log.warn("懒加载注入 {} Cookie 失败: {}", platform, e.getMessage());
        }
    }

    /**
     * 设置Boss直聘平台（加载Cookie、导航、监控）
     */
    private void setupBossPlatform() {
        log.info("开始初始化Boss直聘平台...");
        // 尝试从数据库加载Boss平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("boss");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), BOSS_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载Boss Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到Boss Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载Boss Cookie失败: {}", e.getMessage());
        }

        // 导航到Boss直聘首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                bossPage.navigate(BOSS_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = bossPage.url();
                    pageAccessible = url != null && url.contains("zhipin.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("Boss直聘页面导航失败");
        }

        try {
            // 等待页面网络空闲，确保头部导航渲染完成
            try {
                bossPage.waitForLoadState(LoadState.NETWORKIDLE);
            } catch (Exception e) {
                log.debug("等待Boss页面网络空闲失败: {}", e.getMessage());
            }

            // 初始化阶段不主动跳转登录页，仅在导航后设置状态
            // 参考猎聘实现：加载Cookie并导航后，由业务侧决定是否触发后续登录流程
        } catch (Exception e) {
            log.warn("Boss直聘页面导航失败: {}", e.getMessage());
        }
        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("boss", checkIfLoggedIn());
        // 设置登录状态监控
        setupLoginMonitoring(bossPage);
    }

    /**
     * 检查Boss是否已登录
     */
    private boolean checkIfLoggedIn() {
        if (bossPage == null || !playwrightAttached) {
            return false;
        }
        // 更稳健的登录判断：优先检测用户头像/昵称是否可见；备用检测登录入口是否可见且包含“登录”文本
        try {
            Locator userLabel = bossPage.locator("li.nav-figure span.label-text").first();
            if (userLabel.isVisible()) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            // 有些版本仅展示头像入口，无 label-text
            Locator navFigure = bossPage.locator("li.nav-figure").first();
            if (navFigure.isVisible()) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            // 未登录时通常有“登录/注册”入口或按钮容器
            Locator loginAnchor = bossPage.locator("li.nav-sign a, .btns").first();
            if (loginAnchor.isVisible()) {
                String text = loginAnchor.textContent();
                if (text != null && text.contains("登录")) {
                    return false;
                }
            }
        } catch (Exception ignored) {}

        // 无法明确检测到登录特征时，保守返回未登录
        return false;
    }

    /**
     * 设置登录状态监控
     *
     * @param page 页面实例
     */
    private void setupLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            tryWithPlaywrightAccess(() -> {
                if (frame == page.mainFrame()) {
                    // 事件触发的检查也必须和任务共用同一访问锁。
                    if (!bossMonitoringPaused) {
                        checkLoginStatus(page, "boss");
                    }
                }
            });
        });

        log.info("{}平台登录状态监控已启用", "boss");
    }

    /**
     * 设置猎聘平台（加载Cookie、导航、监控）
     */
    private void setupLiepinPlatform() {
        log.info("开始初始化猎聘平台...");

        // 尝试从数据库加载猎聘平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("liepin");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), LIEPIN_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载猎聘 Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析猎聘Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到猎聘Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载猎聘Cookie失败: {}", e.getMessage());
        }

        // 导航到猎聘首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                liepinPage.navigate(LIEPIN_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = liepinPage.url();
                    pageAccessible = url != null && url.contains("liepin.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("猎聘页面导航失败");
        }

        // 等待页面网络空闲，确保头部导航渲染完成
        try {
            liepinPage.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (Exception e) {
            log.debug("等待猎聘页面网络空闲失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("liepin", checkIfLiepinLoggedIn());
        // 设置登录状态监控
        setupLiepinLoginMonitoring(liepinPage);
    }

    /**
     * 检查猎聘是否已登录
     * 已登录：能找到用户头像 <img class="header-quick-menu-user-photo" ...>
     * 未登录：能找到 <span id="header-quick-menu-login">登录/注册</span>
     */
    private boolean checkIfLiepinLoggedIn() {
        try {
            // 先检查“登录/注册”入口是否可见，若可见则明确未登录
            try {
                Locator loginEntry = liepinPage.locator(
                    "#header-quick-menu-login, a[href*='login'], a[data-key='login'], button[data-key='login'], text=/登录|注册/").first();
                if (loginEntry.isVisible()) {
                    if (isBackgroundMode()) {
                        log.info("后台模式检测到猎聘未登录，等待切换 visible-login 后扫码");
                        return false;
                    }
                    log.info("检测到未登录猎聘，保持在登录页或首页等待扫码登录");
                    // 若不在登录页，则导航到登录页并尝试切换二维码
                    String currentUrl = null;
                    try { currentUrl = liepinPage.url(); } catch (Exception ignored) {}
                    try {
                        if (currentUrl == null || !currentUrl.contains("/login")) {
                            liepinPage.navigate("https://www.liepin.com/login");
                            try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                        // 优先点击官方切换二维码的容器
                        Locator qrSwitch = liepinPage.locator(".switch-type-mask-img-box").first();
                        if (qrSwitch.isVisible()) {
                            qrSwitch.click();
                            log.info("已切换到猎聘二维码登录页面，等待用户扫码...");
                        } else {
                            // 兼容新版页面：图片资源名包含 qrcode-btn，需要点击其父级按钮
                            Locator qrImg = liepinPage.locator("img[src*='qrcode-btn']").first();
                            if (qrImg.count() > 0 && qrImg.isVisible()) {
                                try {
                                    // 尝试点击父节点或最近的可点击容器
                                    qrImg.click();
                                } catch (Exception ignored) {
                                    try {
                                        Locator parentBtn = qrImg.locator("xpath=ancestor::button[1] | xpath=ancestor::*[contains(@class,'btn')][1]").first();
                                        if (parentBtn.count() > 0 && parentBtn.isVisible()) {
                                            parentBtn.click();
                                        }
                                    } catch (Exception ignored2) {}
                                }
                                log.info("已通过二维码按钮切换到扫码登录状态");
                            }
                        }
                    } catch (Exception e) {
                        log.debug("猎聘登录页引导/二维码切换失败: {}", e.getMessage());
                    }
                    return false;
                }
            } catch (Exception ignored) {}

            // 再检查已登录特征：用户信息容器或用户头像是否存在（无需强制可见）
            try {
                if (liepinPage.locator("#header-quick-menu-user-info").count() > 0) {
                    log.debug("猎聘登录检测：存在用户信息容器，判定已登录");
                    return true;
                }
            } catch (Exception ignored) {}

            try {
                if (liepinPage.locator("img.header-quick-menu-user-photo, .header-quick-menu-user-photo").count() > 0) {
                    log.debug("猎聘登录检测：存在用户头像元素，判定已登录");
                    return true;
                }
            } catch (Exception ignored) {}

            // 兜底：若不存在登录入口且也未找到明确已登录特征，按已登录处理（避免误判）
            try {
                boolean loginEntryExists = liepinPage.locator("#header-quick-menu-login, a[href*='login']").count() > 0;
                if (!loginEntryExists) {
                    log.info("猎聘登录检测：未发现登录入口，兜底判定为已登录");
                    return true;
                }
            } catch (Exception ignored) {}

            // 默认未登录
            log.debug("猎聘登录检测：未匹配到明确特征，判定未登录");
            return false;
        } catch (Exception e) {
            log.debug("猎聘登录检测异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 设置猎聘登录状态监控
     *
     * @param page 页面实例
     */
    private void setupLiepinLoginMonitoring(Page page) {
        if (page == null || !liepinMonitoredPages.add(page)) {
            return;
        }

        page.onClose(closedPage -> {
            if (liepinPage == closedPage) {
                liepinPage = null;
                log.warn("猎聘页面已关闭，等待投递流程按需恢复");
            }
        });

        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            tryWithPlaywrightAccess(() -> {
                if (frame == page.mainFrame()) {
                    if (!liepinMonitoringPaused) {
                        checkLiepinLoginStatus(page);
                    }
                }
            });
        });

        log.info("猎聘平台登录状态监控已启用");
    }

    /** 返回可供投递使用的 Boss 页面；懒加载时第一次调用才创建和导航。 */
    public Page ensureBossPageReady() {
        if (isolatedRuntime) {
            return platformRuntime("boss").ensurePageReady();
        }
        playwrightAccessLock.lock();
        try {
            if (isUsableBossPage(bossPage)) {
                return bossPage;
            }
            ensureBrowserContextReady("Boss");
            if (isUsableBossPage(bossPage)) {
                return bossPage;
            }
            if (context == null || browser == null || !browser.isConnected()) {
                throw new IllegalStateException("Boss 浏览器上下文已失效，当前恢复条件不满足");
            }

            Page recovered = context.newPage();
            configurePage(recovered);
            bossPage = recovered;
            try {
                setupBossPlatform();
                return recovered;
            } catch (Exception e) {
                if (bossPage == recovered) {
                    bossPage = null;
                }
                try {
                    recovered.close();
                } catch (Exception ignored) {
                }
                throw new IllegalStateException("Boss 页面恢复失败", e);
            }
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    /** 返回可供投递使用的智联页面；懒加载时第一次调用才创建和导航。 */
    public Page ensureZhilianPageReady() {
        if (isolatedRuntime) {
            return platformRuntime("zhilian").ensurePageReady();
        }
        playwrightAccessLock.lock();
        try {
            if (isUsableZhilianPage(zhilianPage)) {
                return zhilianPage;
            }
            ensureBrowserContextReady("智联招聘");
            if (isUsableZhilianPage(zhilianPage)) {
                return zhilianPage;
            }
            if (context == null || browser == null || !browser.isConnected()) {
                throw new IllegalStateException("智联浏览器上下文已失效，当前恢复条件不满足");
            }

            Page recovered = context.newPage();
            configurePage(recovered);
            zhilianPage = recovered;
            try {
                setupZhilianPlatform();
                return recovered;
            } catch (Exception e) {
                if (zhilianPage == recovered) {
                    zhilianPage = null;
                }
                try {
                    recovered.close();
                } catch (Exception ignored) {
                }
                throw new IllegalStateException("智联页面恢复失败", e);
            }
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    private void ensureBrowserContextReady(String platform) {
        if (isBrowserConnectionUsable()) {
            return;
        }
        if (connectedOverCdp && isCdpReachable(CDP_PORT)) {
            reconnectPlaywrightAfterHandsOff(platform + "-target-closed");
            if (isBrowserConnectionUsable()) {
                return;
            }
        }
        throw new IllegalStateException(platform + " 浏览器连接已失效，当前恢复条件不满足");
    }

    private boolean isUsableBossPage(Page candidate) {
        if (candidate == null || candidate.isClosed()) {
            return false;
        }
        return browser != null && browser.isConnected() && context != null;
    }

    private boolean isUsableZhilianPage(Page candidate) {
        if (candidate == null || candidate.isClosed()) {
            return false;
        }
        return browser != null && browser.isConnected() && context != null;
    }

    /**
     * 返回可供投递使用的猎聘页面；页面关闭时重建，浏览器连接失效时尝试一次 CDP 重连。
     * 调用方通常已经持有共享 Playwright 锁；该方法自身加锁以覆盖独立调用场景。
     */
    public Page ensureLiepinPageReady() {
        if (isolatedRuntime) {
            return platformRuntime("liepin").ensurePageReady();
        }
        playwrightAccessLock.lock();
        try {
            if (isUsableLiepinPage(liepinPage)) {
                return liepinPage;
            }

            log.warn("猎聘页面不可用，开始执行生命周期恢复");
            if (!isBrowserConnectionUsable()) {
                if (connectedOverCdp && isCdpReachable(CDP_PORT)) {
                    reconnectPlaywrightAfterHandsOff("liepin-target-closed");
                    // 投递期间仍由任务独占猎聘页，重连方法恢复监控后这里立即暂停。
                    liepinMonitoringPaused = true;
                } else {
                    throw new IllegalStateException("猎聘浏览器连接已失效，当前恢复条件不满足");
                }
            }

            if (isUsableLiepinPage(liepinPage)) {
                return liepinPage;
            }
            return createLiepinRecoveryPage();
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    private Page createLiepinRecoveryPage() {
        if (context == null || browser == null || !browser.isConnected()) {
            throw new IllegalStateException("猎聘浏览器上下文已失效，当前恢复条件不满足");
        }

        Page recovered = context.newPage();
        configurePage(recovered);
        liepinPage = recovered;
        setupLiepinLoginMonitoring(recovered);
        try {
            recovered.navigate(LIEPIN_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            recovered.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(10000));
            setLoginStatus("liepin", checkIfLiepinLoggedIn());
            log.info("猎聘页面已恢复");
            return recovered;
        } catch (Exception e) {
            if (liepinPage == recovered) {
                liepinPage = null;
            }
            try {
                recovered.close();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("猎聘页面恢复失败", e);
        }
    }

    private boolean isUsableLiepinPage(Page candidate) {
        if (candidate == null || candidate.isClosed()) {
            return false;
        }
        return browser != null && browser.isConnected() && context != null;
    }

    /** 返回可供投递使用的 51job 页面；页面或 CDP 连接失效时执行一次生命周期恢复。 */
    public Page ensureJob51PageReady() {
        if (isolatedRuntime) {
            return platformRuntime("51job").ensurePageReady();
        }
        playwrightAccessLock.lock();
        try {
            if (isUsableJob51Page(job51Page)) {
                return job51Page;
            }

            log.warn("51job页面不可用，开始执行生命周期恢复");
            if (!isBrowserConnectionUsable()) {
                if (connectedOverCdp && isCdpReachable(CDP_PORT)) {
                    reconnectPlaywrightAfterHandsOff("job51-target-closed");
                    job51MonitoringPaused = true;
                } else {
                    throw new IllegalStateException("51job浏览器连接已失效，暂时无法恢复页面");
                }
            }

            if (isUsableJob51Page(job51Page)) {
                return job51Page;
            }
            return createJob51RecoveryPage();
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    private Page createJob51RecoveryPage() {
        if (context == null || browser == null || !browser.isConnected()) {
            throw new IllegalStateException("51job浏览器上下文已失效，暂时无法恢复页面");
        }

        Page recovered = context.newPage();
        configurePage(recovered);
        job51Page = recovered;
        setup51jobLoginMonitoring(recovered);
        try {
            recovered.navigate(JOB51_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            recovered.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(10000));
            setLoginStatus("51job", checkIf51jobLoggedIn());
            log.info("51job页面已恢复");
            return recovered;
        } catch (Exception e) {
            if (job51Page == recovered) {
                job51Page = null;
            }
            try {
                recovered.close();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("51job页面恢复失败", e);
        }
    }

    private boolean isUsableJob51Page(Page candidate) {
        if (candidate == null || candidate.isClosed()) {
            return false;
        }
        return browser != null && browser.isConnected() && context != null;
    }

    private boolean isBrowserConnectionUsable() {
        if (!playwrightAttached || browser == null || context == null) {
            return false;
        }
        try {
            return browser.isConnected() && context.pages() != null;
        } catch (Exception e) {
            log.debug("检查猎聘浏览器连接失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 设置51job平台（加载Cookie、导航、监控）
     */
    private void setup51jobPlatform() {
        log.info("开始初始化51job平台...");

        // 尝试从数据库加载51job平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("51job");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), JOB51_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载51job Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析51job Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到51job Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载51job Cookie失败: {}", e.getMessage());
        }

        // 导航到51job首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                job51Page.navigate(JOB51_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = job51Page.url();
                    pageAccessible = url != null && url.contains("51job.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("51job页面导航失败");
        }

        try {
            // 检查是否需要登录
            if (!checkIf51jobLoggedIn()) {
                if (isBackgroundMode()) {
                    log.info("后台模式检测到51job未登录，等待切换 visible-login 后扫码");
                } else {
                    log.info("检测到未登录51job，尝试自动点击登录入口并等待用户登录");

                    try {
                        // 优先使用用户提供的选择器：span.login.loginBtnClick
                        Locator loginEntry = job51Page.locator("span.login.loginBtnClick").first();
                        if (loginEntry != null && loginEntry.isVisible()) {
                            loginEntry.click(new Locator.ClickOptions().setTimeout(30000));
                            log.info("已点击 51job 首页的 ‘登录/注册’ 入口，等待用户登录...");
                            asyncWaitFor51jobLogin();
                        } else {
                            // 备用选择器：文本匹配
                            Locator altLoginEntry = job51Page.locator("text=/登录\\/注册|登录|注册/").first();
                            if (altLoginEntry != null && altLoginEntry.isVisible()) {
                                altLoginEntry.click(new Locator.ClickOptions().setTimeout(30000));
                                log.info("已点击 51job 首页的登录入口（文本匹配），等待用户登录...");
                                asyncWaitFor51jobLogin();
                            } else {
                                log.info("未找到 51job 登录入口元素，保持在首页等待用户自行登录");
                                // 启动后台轮询，确保无导航也能检测到登录成功
                                asyncWaitFor51jobLogin();
                            }
                        }
                    } catch (Exception clickEx) {
                        log.warn("尝试点击 51job 登录入口时发生异常: {}，保持在首页等待用户登录", clickEx.getMessage());
                        // 启动后台轮询，避免异常导致无法检测登录成功
                        asyncWaitFor51jobLogin();
                    }
                }
            } else {
                log.info("51job已登录");
            }
        } catch (Exception e) {
            log.warn("51job页面初始化检查失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("51job", checkIf51jobLoggedIn());
        // 设置登录状态监控
        setup51jobLoginMonitoring(job51Page);
    }

    /**
     * 检查51job是否已登录
     */
  private boolean checkIf51jobLoggedIn() {
      try {
            // 未登录特征：存在“登录/注册”入口
            Locator loginBtn = job51Page.locator("span.login.loginBtnClick").first();
            if (loginBtn.isVisible()) {
                String txt = (loginBtn.textContent() == null ? "" : loginBtn.textContent()).trim();
                if (txt.contains("登录")) {
                    return false;
                }
            }
            // 已登录特征（增强）：顶部显示用户名入口或个人中心链接
            // 1) 明确的用户名锚点（类名：uname e_icon at）
            Locator userAnchor = job51Page.locator("a.uname.e_icon.at");
            if (userAnchor.count() > 0 && userAnchor.first().isVisible()) {
                return true;
            }
            // 2) 个人中心链接（href=/pc/my/myjob）
            Locator myJobLink = job51Page.locator("a[href*='/pc/my/myjob']");
            if (myJobLink.count() > 0 && myJobLink.first().isVisible()) {
                return true;
            }
            // 3) 其他可能的用户信息容器（旧的兜底选择器）
            return job51Page.locator(".login-info, .user-info, .username").count() > 0;
      } catch (Exception e) {
          return false;
      }
  }

    /**
     * 设置51job登录状态监控
     *
     * @param page 页面实例
     */
    private void setup51jobLoginMonitoring(Page page) {
        if (page == null || !job51MonitoredPages.add(page)) {
            return;
        }

        page.onClose(closedPage -> {
            if (job51Page == closedPage) {
                job51Page = null;
                log.warn("51job页面已关闭，等待投递流程按需恢复");
            }
        });

        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            tryWithPlaywrightAccess(() -> {
                if (frame == page.mainFrame()) {
                    if (!job51MonitoringPaused) {
                        check51jobLoginStatus(page);
                    }
                }
            });
        });

        log.info("51job平台登录状态监控已启用");
    }

    /**
     * 检查51job登录状态
     *
     * @param page 页面实例
     */
    private void check51jobLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkIf51jobLoggedIn();
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("51job");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                on51jobLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查51job平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 51job登录成功回调
     */
    private void on51jobLoginSuccess() {
        log.info("51job平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("51job", true);

        // Cookie 持久化由登录卡片中的显式获取动作负责。
    }

    /**
     * 在后台异步等待 51job 登录成功。
     * 说明：不阻塞初始化主流程，独立线程每秒轮询一次登录状态，最长等待5分钟。
     */
    private void asyncWaitFor51jobLogin() {
        Thread waitThread = new Thread(() -> {
            try {
                int maxSeconds = 300; // 最长等待 5 分钟
                for (int i = 0; i < maxSeconds; i++) {
                    final boolean[] loggedIn = {false};
                    withPlaywrightAccess(() -> {
                        try {
                            loggedIn[0] = checkIf51jobLoggedIn();
                            if (loggedIn[0]) {
                                // 登录成功回调也必须和 Cookie 读取处于同一临界区。
                                on51jobLoginSuccess();
                            }
                        } catch (Exception ignored) {
                        }
                    });

                    if (loggedIn[0]) {
                        log.info("后台等待检测到 51job 登录成功，用时约 {} 秒", i);
                        return;
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("等待 51job 登录线程被中断");
                        return;
                    }
                }
                log.warn("后台等待 51job 登录超时（约5分钟），仍未检测到登录成功");
            } catch (Exception e) {
                log.warn("后台等待 51job 登录过程中发生异常: {}", e.getMessage());
            }
        }, "wait-51job-login-thread");

        waitThread.setDaemon(true);
        waitThread.start();
    }

    /**
     * 保存51job Cookie到数据库
     *
     * @param remark 备注信息
     */
  private void save51jobCookiesToDatabase(String remark) {
      try {
          List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), JOB51_DOMAIN);
          // 使用ObjectMapper序列化为JSON字符串
          String cookieJson = new ObjectMapper().writeValueAsString(cookies);
          boolean result = cookieService.saveOrUpdateCookie("51job", cookieJson, remark);
          if (result) {
                long now = System.currentTimeMillis();
                boolean shouldInfoLog = (now - last51CookieLogMs) > 15000 // 至少间隔15秒
                        || cookies.size() != last51CookieLogCount
                        || (remark != null && !remark.equals(last51CookieRemark));
                if (shouldInfoLog) {
                    log.info("保存51job Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
                    last51CookieLogMs = now;
                    last51CookieLogCount = cookies.size();
                    last51CookieRemark = remark == null ? "" : remark;
                } else {
                    // 近似重复的频繁调用，改为debug降低噪音
                    log.debug("保存51job Cookie成功(节流)，条数={}，remark={}", cookies.size(), remark);
                }
          }
      } catch (Exception e) {
          log.warn("保存51job Cookie失败: {}", e.getMessage());
      }
  }

    /**
     * 主动保存51job Cookie到数据库（用于调试/验证）
     */
    public void save51jobCookiesToDb(String remark) {
        if (isolatedRuntime) {
            platformRuntime("51job").saveCookiesToDatabase(remark);
            return;
        }
        withPlaywrightAccess(() -> save51jobCookiesToDatabase(remark));
    }

    /**
     * 清理51job上下文中的Cookie
     */
    public void clear51jobCookies() {
        if (isolatedRuntime) {
            platformRuntime("51job").clearCookies();
            return;
        }
        withPlaywrightAccess(this::clear51jobCookiesInternal);
    }

    private void clear51jobCookiesInternal() {
        try {
            if (context != null) {
                context.clearCookies();
                log.info("已清理共享上下文中的所有Cookie");
            } else {
                log.warn("共享上下文不存在，无法清理Cookie");
            }
        } catch (Exception e) {
            log.error("清理共享上下文Cookie失败: {}", e.getMessage(), e);
            throw new RuntimeException("清理共享上下文Cookie失败", e);
        }
    }

    /**
     * 暂停51job页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pause51jobMonitoring() {
        if (isolatedRuntime) {
            platformRuntime("51job").pauseMonitoring();
            return;
        }
        job51MonitoringPaused = true;
        log.debug("51job登录监控已暂停");
    }

    /**
     * 恢复51job页面的后台登录监控
     */
    public void resume51jobMonitoring() {
        if (isolatedRuntime) {
            platformRuntime("51job").resumeMonitoring();
            return;
        }
        job51MonitoringPaused = false;
        log.debug("51job登录监控已恢复");
    }

    /**
     * 触发 51job 登录流程：打开登录页并点击“微信扫码登录”按钮
     */
    public void trigger51jobLogin() {
        if (isolatedRuntime) {
            platformRuntime("51job").triggerLogin();
            return;
        }
        if (isBackgroundMode()) {
            throw new IllegalStateException("当前为后台模式，请切换 visible-login 后再扫码登录");
        }
        withPlaywrightAccess(this::trigger51jobLoginInternal);
    }

    private void trigger51jobLoginInternal() {
        try {
            if (job51Page == null) {
                if (context == null) {
                    throw new IllegalStateException("浏览器上下文尚未初始化");
                }
                job51Page = context.newPage();
                configurePage(job51Page);
            }

            // 如果已登录则直接返回
            if (checkIf51jobLoggedIn()) {
                log.info("检测到已登录51job，跳过登录触发");
                return;
            }

            // 先尝试在首页点击“登录/注册”入口
            try {
                job51Page.navigate(JOB51_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                Locator loginEntry = job51Page.locator("span.login.loginBtnClick, text=/登录\\/注册|登录|注册/").first();
                if (loginEntry.isVisible()) {
                    loginEntry.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                }
            } catch (Exception e) {
                log.debug("在首页尝试点击登录入口失败: {}", e.getMessage());
            }

            // 跳转到官方登录页
            String loginUrl = "https://login.51job.com/login.php";
            job51Page.navigate(loginUrl, new Page.NavigateOptions()
                .setTimeout(60000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // 尝试点击“微信扫码登录”按钮
            Locator wechatScanBtn = job51Page.locator(
                "i.passIcon.custom-cursor-on-hover[data-sensor-id='sensor_login_wechatScan'], " +
                "i.passIcon[data-sensor-id='sensor_login_wechatScan'], " +
                "[data-sensor-id='sensor_login_wechatScan']"
            ).first();

            if (wechatScanBtn.isVisible()) {
                wechatScanBtn.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                log.info("已点击51job登录页的微信扫码按钮，等待用户扫码登录...");
            } else {
                log.warn("未找到微信扫码登录按钮，用户可在登录页自行选择扫码方式");
            }

            // 不阻塞等待：监控会自动检测到登录成功并保存Cookie
        } catch (Exception e) {
            log.error("触发51job登录流程失败: {}", e.getMessage(), e);
            throw new RuntimeException("触发51job登录流程失败", e);
        }
    }

    /**
     * 设置智联招聘平台（加载Cookie、导航、监控）
     */
    private void setupZhilianPlatform() {
        log.info("开始初始化智联招聘平台...");

        // 尝试从数据库加载智联招聘平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("zhilian");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), ZHILIAN_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载智联招聘 Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析智联招聘Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到智联招聘Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载智联招聘Cookie失败: {}", e.getMessage());
        }

        // 导航到智联招聘首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                zhilianPage.navigate(ZHILIAN_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = zhilianPage.url();
                    pageAccessible = url != null && url.contains("zhaopin.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("智联招聘页面导航失败");
        }

        // 等待页面加载完成
        try {
            zhilianPage.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (Exception e) {
            log.debug("等待智联页面网络空闲失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("zhilian", checkIfZhilianLoggedIn());
        // 设置登录状态监控
        setupZhilianLoginMonitoring(zhilianPage);
    }

    /**
     * 检查智联招聘是否已登录
     * 未登录时只在首次检测时引导用户到登录页
     */
    private boolean checkIfZhilianLoggedIn() {
        try {
            if (zhilianPage == null) {
                return false;
            }

            boolean isLoggedIn = false;
            boolean loginButtonExists = false;

            // 检查是否存在"登录/注册"按钮
            try {
                Locator loginButton = zhilianPage.locator("a.home-header__c-no-login").first();
                int count = loginButton.count();
                if (count > 0) {
                    loginButtonExists = true;
                    // 尝试获取文本进一步确认
                    try {
                        String buttonText = loginButton.textContent();
                        if (buttonText != null && buttonText.contains("登录")) {
                            loginButtonExists = true;
                        }
                    } catch (Exception e) {
                        log.debug("智联招聘：获取登录按钮文本失败: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("智联招聘：检查登录按钮时异常: {}", e.getMessage());
            }

            // 如果存在登录按钮，说明未登录
            if (loginButtonExists) {
                if (isBackgroundMode()) {
                    log.info("后台模式检测到智联未登录，等待切换 visible-login 后扫码");
                    return false;
                }
                // 只在首次检测到未登录时执行引导操作
                if (!zhilianLoginGuided) {
                    log.info("检测到未登录智联招聘，重定向到登录页面");
                    zhilianLoginGuided = true;

                    // 重定向到登录页面
                    String currentUrl = null;
                    try {
                        currentUrl = zhilianPage.url();
                    } catch (Exception ignored) {
                    }

                    try {
                        if (currentUrl == null || !currentUrl.contains("passport.zhaopin.com/login")) {
                            boolean loginNavOk = false;
                            try {
                                zhilianPage.navigate(
                                        "https://passport.zhaopin.com/login",
                                        new Page.NavigateOptions()
                                                .setTimeout(60000)
                                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                );
                                loginNavOk = true;
                            } catch (Exception navEx) {
                                String urlAfter = null;
                                try {
                                    urlAfter = zhilianPage.url();
                                } catch (Exception ignored2) {}

                                if (urlAfter != null && urlAfter.contains("passport.zhaopin.com")) {
                                    loginNavOk = true;
                                    log.debug("智联招聘：登录页导航异常但已在登录域: {}", navEx.getMessage());
                                } else {
                                    log.warn("智联招聘：导航至登录页失败: {}", navEx.getMessage());
                                }
                            }

                            if (loginNavOk) {
                                try {
                                    zhilianPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
                                } catch (Exception ignored) {}
                                try {
                                    zhilianPage.waitForSelector(
                                            "div.zppp-panel-normal-bar__img, " +
                                            "div.passport-login, #J_loginWrap, " +
                                            "div[class*='qrcode'], img[src*='qrcode']",
                                            new Page.WaitForSelectorOptions().setTimeout(30000)
                                    );
                                } catch (Exception e) {
                                    log.debug("智联招聘：登录页关键元素等待失败: {}", e.getMessage());
                                }
                            }
                        }

                        // 点击二维码登录按钮
                        Locator qrToggle = zhilianPage.locator("div.zppp-panel-normal-bar__img").first();
                        if (qrToggle.count() > 0 && qrToggle.isVisible()) {
                            qrToggle.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                            log.info("已切换到智联二维码登录页面，等待用户扫码...");
                        } else {
                            log.info("智联招聘登录页面已打开，等待用户扫码...");
                        }
                    } catch (Exception e) {
//                        log.warn("智联招聘：打开二维码登录面板失败: {}", e.getMessage());
                    }
                }
                return false;
            }

            // 检查是否有已登录的特征
            try {
                String url = zhilianPage.url();
                if (url != null && url.contains("i.zhaopin.com")) {
                    log.debug("智联招聘：URL包含i.zhaopin.com，判定为已登录");
                    isLoggedIn = true;
                }
            } catch (Exception ignore) {
            }

            // 如果没有登录按钮，也认为已登录
            if (!loginButtonExists) {
                log.debug("智联招聘：未检测到登录按钮，判定为已登录");
                isLoggedIn = true;
            }

            return isLoggedIn;
        } catch (Exception e) {
            log.warn("智联招聘：检查登录状态异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 设置智联招聘登录状态监控
     *
     * @param page 页面实例
     */
    private void setupZhilianLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            tryWithPlaywrightAccess(() -> {
                if (frame == page.mainFrame()) {
                    if (!zhilianMonitoringPaused) {
                        checkZhilianLoginStatus(page);
                    }
                }
            });
        });

        log.info("智联招聘平台登录状态监控已启用");
    }

    /**
     * 检查智联招聘登录状态
     *
     * @param page 页面实例
     */
    private void checkZhilianLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkIfZhilianLoggedIn();
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("zhilian");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onZhilianLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查智联招聘平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 主动触发智联招聘登录：点击二维码入口并等待登录成功跳转
     */
    public void triggerZhilianLogin() {
        if (isolatedRuntime) {
            platformRuntime("zhilian").triggerLogin();
            return;
        }
        if (isBackgroundMode()) {
            throw new IllegalStateException("当前为后台模式，请切换 visible-login 后再扫码登录");
        }
        withPlaywrightAccess(this::triggerZhilianLoginInternal);
    }

    private void triggerZhilianLoginInternal() {
        try {
            if (zhilianPage == null) {
                throw new IllegalStateException("智联招聘页面未初始化");
            }

            // 导航到智联首页，确保DOM就绪
            zhilianPage.navigate(ZHILIAN_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // 如果看到未登录入口，尝试打开二维码登录面板
            Locator noLoginAnchor = zhilianPage.locator("a.home-header__c-no-login").first();
            if (noLoginAnchor.isVisible()) {
                Locator qrToggle = zhilianPage.locator("div.zppp-panel-normal-bar__img").first();
                if (qrToggle.isVisible()) {
                    qrToggle.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                    log.info("已点击智联二维码登录入口，等待用户扫码...");
                } else {
                    log.warn("未找到二维码登录入口元素：div.zppp-panel-normal-bar__img");
                }
            } else {
                log.info("未检测到未登录入口，可能已登录或在其他页面");
            }

            // 监听登录成功：等待URL跳转到 i.zhaopin.com 或用户信息元素出现
            try {
                zhilianPage.waitForURL("**i.zhaopin.com**", new Page.WaitForURLOptions().setTimeout(120_000));
                onZhilianLoginSuccess();
                return;
            } catch (Exception ignored) {
            }
            try {
                zhilianPage.waitForSelector(".user-info, .user-name, .username-text", new Page.WaitForSelectorOptions().setTimeout(120_000));
                onZhilianLoginSuccess();
            } catch (Exception e) {
                log.warn("等待智联登录成功超时或失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("触发智联登录流程失败: {}", e.getMessage(), e);
            throw new RuntimeException("触发智联登录流程失败", e);
        }
    }

    /**
     * 智联招聘登录成功回调
     */
    private void onZhilianLoginSuccess() {
        log.info("智联招聘平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("zhilian", true);

        // Cookie 持久化由登录卡片中的显式获取动作负责。
    }

    /**
     * 保存智联招聘Cookie到数据库
     *
     * @param remark 备注信息
     */
    private void saveZhilianCookiesToDatabase(String remark) {
        try {
            List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), ZHILIAN_DOMAIN);
            // 使用ObjectMapper序列化为JSON字符串
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            boolean result = cookieService.saveOrUpdateCookie("zhilian", cookieJson, remark);
            if (result) {
                log.info("保存智联招聘Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
            }
        } catch (Exception e) {
            log.warn("保存智联招聘Cookie失败: {}", e.getMessage());
        }
    }

    /**
     * 主动保存智联招聘Cookie到数据库（用于调试/验证）
     */
    public void saveZhilianCookiesToDb(String remark) {
        if (isolatedRuntime) {
            platformRuntime("zhilian").saveCookiesToDatabase(remark);
            return;
        }
        withPlaywrightAccess(() -> saveZhilianCookiesToDatabase(remark));
    }

    /**
     * 统一按平台保存 Cookie 到数据库
     *
     * @param platform 平台标识（boss/liepin/51job/zhilian）
     * @param remark   备注
     */
    public void saveCookiesToDb(String platform, String remark) {
        if (isolatedRuntime) {
            platformRuntime(platform).saveCookiesToDatabase(remark);
            return;
        }
        withPlaywrightAccess(() -> saveCookiesToDbInternal(platform, remark));
    }

    private void saveCookiesToDbInternal(String platform, String remark) {
        switch (platform) {
            case "boss" -> saveBossCookiesToDatabase(remark);
            case "liepin" -> saveLiepinCookiesToDatabase(remark);
            case "51job" -> save51jobCookiesToDatabase(remark);
            case "zhilian" -> saveZhilianCookiesToDatabase(remark);
            default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
    }

    /**
     * 清理智联招聘上下文中的Cookie
     */
    public void clearZhilianCookies() {
        if (isolatedRuntime) {
            platformRuntime("zhilian").clearCookies();
            return;
        }
        withPlaywrightAccess(this::clearZhilianCookiesInternal);
    }

    private void clearZhilianCookiesInternal() {
        try {
            if (context != null) {
                context.clearCookies();
                log.info("已清理共享上下文中的所有Cookie");
            } else {
                log.warn("共享上下文不存在，无法清理Cookie");
            }
        } catch (Exception e) {
            log.error("清理共享上下文Cookie失败: {}", e.getMessage(), e);
            throw new RuntimeException("清理共享上下文Cookie失败", e);
        }
    }

    /**
     * 暂停智联招聘页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseZhilianMonitoring() {
        if (isolatedRuntime) {
            platformRuntime("zhilian").pauseMonitoring();
            return;
        }
        zhilianMonitoringPaused = true;
        log.debug("智联招聘登录监控已暂停");
    }

    /**
     * 恢复智联招聘页面的后台登录监控
     */
    public void resumeZhilianMonitoring() {
        if (isolatedRuntime) {
            platformRuntime("zhilian").resumeMonitoring();
            return;
        }
        zhilianMonitoringPaused = false;
        log.debug("智联招聘登录监控已恢复");
    }

    /**
     * 检查猎聘登录状态
     *
     * @param page 页面实例
     */
    private void checkLiepinLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkIfLiepinLoggedIn();
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("liepin");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onLiepinLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查猎聘平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 猎聘登录成功回调
     */
    private void onLiepinLoginSuccess() {
        log.info("猎聘平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("liepin", true);

        // Cookie 持久化由登录卡片中的显式获取动作负责。
    }

    /**
     * 保存猎聘Cookie到数据库
     *
     * @param remark 备注信息
     */
    private void saveLiepinCookiesToDatabase(String remark) {
        try {
            List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), LIEPIN_DOMAIN);
            // 使用ObjectMapper序列化为JSON字符串
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            boolean result = cookieService.saveOrUpdateCookie("liepin", cookieJson, remark);
            if (result) {
                log.info("保存猎聘Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
            }
        } catch (Exception e) {
            log.warn("保存猎聘Cookie失败: {}", e.getMessage());
        }
    }

    /**
     * 主动保存猎聘Cookie到数据库（用于调试/验证）
     */
    public void saveLiepinCookiesToDb(String remark) {
        if (isolatedRuntime) {
            platformRuntime("liepin").saveCookiesToDatabase(remark);
            return;
        }
        withPlaywrightAccess(() -> saveLiepinCookiesToDatabase(remark));
    }

    /**
     * 清理猎聘上下文中的Cookie
     */
    public void clearLiepinCookies() {
        if (isolatedRuntime) {
            platformRuntime("liepin").clearCookies();
            return;
        }
        withPlaywrightAccess(this::clearLiepinCookiesInternal);
    }

    private void clearLiepinCookiesInternal() {
        try {
            if (context != null) {
                context.clearCookies();
                log.info("已清理共享上下文中的所有Cookie");
            } else {
                log.warn("共享上下文不存在，无法清理Cookie");
            }
        } catch (Exception e) {
            log.error("清理共享上下文Cookie失败: {}", e.getMessage(), e);
            throw new RuntimeException("清理共享上下文Cookie失败", e);
        }
    }

    /**
     * 暂停猎聘页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseLiepinMonitoring() {
        if (isolatedRuntime) {
            platformRuntime("liepin").pauseMonitoring();
            return;
        }
        liepinMonitoringPaused = true;
        log.debug("猎聘登录监控已暂停");
    }

    /**
     * 恢复猎聘页面的后台登录监控
     */
    public void resumeLiepinMonitoring() {
        if (isolatedRuntime) {
            platformRuntime("liepin").resumeMonitoring();
            return;
        }
        liepinMonitoringPaused = false;
        log.debug("猎聘登录监控已恢复");
    }

    /**
     * 检查登录状态
     *
     * @param page     页面实例
     * @param platform 平台名称
     */
    private void checkLoginStatus(Page page, String platform) {
        try {
            boolean isLoggedIn = false;
            if (platform.equals("boss")) {
                // 统一复用更稳健的Boss登录判断逻辑
                isLoggedIn = checkIfLoggedIn();
            }
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get(platform);
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onLoginSuccess(platform);
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查{}平台登录状态时发生异常: {}", platform, e.getMessage());
        }
    }

    /**
     * 登录成功回调
     *
     * @param platform 平台名称
     */
    private void onLoginSuccess(String platform) {
        log.info("{}平台登录成功", platform);

        // 更新登录状态并通知（统一使用setLoginStatus方法）
        setLoginStatus(platform, true);

        // Cookie 持久化由登录卡片中的显式获取动作负责。
    }

    /**
     * 统一的Boss Cookie保存方法（使用JSON序列化）
     *
     * @param remark 备注信息
     */
    private void saveBossCookiesToDatabase(String remark) {
        try {
            List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), BOSS_DOMAIN);
            // 使用ObjectMapper序列化为JSON字符串
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            boolean result = cookieService.saveOrUpdateCookie("boss", cookieJson, remark);
            if (result) {
                log.info("保存Boss Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
            }
        } catch (Exception e) {
            log.warn("保存Boss Cookie失败: {}", e.getMessage());
        }
    }

    /**
     * 主动保存 Boss Cookie 到数据库（用于调试/验证）
     */
    public void saveBossCookiesToDb(String remark) {
        if (isolatedRuntime) {
            platformRuntime("boss").saveCookiesToDatabase(remark);
            return;
        }
        withPlaywrightAccess(() -> saveBossCookiesToDatabase(remark));
    }

    /**
     * 清理Boss上下文中的Cookie
     * 用于退出登录时清除浏览器上下文中的所有Cookie
     */
    public void clearBossCookies() {
        if (isolatedRuntime) {
            platformRuntime("boss").clearCookies();
            return;
        }
        withPlaywrightAccess(this::clearBossCookiesInternal);
    }

    private void clearBossCookiesInternal() {
        try {
            if (context != null) {
                context.clearCookies();
                log.info("已清理共享上下文中的所有Cookie");
            } else {
                log.warn("共享上下文不存在，无法清理Cookie");
            }
        } catch (Exception e) {
            log.error("清理共享上下文Cookie失败: {}", e.getMessage(), e);
            throw new RuntimeException("清理共享上下文Cookie失败", e);
        }
    }

    /**
     * 定时检查登录状态（每3秒）
     * 用于捕获通过DOM元素判断登录状态的场景（无导航也可触发）
     */
    @Scheduled(fixedDelay = 3000)
    public void scheduledLoginCheck() {
        for (BrowserLoginSession session : browserLoginSessions.values()) {
            try {
                session.pollStatus();
            } catch (Exception e) {
                log.debug("可见登录会话检测异常: {}", e.getMessage());
            }
        }
        if (isolatedRuntime) {
            for (PlatformBrowserRuntime runtime : platformRuntimes.values()) {
                try {
                    runtime.tryRefreshLoginStatus();
                } catch (Exception e) {
                    log.debug("独立平台登录检测异常: platform={}, error={}", runtime.getPlatform(), e.getMessage());
                }
            }
            return;
        }
        if (!playwrightAccessLock.tryLock()) {
            return;
        }
        try {
            if (bossHandsOffLoginActive || !playwrightAttached) {
                return;
            }
            if (liepinPage != null && !liepinMonitoringPaused) {
                checkLiepinLoginStatus(liepinPage);
            }
            // 其他平台如需也可启用（保留，但不强制）
            if (bossPage != null && !bossMonitoringPaused) {
                checkLoginStatus(bossPage, "boss");
            }
            if (job51Page != null && !job51MonitoringPaused) {
                check51jobLoginStatus(job51Page);
            }
            if (zhilianPage != null && !zhilianMonitoringPaused) {
                checkZhilianLoginStatus(zhilianPage);
            }
        } catch (Exception e) {
            log.debug("定时登录检测异常: {}", e.getMessage());
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    /**
     * 暂停Boss页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseBossMonitoring() {
        if (isolatedRuntime) {
            platformRuntime("boss").pauseMonitoring();
            return;
        }
        bossMonitoringPaused = true;
        log.debug("Boss登录监控已暂停");
    }

    /**
     * 恢复Boss页面的后台登录监控
     */
    public void resumeBossMonitoring() {
        if (isolatedRuntime) {
            platformRuntime("boss").resumeMonitoring();
            return;
        }
        bossMonitoringPaused = false;
        log.debug("Boss登录监控已恢复");
    }

    /**
     * 关闭Playwright实例
     * 在Spring容器销毁前自动执行
     */
    @PreDestroy
    public void destroy() {
        log.info("开始关闭Playwright管理器...");

        for (BrowserLoginSession session : browserLoginSessions.values()) {
            try {
                session.close();
            } catch (Exception e) {
                log.debug("关闭可见登录会话失败: {}", e.getMessage());
            }
        }
        browserLoginSessions.clear();

        if (isolatedRuntime) {
            for (PlatformBrowserRuntime runtime : platformRuntimes.values()) {
                try {
                    runtime.close();
                } catch (Exception e) {
                    log.warn("关闭独立平台运行时失败: platform={}, error={}",
                            runtime.getPlatform(), e.getMessage());
                }
            }
            platformRuntimes.clear();
            initializationComplete = false;
            isolatedRuntime = false;
            log.info("独立平台运行时关闭完成");
            return;
        }

        try {
            // 关闭所有页面
            if (bossPage != null) {
                bossPage.close();
                log.info("Boss直聘页面已关闭");
            }
            if (liepinPage != null) {
                liepinPage.close();
                log.info("猎聘页面已关闭");
            }
            if (job51Page != null) {
                job51Page.close();
                log.info("51job页面已关闭");
            }
            if (zhilianPage != null) {
                zhilianPage.close();
                log.info("智联招聘页面已关闭");
            }

            // 关闭共享的BrowserContext
            if (context != null) {
                context.close();
                log.info("共享BrowserContext已关闭");
            }

            // 关闭浏览器
            if (browser != null) {
                try {
                    browser.close();
                    log.info("浏览器已关闭 (cdpAttached={})", connectedOverCdp);
                } catch (Exception e) {
                    log.debug("关闭 browser 失败: {}", e.getMessage());
                }
            }
            destroyManagedChromeQuietly();
            connectedOverCdp = false;
            playwrightAttached = false;
            bossHandsOffLoginActive = false;
            initializationComplete = false;
            if (playwright != null) {
                playwright.close();
                log.info("Playwright实例已关闭");
            }

            log.info("Playwright管理器关闭完成！");
        } catch (Exception e) {
            log.error("关闭Playwright管理器时发生错误", e);
        }
    }

    /**
     * 检查Playwright是否已初始化
     */
    public boolean isInitialized() {
        if (isolatedRuntime) {
            if (isLazyPagePolicy()) {
                return initializationComplete;
            }
            return initializationComplete
                    && platformRuntimes.size() == 4
                    && platformRuntimes.values().stream().allMatch(PlatformBrowserRuntime::isInitialized);
        }
        if (bossHandsOffLoginActive && isCdpReachable(CDP_PORT)) {
            return true;
        }
        boolean coreReady = playwright != null
                && playwrightAttached
                && browser != null
                && browser.isConnected()
                && context != null;
        if (!coreReady) {
            return false;
        }
        return isLazyPagePolicy() || isUsableBossPage(bossPage);
    }

    /** 是否处于 Boss hands-off 登录（Playwright 已 detach） */
    public boolean isBossHandsOffLoginActive() {
        return bossHandsOffLoginActive;
    }


    /**
     * 获取CDP端口号
     */
    public int getCdpPort() {
        return CDP_PORT;
    }

    /**
     * Boss hands-off 登录：
     * 1) 断开 Playwright CDP（不杀 Chrome）
     * 2) HTTP /json/new 打开登录页（无 Playwright/WS 控制）
     * 3) 轮询登录结果后重新 connectOverCDP 并恢复四平台 Page
     */
    private void startBossHandsOffLogin() {
        if (!connectedOverCdp) {
            log.warn("hands-off 登录仅支持系统 Chrome CDP 模式");
            return;
        }
        if (!bossHandsOffLoginGate.compareAndSet(false, true)) {
            log.info("Boss hands-off 登录已在进行中，跳过重复触发");
            return;
        }
        try {
            synchronized (cdpLifecycleLock) {
                if (playwrightAttached && browser != null) {
                    detachPlaywrightKeepChromeForBossLogin();
                } else {
                    bossHandsOffLoginActive = true;
                    pauseAllPlatformMonitoring();
                    log.info("Playwright 已处于 detach 状态，直接打开登录页");
                }
            }

            String targetId = openUrlViaCdpJsonNew(BOSS_LOGIN_URL);
            if (targetId == null) {
                log.error("CDP /json/new 打开 Boss 登录页失败，尝试重连 Playwright");
                reconnectPlaywrightAfterHandsOff("json-new-failed");
                return;
            }
            log.info("✓ hands-off 已打开 Boss 登录页 targetId={} url={}", targetId, BOSS_LOGIN_URL);
            pollBossHandsOffLoginAndReconnect(targetId);
        } catch (Exception e) {
            log.error("Boss hands-off 登录流程异常: {}", e.getMessage(), e);
            try {
                reconnectPlaywrightAfterHandsOff("hands-off-error");
            } catch (Exception ex) {
                log.error("hands-off 异常后重连失败: {}", ex.getMessage(), ex);
            }
        } finally {
            bossHandsOffLoginActive = false;
            bossHandsOffLoginGate.set(false);
        }
    }

    private void pauseAllPlatformMonitoring() {
        bossMonitoringPaused = true;
        liepinMonitoringPaused = true;
        job51MonitoringPaused = true;
        zhilianMonitoringPaused = true;
    }

    private void resumeAllPlatformMonitoring() {
        bossMonitoringPaused = false;
        liepinMonitoringPaused = false;
        job51MonitoringPaused = false;
        zhilianMonitoringPaused = false;
    }

    /**
     * 断开 Playwright 对浏览器的控制，保留系统 Chrome + remote-debugging。
     * 注意：仅在 connectOverCDP 路径安全；launch 路径的 browser.close() 会杀浏览器。
     */
    private void detachPlaywrightKeepChromeForBossLogin() {
        log.info("开始 detach Playwright（保留 Chrome CDP :{}）以进行 Boss hands-off 登录...", CDP_PORT);
        pauseAllPlatformMonitoring();
        bossHandsOffLoginActive = true;

        // 先摘引用，避免定时任务/业务在 close 过程中碰死对象
        bossPage = null;
        liepinPage = null;
        job51Page = null;
        zhilianPage = null;
        context = null;

        if (browser != null) {
            try {
                browser.close();
                log.info("✓ Playwright browser.close() 完成（CDP 断开，Chrome 应仍存活）");
            } catch (Exception e) {
                log.warn("Playwright browser.close() 异常（可忽略）: {}", e.getMessage());
            }
            browser = null;
        }
        playwrightAttached = false;

        if (!isCdpReachable(CDP_PORT)) {
            throw new IllegalStateException("detach 后 CDP :" + CDP_PORT + " 不可达，Chrome 可能已退出");
        }
        log.info("✓ Chrome CDP 仍可达，hands-off 登录环境就绪");
    }

    /**
     * 通过 Chrome DevTools HTTP 端点打开新标签（不建立 Playwright/CDP WebSocket）。
     */
    private String openUrlViaCdpJsonNew(String url) {
        String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
        String endpoint = "http://127.0.0.1:" + CDP_PORT + "/json/new?" + encoded;
        // PUT 优先，失败再 GET
        String body = httpRequestRaw(endpoint, "PUT", 8000);
        if (body == null || body.isBlank()) {
            body = httpRequestRaw(endpoint, "GET", 8000);
        }
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper().readTree(body);
            if (node.has("id")) {
                return node.get("id").asText();
            }
            log.warn("/json/new 响应无 id: {}", body.length() > 300 ? body.substring(0, 300) : body);
            return "unknown";
        } catch (Exception e) {
            log.warn("解析 /json/new 响应失败: {} body={}", e.getMessage(),
                    body.length() > 200 ? body.substring(0, 200) : body);
            return "unknown";
        }
    }

    private String httpRequestRaw(String url, String method, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(0);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                return null;
            }
            try (InputStream stream = in) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("HTTP {} {} 失败: {}", method, url, e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private List<CdpPageInfo> listCdpPages() {
        String body = httpRequestRaw("http://127.0.0.1:" + CDP_PORT + "/json/list", "GET", 3000);
        List<CdpPageInfo> pages = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return pages;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr = new ObjectMapper().readTree(body);
            if (!arr.isArray()) {
                return pages;
            }
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                String type = n.path("type").asText("");
                if (!"page".equals(type)) {
                    continue;
                }
                pages.add(new CdpPageInfo(
                        n.path("id").asText(""),
                        n.path("url").asText(""),
                        n.path("title").asText("")
                ));
            }
        } catch (Exception e) {
            log.debug("解析 /json/list 失败: {}", e.getMessage());
        }
        return pages;
    }

    private void pollBossHandsOffLoginAndReconnect(String loginTargetId) {
        long start = System.currentTimeMillis();
        long lastStayLogMs = 0L;
        boolean sawLoginPage = false;
        boolean leftLoginPage = false;
        int stableLoginTicks = 0;

        log.info("开始轮询 Boss hands-off 登录状态（timeout={}ms）...", BOSS_HANDS_OFF_TIMEOUT_MS);
        while (System.currentTimeMillis() - start < BOSS_HANDS_OFF_TIMEOUT_MS) {
            if (!isCdpReachable(CDP_PORT)) {
                log.error("hands-off 轮询中 CDP 不可达，中止");
                break;
            }
            List<CdpPageInfo> pages = listCdpPages();
            boolean onLogin = false;
            boolean zhipinNonLogin = false;
            for (CdpPageInfo p : pages) {
                String u = p.url() == null ? "" : p.url();
                if (!u.contains("zhipin.com")) {
                    continue;
                }
                if (u.contains("/web/user")) {
                    onLogin = true;
                } else if (!u.startsWith("chrome://") && !u.equals("about:blank")) {
                    zhipinNonLogin = true;
                }
            }

            if (onLogin) {
                sawLoginPage = true;
                stableLoginTicks++;
                long now = System.currentTimeMillis();
                if (now - lastStayLogMs >= 10_000L) {
                    log.info("Boss 登录页 hands-off 停留中... elapsed={}s stableTicks={} tabs={}",
                            (now - start) / 1000, stableLoginTicks, pages.size());
                    lastStayLogMs = now;
                }
                // 连续稳定停留视为 REV-001 方向通过（无 Playwright 控制）
                if (stableLoginTicks == 8) {
                    log.info("✓ Boss 登录页已稳定停留（≈16s+，无 Playwright 附着）");
                }
            } else if (sawLoginPage) {
                leftLoginPage = true;
                log.info("Boss 登录页已离开 /web/user（可能已扫码登录），准备重连 Playwright。zhipinNonLogin={}", zhipinNonLogin);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                break;
            } else {
                // 尚未看到登录页：给页面加载一点时间
                if (System.currentTimeMillis() - start > 15_000L) {
                    log.warn("15s 内未在 /json/list 看到 Boss 登录页，仍继续等待用户操作/重开");
                    // 尝试再开一次
                    openUrlViaCdpJsonNew(BOSS_LOGIN_URL);
                    start = System.currentTimeMillis(); // 重置等待窗口
                    sawLoginPage = false;
                    stableLoginTicks = 0;
                }
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!leftLoginPage && sawLoginPage) {
            log.info("Boss hands-off 登录等待结束（超时或人工未完成），重连 Playwright 以恢复自动化");
        }
        reconnectPlaywrightAfterHandsOff(leftLoginPage ? "left-login-page" : "timeout-or-idle");
    }

    private void reconnectPlaywrightAfterHandsOff(String reason) {
        synchronized (cdpLifecycleLock) {
            log.info("hands-off 后重连 Playwright，reason={} ...", reason);
            if (!isCdpReachable(CDP_PORT)) {
                log.error("无法重连：CDP :{} 不可达", CDP_PORT);
                bossHandsOffLoginActive = false;
                return;
            }
            if (playwright == null) {
                playwright = Playwright.create();
            }
            browser = playwright.chromium().connectOverCDP("http://127.0.0.1:" + CDP_PORT);
            connectedOverCdp = true;
            playwrightAttached = true;
            if (browser.contexts() != null && !browser.contexts().isEmpty()) {
                context = browser.contexts().get(0);
            } else {
                context = browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(null)
                        .setLocale("zh-CN")
                        .setTimezoneId("Asia/Shanghai"));
            }
            try {
                context.setExtraHTTPHeaders(Map.of("Accept-Language", "zh-CN,zh;q=0.9"));
            } catch (Exception ignored) {}
            configureContext(context);

            injectBossInitScript(context);
            rebindPlatformPagesFromContext();
            bossHandsOffLoginActive = false;
            resumeAllPlatformMonitoring();

            // 重新判定登录态；Cookie 持久化由登录卡片中的显式获取动作负责。
            try {
                boolean loggedIn = checkIfLoggedIn();
                loginStatus.put("boss", loggedIn);
                if (loggedIn) {
                    log.info("✓ hands-off 后检测到 Boss 已登录");
                    LoginStatusChange change = new LoginStatusChange("boss", true, System.currentTimeMillis());
                    loginStatusListeners.forEach(listener -> {
                        try {
                            listener.accept(change);
                        } catch (Exception e) {
                            log.error("通知登录状态监听器失败", e);
                        }
                    });
                } else {
                    log.info("hands-off 后 Boss 仍未登录（用户可能尚未扫码）。浏览器已恢复，可稍后再次触发退出/登录引导");
                }
            } catch (Exception e) {
                log.warn("hands-off 重连后检查登录态失败: {}", e.getMessage());
            }
            log.info("✓ Playwright 已重连并恢复平台 Page 引用 (version={})", safeBrowserVersion());
        }
    }

    private void rebindPlatformPagesFromContext() {
        List<Page> existing = new ArrayList<>();
        try {
            if (context.pages() != null) {
                existing.addAll(context.pages());
            }
        } catch (Exception e) {
            log.debug("读取 context.pages 失败: {}", e.getMessage());
        }
        Set<Page> claimed = new HashSet<>();
        bossPage = findOrCreatePage(existing, claimed, "zhipin.com");
        liepinPage = findOrCreatePage(existing, claimed, "liepin.com");
        job51Page = findOrCreatePage(existing, claimed, "51job.com");
        zhilianPage = findOrCreatePage(existing, claimed, "zhaopin.com");

        configurePage(bossPage);
        configurePage(liepinPage);
        configurePage(job51Page);
        configurePage(zhilianPage);

        // 重新挂监控（重连后的 Page 是新包装对象）
        setupLoginMonitoring(bossPage);
        setupLiepinLoginMonitoring(liepinPage);
        setup51jobLoginMonitoring(job51Page);
        setupZhilianLoginMonitoring(zhilianPage);
        log.info("已重绑定平台 Page: boss={}, liepin={}, 51job={}, zhilian={}",
                safePageUrl(bossPage), safePageUrl(liepinPage), safePageUrl(job51Page), safePageUrl(zhilianPage));
    }

    private Page findOrCreatePage(List<Page> existing, Set<Page> claimed, String domainHint) {
        for (Page p : existing) {
            if (p == null || claimed.contains(p)) {
                continue;
            }
            try {
                String u = p.url();
                if (u != null && u.contains(domainHint)) {
                    claimed.add(p);
                    return p;
                }
            } catch (Exception ignored) {
            }
        }
        Page created = context.newPage();
        claimed.add(created);
        return created;
    }

    private String safePageUrl(Page page) {
        try {
            return page != null ? page.url() : "null";
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    private record CdpPageInfo(String id, String url, String title) {}


    /**
     * 注册登录状态监听器
     *
     * @param listener 监听器
     */
    public void addLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.add(listener);
    }

    /**
     * 移除登录状态监听器
     *
     * @param listener 监听器
     */
    public void removeLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.remove(listener);
    }

    /**
     * 获取平台登录状态
     *
     * @param platform 平台名称
     * @return 是否已登录
     */
    public boolean isLoggedIn(String platform) {
        if (isolatedRuntime) {
            PlatformBrowserRuntime runtime = platformRuntimes.get(platform);
            if (runtime == null) {
                if (!initializationComplete || !isLazyPagePolicy()) {
                    return loginStatus.getOrDefault(platform, false);
                }
                try {
                    // 懒加载只延后运行时创建，不跳过已保存 Cookie 的实际登录校验。
                    runtime = platformRuntime(platform);
                } catch (RuntimeException e) {
                    log.warn("懒加载平台运行时创建失败，暂按未登录处理: platform={}, error={}",
                            platform, e.getMessage());
                    return false;
                }
            }
            try {
                // 状态接口不能等待平台投递持有的 Playwright 锁；运行时已提供非阻塞刷新，锁忙时直接返回缓存态。
                boolean loggedIn = runtime.tryRefreshLoginStatus();
                loginStatus.put(platform, loggedIn);
                return loggedIn;
            } catch (RuntimeException e) {
                loginStatus.put(platform, false);
                log.warn("平台登录状态检测失败，暂按未登录处理: platform={}, error={}",
                        platform, e.getMessage());
                return false;
            }
        }
        BrowserLoginSession loginSession = browserLoginSessions.get(platform);
        if (loginSession != null && loginSession.isOpen()) {
            loginSession.pollStatus();
            Map<String, Object> sessionStatus = loginSession.status();
            boolean loggedIn = Boolean.TRUE.equals(sessionStatus.get("loggedIn"));
            loginStatus.put(platform, loggedIn);
            return loggedIn;
        }
        return loginStatus.getOrDefault(platform, false);
    }

    /** Returns the last observed login state without creating or touching a browser runtime. */
    public boolean getCachedLoginStatus(String platform) {
        if (isolatedRuntime) {
            PlatformBrowserRuntime runtime = platformRuntimes.get(platform);
            return runtime != null && runtime.isLoginKnown()
                    ? runtime.isLoggedIn()
                    : loginStatus.getOrDefault(platform, false);
        }
        return loginStatus.getOrDefault(platform, false);
    }

    /**
     * 读取当前上下文的 Cookie 快照，供无页面 HTTP 请求使用。
     * 快照复制后与 Playwright 生命周期解耦，调用方无需持有页面对象。
     */
    public BrowserSessionSnapshot getSessionSnapshot() {
        if (isolatedRuntime) {
            List<com.microsoft.playwright.options.Cookie> cookies = new ArrayList<>();
            for (PlatformBrowserRuntime runtime : platformRuntimes.values()) {
                cookies.addAll(runtime.getSessionSnapshot().cookies());
            }
            return new BrowserSessionSnapshot(cookies);
        }
        playwrightAccessLock.lock();
        try {
            if (context == null || !playwrightAttached) {
                return new BrowserSessionSnapshot(List.of());
            }
            return new BrowserSessionSnapshot(context.cookies());
        } catch (Exception e) {
            log.debug("读取浏览器 Cookie 快照失败: {}", e.getMessage());
            return new BrowserSessionSnapshot(List.of());
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    /** 读取单个平台的 Cookie 快照，避免独立运行时之间互相等待。 */
    public BrowserSessionSnapshot getSessionSnapshot(String platform) {
        ensureSupportedPlatform(platform);
        if (isolatedRuntime) {
            return platformRuntime(platform).getSessionSnapshot();
        }
        playwrightAccessLock.lock();
        try {
            if (context == null || !playwrightAttached) {
                return new BrowserSessionSnapshot(List.of());
            }
            return new BrowserSessionSnapshot(filterCookiesByDomain(context.cookies(), platformDomain(platform)));
        } catch (Exception e) {
            log.debug("读取平台 Cookie 快照失败: platform={}, error={}", platform, e.getMessage());
            return new BrowserSessionSnapshot(List.of());
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    public String getCookieHeader(String domainSuffix) {
        return getSessionSnapshot().cookieHeader(domainSuffix);
    }

    /**
     * 手动设置平台登录状态（会触发SSE通知）
     *
     * @param platform   平台名称
     * @param isLoggedIn 是否已登录
     */
    public void setLoginStatus(String platform, boolean isLoggedIn) {
        if (isolatedRuntime) {
            PlatformBrowserRuntime runtime = platformRuntimes.get(platform);
            if (runtime != null) {
                runtime.markLoggedIn(isLoggedIn);
            }
        }
        Boolean previousStatus = loginStatus.get(platform);

        // 只有状态真正发生变化时才更新和通知
        if (previousStatus == null || previousStatus != isLoggedIn) {
            loginStatus.put(platform, isLoggedIn);

            // 通知所有监听器（触发SSE推送）
            LoginStatusChange change = new LoginStatusChange(platform, isLoggedIn, System.currentTimeMillis());
            loginStatusListeners.forEach(listener -> {
                try {
                    listener.accept(change);
                } catch (Exception e) {
                    log.error("通知登录状态监听器失败: platform={}, isLoggedIn={}", platform, isLoggedIn, e);
                }
            });
        }

        // Boss 使用 Cookie 导入登录：未登录时不再自动 hands-off / 导航登录页
        if ("boss".equals(platform) && !isLoggedIn) {
            log.info("Boss 标记未登录；请通过 /api/cookie/import 导入 Cookie 完成登录");
        }
    }

    /**
     * 导入平台 Cookie：落库 + 注入当前浏览器上下文，并刷新对应页面检测登录态。
     * 支持 Playwright/EditThisCookie JSON 数组，或浏览器 Cookie 请求头格式（name=value; a=b）。
     */
    public Map<String, Object> importCookies(String platform, String cookieRaw, String remark) {
        if (isolatedRuntime) {
            return platformRuntime(platform).importCookies(cookieRaw, remark);
        }
        playwrightAccessLock.lock();
        try {
            return importCookiesInternal(platform, cookieRaw, remark);
        } finally {
            playwrightAccessLock.unlock();
        }
    }

    private Map<String, Object> importCookiesInternal(String platform, String cookieRaw, String remark) {
        Map<String, Object> result = new HashMap<>();
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        if (cookieRaw == null || cookieRaw.isBlank()) {
            throw new IllegalArgumentException("cookie_value 不能为空");
        }
        if (bossHandsOffLoginActive || !playwrightAttached || context == null) {
            throw new IllegalStateException("浏览器未就绪或 Playwright 未附着，请稍后再导入 Cookie");
        }

        String domain = domainForPlatform(platform);
        List<Cookie> cookies = parseCookiesFlexible(cookieRaw, domain);
        cookies = filterCookiesByDomain(cookies, domain);
        if (cookies.isEmpty()) {
            throw new IllegalArgumentException("未能解析出有效 Cookie，请粘贴 JSON 数组或 name=value; 格式");
        }

        try {
            ObjectMapper om = new ObjectMapper();
            String normalizedJson = om.writeValueAsString(cookiesToMaps(cookies));
            boolean saved = cookieService.saveOrUpdateCookie(platform, normalizedJson,
                    remark == null || remark.isBlank() ? "manual import" : remark);
            if (!saved) {
                throw new IllegalStateException("Cookie 写入数据库失败");
            }
            context.addCookies(cookies);
            log.info("已导入并注入 {} Cookie，共 {} 条", platform, cookies.size());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("导入 Cookie 失败: " + e.getMessage(), e);
        }

        boolean loggedIn = false;
        try {
            loggedIn = refreshPlatformAfterCookieImport(platform);
        } catch (Exception e) {
            log.warn("导入 Cookie 后刷新/检测登录态失败: {}", e.getMessage());
        }

        result.put("platform", platform);
        result.put("count", cookies.size());
        result.put("loggedIn", loggedIn);
        return result;
    }

    private String domainForPlatform(String platform) {
        return switch (platform) {
            case "boss" -> BOSS_DOMAIN;
            case "liepin" -> LIEPIN_DOMAIN;
            case "51job" -> JOB51_DOMAIN;
            case "zhilian" -> ZHILIAN_DOMAIN;
            default -> throw new IllegalArgumentException("不支持的平台: " + platform);
        };
    }

    private boolean refreshPlatformAfterCookieImport(String platform) {
        return switch (platform) {
            case "boss" -> {
                if (bossPage != null) {
                    try {
                        bossPage.navigate(BOSS_URL, new Page.NavigateOptions()
                                .setTimeout(60000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                        try { bossPage.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}
                    } catch (Exception e) {
                        log.warn("Boss 导入 Cookie 后导航失败: {}", e.getMessage());
                    }
                }
                boolean ok = checkIfLoggedIn();
                Boolean previous = loginStatus.get("boss");
                loginStatus.put("boss", ok);
                if (previous == null || previous != ok) {
                    LoginStatusChange change = new LoginStatusChange("boss", ok, System.currentTimeMillis());
                    loginStatusListeners.forEach(listener -> {
                        try { listener.accept(change); } catch (Exception e) {
                            log.error("通知登录状态监听器失败", e);
                        }
                    });
                }
                if (ok) {
                    saveBossCookiesToDatabase("cookie import login success");
                }
                yield ok;
            }
            case "liepin" -> {
                if (liepinPage != null) {
                    try {
                        liepinPage.navigate(LIEPIN_URL, new Page.NavigateOptions()
                                .setTimeout(60000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    } catch (Exception e) {
                        log.warn("猎聘导入 Cookie 后导航失败: {}", e.getMessage());
                    }
                }
                boolean ok = checkIfLiepinLoggedIn();
                setLoginStatus("liepin", ok);
                if (ok) saveLiepinCookiesToDatabase("cookie import login success");
                yield ok;
            }
            case "51job" -> {
                if (job51Page != null) {
                    try {
                        job51Page.navigate(JOB51_URL, new Page.NavigateOptions()
                                .setTimeout(60000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    } catch (Exception e) {
                        log.warn("51job 导入 Cookie 后导航失败: {}", e.getMessage());
                    }
                }
                boolean ok = checkIf51jobLoggedIn();
                setLoginStatus("51job", ok);
                if (ok) save51jobCookiesToDatabase("cookie import login success");
                yield ok;
            }
            case "zhilian" -> {
                if (zhilianPage != null) {
                    try {
                        zhilianPage.navigate(ZHILIAN_URL, new Page.NavigateOptions()
                                .setTimeout(60000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    } catch (Exception e) {
                        log.warn("智联导入 Cookie 后导航失败: {}", e.getMessage());
                    }
                }
                boolean ok = checkIfZhilianLoggedIn();
                setLoginStatus("zhilian", ok);
                if (ok) saveZhilianCookiesToDatabase("cookie import login success");
                yield ok;
            }
            default -> false;
        };
    }

    private List<Map<String, Object>> cookiesToMaps(List<Cookie> cookies) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Cookie c : cookies) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name);
            m.put("value", c.value);
            if (c.domain != null) m.put("domain", c.domain);
            if (c.path != null) m.put("path", c.path);
            // expires 为 double 原始类型：-1 表示会话 cookie
            m.put("expires", c.expires);
            m.put("httpOnly", c.httpOnly);
            m.put("secure", c.secure);
            if (c.sameSite != null) m.put("sameSite", c.sameSite.name());
            list.add(m);
        }
        return list;
    }

    /**
     * 灵活解析 Cookie：JSON 数组 或 header 字符串。缺 domain 时填默认 domain。
     */
    private List<Cookie> parseCookiesFlexible(String raw, String defaultDomain) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return new ArrayList<>();

        if (s.startsWith("[")) {
            List<Cookie> fromJson = parseCookiesFromString(s);
            for (Cookie c : fromJson) {
                if (c.domain == null || c.domain.isBlank()) {
                    c.domain = defaultDomain.startsWith(".") ? defaultDomain : "." + defaultDomain;
                }
                if (c.path == null || c.path.isBlank()) {
                    c.path = "/";
                }
            }
            return fromJson;
        }

        List<Cookie> cookies = new ArrayList<>();
        String domain = defaultDomain.startsWith(".") ? defaultDomain : "." + defaultDomain;
        for (String part : s.split(";")) {
            String p = part.trim();
            if (p.isEmpty() || !p.contains("=")) continue;
            int eq = p.indexOf('=');
            String name = p.substring(0, eq).trim();
            String value = p.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (Set.of("path", "domain", "expires", "max-age", "secure", "httponly", "samesite").contains(lower)) {
                continue;
            }
            Cookie cookie = new Cookie(name, value);
            cookie.domain = domain;
            cookie.path = "/";
            cookies.add(cookie);
        }
        return cookies;
    }

    /**
     * 从JSON字符串解析Cookie列表
     *
     * @param cookieJson Cookie的JSON字符串
     * @return Cookie列表
     */
    private List<Cookie> parseCookiesFromString(String cookieJson) {
        List<Cookie> cookies = new ArrayList<>();

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode jsonArray = objectMapper.readTree(cookieJson);

            for (com.fasterxml.jackson.databind.JsonNode node : jsonArray) {
                // 创建Cookie对象（name和value是必需的）
                Cookie cookie = new Cookie(
                        node.get("name").asText(),
                        node.get("value").asText()
                );

                // 设置可选字段
                if (node.has("domain") && !node.get("domain").isNull()) {
                    cookie.domain = node.get("domain").asText();
                }
                if (node.has("path") && !node.get("path").isNull()) {
                    cookie.path = node.get("path").asText();
                }
                if (node.has("expires") && !node.get("expires").isNull()) {
                    cookie.expires = node.get("expires").asDouble();
                }
                if (node.has("httpOnly") && !node.get("httpOnly").isNull()) {
                    cookie.httpOnly = node.get("httpOnly").asBoolean();
                }
                if (node.has("secure") && !node.get("secure").isNull()) {
                    cookie.secure = node.get("secure").asBoolean();
                }
                if (node.has("sameSite") && !node.get("sameSite").isNull()) {
                    String sameSite = node.get("sameSite").asText();
                    if (sameSite != null && !sameSite.isEmpty()) {
                        cookie.sameSite = mapSameSite(sameSite);
                    }
                }
                // EditThisCookie 等导出使用 expirationDate（秒）
                if ((!node.has("expires") || node.get("expires").isNull())
                        && node.has("expirationDate") && !node.get("expirationDate").isNull()) {
                    cookie.expires = node.get("expirationDate").asDouble();
                }

                cookies.add(cookie);
            }

            log.debug("成功解析Cookie，共 {} 条", cookies.size());
        } catch (Exception e) {
            log.error("解析Cookie JSON失败: {}", e.getMessage(), e);
        }

        return cookies;
    }


    private com.microsoft.playwright.options.SameSiteAttribute mapSameSite(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (s) {
            case "strict" -> com.microsoft.playwright.options.SameSiteAttribute.STRICT;
            case "lax" -> com.microsoft.playwright.options.SameSiteAttribute.LAX;
            case "none", "no_restriction", "unspecified" -> com.microsoft.playwright.options.SameSiteAttribute.NONE;
            default -> {
                try {
                    yield com.microsoft.playwright.options.SameSiteAttribute.valueOf(s.toUpperCase(Locale.ROOT));
                } catch (Exception e) {
                    log.debug("未知 sameSite={}，忽略该字段", raw);
                    yield null;
                }
            }
        };
    }

    private List<Cookie> filterCookiesByDomain(List<Cookie> cookies, String domainSuffix) {
        if (cookies == null || cookies.isEmpty()) {
            return new ArrayList<>();
        }

        String suffix = domainSuffix == null ? "" : domainSuffix.toLowerCase(Locale.ROOT);
        List<Cookie> filtered = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (cookie == null || cookie.domain == null || cookie.domain.isBlank()) {
                continue;
            }
            String domain = cookie.domain.toLowerCase(Locale.ROOT);
            if (domain.equals(suffix) || domain.endsWith("." + suffix)) {
                filtered.add(cookie);
            }
        }

        return filtered;
    }

    /**
     * LoginStatusChange - 登录状态变化DTO
     */
    public record LoginStatusChange(String platform, boolean isLoggedIn, long timestamp) {
    }
}
