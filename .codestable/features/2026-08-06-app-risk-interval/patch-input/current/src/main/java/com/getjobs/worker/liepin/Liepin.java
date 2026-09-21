package com.getjobs.worker.liepin;

import com.getjobs.worker.utils.PlaywrightUtil;
import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.LiepinService;
import com.getjobs.application.service.AiService;
import com.getjobs.application.entity.LiepinEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

// 移除保存页面源码相关的导入
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.getjobs.worker.liepin.Locators.*;


/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Slf4j
@Component
@Scope("prototype")
public class Liepin {
    private static final int MAX_PAGE_RECOVERY_ATTEMPTS = 1;
    private static final String SEARCH_API_FRAGMENT = "com.liepin.searchfront4c.pc-search-job";
    private static final List<String> RELEVANT_DOMAIN_TERMS = List.of(
            "放疗", "医疗器械", "医疗设备", "医学影像", "核医学", "直线加速器",
            "质子治疗", "重离子", "医用影像", "临床应用", "影像设备"
    );
    private static final List<String> EXCLUDED_TITLE_TERMS = List.of(
            "销售", "保险", "建筑", "热能", "锅炉", "供热", "暖通",
            "房地产", "房产", "工程造价", "施工", "采购", "行政", "人事"
    );
    private static final List<String> GENERIC_MANUFACTURING_TERMS = List.of(
            "机械制造", "汽车制造", "电子制造", "生产线", "工厂", "车间"
    );

    static {
        // 在类加载时就设置日志文件名，确保Logger初始化时能获取到正确的属性
        System.setProperty("log.name", "liepin");
    }

    private int maxPage = 50;
    private final List<String> resultList = new ArrayList<>();
    private final List<LiepinEntity> lastApiEntities = new ArrayList<>();
    private LiepinRateGuard rateGuard;
    @Setter
    private LiepinConfig config;
    @Getter
    private Date startDate;
    @Setter
    private Page page;
    @Autowired
    private LiepinService liepinService;
    @Autowired
    private AiService aiService;

    public interface ProgressCallback {
        void onProgress(String message, Integer current, Integer total);
    }

    public enum GreetingAction {
        SEND_AI,
        SEND_PRESET,
        SKIP
    }

    /** 单页卡片扫描结果：只有 COMPLETED 才推进断点页码。 */
    enum PageScanResult {
        COMPLETED,
        STOPPED,
        LIMIT_REACHED
    }

    public record GreetingRequest(
            Long jobId,
            String companyName,
            String jobTitle,
            String salary,
            String jd,
            String message,
            boolean aiAvailable
    ) {
    }

    record AiGreetingResult(String message, boolean rejected) {
    }

    private record PreparedJob(
            Long jobId,
            LiepinEntity entity,
            Locator button,
            String companyName,
            String jobName,
            String salary,
            String recruiterName,
            String jd
    ) {
    }

    private static final class AiRunStats {
        int candidateCount;
        int cacheHits;
        int screenCalls;
        int messageCalls;
        int passed;
        int review;
        int skipped;
        int invalid;
        long totalLatencyMs;
        int latencySamples;

        void reset() {
            candidateCount = 0;
            cacheHits = 0;
            screenCalls = 0;
            messageCalls = 0;
            passed = 0;
            review = 0;
            skipped = 0;
            invalid = 0;
            totalLatencyMs = 0;
            latencySamples = 0;
        }

        Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("candidateCount", candidateCount);
            result.put("cacheHits", cacheHits);
            result.put("screenCalls", screenCalls);
            result.put("messageCalls", messageCalls);
            result.put("passed", passed);
            result.put("review", review);
            result.put("skipped", skipped);
            result.put("invalid", invalid);
            result.put("avgLatencyMs", latencySamples == 0 ? 0 : totalLatencyMs / latencySamples);
            return result;
        }
    }

    private final AiRunStats aiStats = new AiRunStats();

    @FunctionalInterface
    public interface GreetingConfirmation {
        GreetingAction confirm(GreetingRequest request);
    }

    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Supplier<Boolean> shouldStopCallback;
    @Setter
    private GreetingConfirmation greetingConfirmation;
    @Setter
    private Supplier<Page> pageRecovery;

    /** 页面生命周期中断，供服务层转换成稳定的用户状态消息。 */
    public static final class PageLifecycleException extends RuntimeException {
        private final boolean sideEffectStarted;

        public PageLifecycleException(boolean sideEffectStarted, Throwable cause) {
            super("猎聘页面生命周期中断", cause);
            this.sideEffectStarted = sideEffectStarted;
        }

        public boolean sideEffectStarted() {
            return sideEffectStarted;
        }
    }

    public void prepare() {
        this.startDate = new Date();
        this.resultList.clear();
        lastApiEntities.clear();
        aiStats.reset();
        this.rateGuard = LiepinRateGuard.production(
                this::shouldStop,
                this::info,
                config
        );
    }

    public int execute() {
        if (page == null) {
            throw new IllegalStateException("Liepin.page 未设置");
        }
        if (config == null) {
            throw new IllegalStateException("Liepin.config 未设置");
        }
        validateSalaryConfig();
        validateDeliveryConfig();

        // 在开始执行前确保已注册接口监听
        prepare();

        List<String> keywords = config.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            log.warn("未配置关键词，执行结束");
            return 0;
        }

        for (String keyword : keywords) {
            if (shouldStop()) {
                info("收到停止指令，提前结束关键词循环");
                break;
            }
            int recoveryAttempts = 0;
            while (true) {
                try {
                    submit(keyword);
                    break;
                } catch (RateLimitSignalException signal) {
                    info(signal.getMessage());
                    return resultList.size();
                } catch (PageLifecycleException lifecycle) {
                    if (lifecycle.sideEffectStarted()
                            || recoveryAttempts >= MAX_PAGE_RECOVERY_ATTEMPTS
                            || pageRecovery == null) {
                        throw lifecycle;
                    }
                    recoveryAttempts++;
                    try {
                        Page recovered = pageRecovery.get();
                        if (recovered == null || recovered.isClosed()) {
                            throw new IllegalStateException("恢复后的猎聘页面仍不可用");
                        }
                        page = recovered;
                        info("猎聘页面已恢复，正在重试当前关键词");
                    } catch (PageLifecycleException recoveryFailure) {
                        throw recoveryFailure;
                    } catch (Exception recoveryFailure) {
                        throw new PageLifecycleException(false, recoveryFailure);
                    }
                } catch (RuntimeException failure) {
                    if (isTargetClosedFailure(failure)) {
                        throw pageClosed(failure, false);
                    }
                    throw failure;
                }
            }
            if (resultList.size() >= config.effectiveMaxPerRun()) {
                info(String.format("已达到本次成功聊天上限%d个，结束全部关键词", config.effectiveMaxPerRun()));
                break;
            }
        }
        return resultList.size();
    }

    public Map<String, Object> getAiSummary() {
        return aiStats.snapshot();
    }

    public static boolean isTargetClosedFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("targetclosederror")
                        || normalized.contains("target page, context or browser has been closed")
                        || normalized.contains("page has been closed")
                        || normalized.contains("context has been closed")
                        || normalized.contains("browser has been closed")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static PageLifecycleException pageClosed(Throwable error, boolean sideEffectStarted) {
        if (error instanceof PageLifecycleException lifecycle) {
            return lifecycle;
        }
        return new PageLifecycleException(sideEffectStarted, error);
    }

    // ========== 解析接口JSON并保存到数据库 ==========
    private void parseAndPersistLiepinData(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            // 兼容两种结构：data.data.jobCardList 或 data.jobCardList
            JsonNode cardList = root.path("data").path("data").path("jobCardList");
            if (!cardList.isArray()) {
                cardList = root.path("data").path("jobCardList");
            }
            if (!cardList.isArray()) {
                return;
            }
            lastApiEntities.clear();
            for (JsonNode item : cardList) {
                JsonNode job = item.path("job");
                JsonNode comp = item.path("comp");
                JsonNode recruiter = item.path("recruiter");

                Long jobId = readLong(job.path("jobId"));
                if (jobId == null) {
                    continue;
                }

                LiepinEntity entity = new LiepinEntity();
                entity.setJobId(jobId);
                entity.setJobTitle(readText(job.path("title")));
                entity.setJobLink(readText(job.path("link")));
                entity.setJobDescription(readFirstText(job,
                        "jobDesc", "description", "jobDescription", "postDescription", "detail", "requirement"));
                entity.setJobSalaryText(readText(job.path("salary")));
                entity.setJobArea(readText(job.path("dq")));
                entity.setJobEduReq(readText(job.path("requireEduLevel")));
                entity.setJobExpReq(readText(job.path("requireWorkYears")));
                entity.setJobPublishTime(readText(job.path("refreshTime")));

                entity.setCompId(readLong(comp.path("compId")));
                entity.setCompName(readText(comp.path("compName")));
                entity.setCompIndustry(readText(comp.path("compIndustry")));
                entity.setCompScale(readText(comp.path("compScale")));

                entity.setHrId(readText(recruiter.path("recruiterId")));
                entity.setHrName(readText(recruiter.path("recruiterName")));
                entity.setHrTitle(readText(recruiter.path("recruiterTitle")));
                entity.setHrImId(readText(recruiter.path("imId")));

                // 缓存到内存供页面投递显示使用（避免从页面读取文本）
                lastApiEntities.add(entity);
            }
            // 批量持久化：仅不存在时插入，默认 delivered=0
            try {
                liepinService.insertSnapshotsIfNotExistsBatch(lastApiEntities);
                for (LiepinEntity entity : lastApiEntities) {
                    liepinService.updateJobDescription(entity.getJobId(), entity.getJobDescription());
                }
            } catch (Exception e) {
                log.warn("批量保存猎聘岗位数据失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("解析猎聘JSON失败: {}", e.getMessage());
        }
    }

    private String readText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String v = node.asText();
        return (v == null || v.isEmpty()) ? null : v;
    }

    private String readFirstText(JsonNode parent, String... names) {
        if (parent == null || names == null) return null;
        for (String name : names) {
            String value = readText(parent.path(name));
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private Long readLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try {
            if (node.isNumber()) {
                long v = node.asLong();
                return v == 0 ? null : v;
            }
            if (node.isTextual()) {
                String t = node.asText();
                if (t == null || t.isEmpty()) return null;
                long v = Long.parseLong(t.trim());
                return v == 0 ? null : v;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String safeText(String s) {
        if (s == null) return null;
        return s.replaceAll("\n", " ").replaceAll("【 ", "[").replaceAll(" 】", "]");
    }

    private boolean shouldStop() {
        return shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get());
    }

    private void validateSalaryConfig() {
        String salaryCode = config.getSalary();
        if (salaryCode != null && !salaryCode.isBlank()
                && LiepinService.parseSalaryRange(salaryCode) == null) {
            throw new IllegalArgumentException("猎聘薪资范围配置无效，请使用如 6$10 的范围码");
        }
    }

    private void validateDeliveryConfig() {
        int maxPerRun = config.effectiveMaxPerRun();
        int minDelay = config.effectiveMinDelaySeconds();
        int maxDelay = config.effectiveMaxDelaySeconds();
        if (maxPerRun < 1) {
            throw new IllegalArgumentException("猎聘单次岗位上限必须大于0");
        }
        if (minDelay < LiepinConfig.MIN_SAFE_SEND_DELAY_SECONDS
                || maxDelay < minDelay
                || maxDelay > LiepinConfig.MAX_RATE_DELAY_SECONDS) {
            throw new IllegalArgumentException("猎聘发送间隔需满足 120<=最小秒数<=最大秒数<=300");
        }
        if ((config.isAutoAiDeliveryEnabled() || config.isBatchShadowEnabled()) && !config.isAiEnabled()) {
            throw new IllegalArgumentException("开启AI投递模式前，请先开启AI招呼语");
        }
        if (config.effectiveAiReviewMinScore() > config.effectiveAiMinScore()) {
            throw new IllegalArgumentException("AI复核分数不能高于AI通过分数");
        }
        if (config.isBatchAutoDeliveryEnabled() || config.isBatchShadowEnabled()) {
            if (config.effectiveAiBatchSize() < LiepinConfig.MIN_AI_BATCH_SIZE
                    || config.effectiveAiBatchSize() > LiepinConfig.MAX_AI_BATCH_SIZE) {
                throw new IllegalArgumentException("AI批量大小必须在3到10之间");
            }
        }
    }

    private void info(String msg) {
        if (progressCallback != null) {
            progressCallback.onProgress(msg, null, null);
        } else {
            log.info(msg);
        }
    }

    private void submit(String keyword) {
        // 清洗关键词：去掉前后引号与多余空白
        String cleanKeyword = keyword == null ? "" : keyword.replace("\"", "").trim();
        String cityCode = config.getCityCode() == null ? "" : config.getCityCode();
        String salaryCode = config.getSalary() == null ? "" : config.getSalary();
        int plannedStartPage = LiepinPageProgress.nextStartPage(
                liepinService.getLastCompletedPage(cleanKeyword, cityCode, salaryCode)
        );

        if (!navigateAndCaptureSearchResponse(getSearchUrl(plannedStartPage - 1) + "&key=" + cleanKeyword)) return;

        if (!waitForSearchResults()) {
            info(String.format("【%s】搜索结果未加载出岗位卡片，跳过本次关键词", cleanKeyword));
            return;
        }

        // 分页控件只决定是否继续翻页，不作为搜索结果加载成功的必要条件。
        maxPage = 1;
        Locator paginationBox = findPaginationBox();
        if (paginationBox != null) {
            setMaxPage(paginationBox.locator("li"));
        } else {
            info(String.format("【%s】未发现分页控件，按当前结果页处理", cleanKeyword));
        }

        int startPage = plannedStartPage;
        if (LiepinPageProgress.isProgressOutOfRange(plannedStartPage, maxPage)) {
            info(String.format("【%s】进度第%d页超出当前总页数%d，重置并从第1页开始",
                    cleanKeyword, plannedStartPage, maxPage));
            liepinService.clearPageProgress(cleanKeyword, cityCode, salaryCode);
            startPage = 1;
            if (!navigateAndCaptureSearchResponse(getSearchUrl(0) + "&key=" + cleanKeyword)) return;
            if (!waitForSearchResults()) {
                info(String.format("【%s】搜索结果未加载出岗位卡片，跳过本次关键词", cleanKeyword));
                return;
            }
            maxPage = 1;
            paginationBox = findPaginationBox();
            if (paginationBox != null) {
                setMaxPage(paginationBox.locator("li"));
            }
        } else if (startPage > 1) {
            info(String.format("从第【%d】页继续投递【%s】", startPage, cleanKeyword));
        }
        
        boolean finishedAllPages = false;
        for (int i = startPage - 1; i < maxPage; i++) {
            if (shouldStop()) {
                info("收到停止指令，结束分页循环");
                return;
            }
            try {
                // 尝试关闭订阅弹窗
                Locator closeBtn = page.locator(SUBSCRIBE_CLOSE_BTN);
                if (closeBtn.count() > 0) {
                    closeBtn.click();
                }
            } catch (Exception ignored) {
            }
            
        // 等待岗位卡片挂载（不要求可见，避免因遮挡造成超时）
        page.waitForSelector(
            JOB_CARDS,
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.ATTACHED)
                .setTimeout(15000)
        );
            info(String.format("正在投递【%s】第【%d】页...", cleanKeyword, i + 1));
            PageScanResult scanResult = submitJob(cleanKeyword);
            if (scanResult != PageScanResult.COMPLETED) {
                // 半页中断不推进进度，下次仍从本页重试（已聊岗位靠“继续聊”跳过）
                return;
            }
            info(String.format("已投递第【%d】页所有的岗位...", i + 1));
            liepinService.saveLastCompletedPage(cleanKeyword, cityCode, salaryCode, i + 1);
            info(String.format("进度已保存：【%s】完成到第【%d】页", cleanKeyword, i + 1));
            
            // 查找下一页按钮（AntD v5 结构）
            paginationBox = findPaginationBox();
            if (paginationBox == null) {
                finishedAllPages = true;
                break;
            }
            Locator nextLi = paginationBox.locator(NEXT_PAGE);
            if (nextLi.count() > 0) {
                String cls = nextLi.first().getAttribute("class");
                boolean disabled = cls != null && cls.contains("ant-pagination-disabled");
                if (!disabled) {
                    Locator btn = nextLi.first().locator("button.ant-pagination-item-link");
                    if (btn.count() > 0) {
                        if (!clickNextPageAndCaptureResponse(btn.first())) return;
                    } else {
                        if (!clickNextPageAndCaptureResponse(nextLi.first())) return;
                    }
                } else {
                    finishedAllPages = true;
                    break;
                }
            } else {
                finishedAllPages = true;
                break;
            }
        }
        // 能走到这里说明剩余页都完整扫完了（中途 return 不会到这）
        liepinService.clearPageProgress(cleanKeyword, cityCode, salaryCode);
        if (finishedAllPages || startPage <= maxPage) {
            info(String.format("【%s】全部页处理完成，进度已重置", cleanKeyword));
        }
        info(String.format("【%s】关键词投递完成！", cleanKeyword));
    }

    /** 等待搜索结果卡片或任一分页变体出现；单页结果可以没有分页控件。 */
    private boolean waitForSearchResults() {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (page.locator(JOB_CARDS).count() > 0 || findPaginationBox() != null) {
                    return true;
                }
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) {
                    throw pageClosed(e, false);
                }
                log.debug("检查猎聘搜索结果状态失败: {}", e.getMessage());
                return false;
            }
            page.waitForTimeout(200);
        }
        return false;
    }

    private Locator findPaginationBox() {
        String[] selectors = {PAGINATION_BOX, ".ant-pagination", "ul.ant-pagination"};
        for (String selector : selectors) {
            Locator candidate = page.locator(selector);
            if (candidate.count() > 0) {
                return candidate.first();
            }
        }
        return null;
    }

    /**
     * 搜索导航必须把动作和响应等待放在同一个 Playwright 调用中，避免异步 response 回调跨线程读取正文。
     * 导航最多重试一次；聊天点击不走这条重试路径，避免重复发起投递。
     */
    private boolean navigateAndCaptureSearchResponse(String searchUrl) {
        lastApiEntities.clear();
        if (!rateGuard.before(LiepinRateGuard.Action.SEARCH)) return false;
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Response response = page.waitForResponse(
                        this::isSearchResponse,
                        () -> page.navigate(searchUrl)
                );
                validateSearchResponse(response);
                parseSearchResponse(response);
                rateGuard.completed(LiepinRateGuard.Action.SEARCH);
                return true;
            } catch (RateLimitSignalException signal) {
                throw signal;
            } catch (RuntimeException e) {
                if (isTargetClosedFailure(e)) {
                    throw pageClosed(e, false);
                }
                lastFailure = e;
                log.warn("猎聘搜索导航第{}次失败: {}", attempt, e.getMessage());
                if (attempt < 2) {
                    if (!rateGuard.before(LiepinRateGuard.Action.SEARCH)) return false;
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("猎聘搜索导航失败")
                : lastFailure;
    }

    private boolean clickNextPageAndCaptureResponse(Locator nextButton) {
        if (!rateGuard.before(LiepinRateGuard.Action.PAGE)) return false;
        try {
            Response response = page.waitForResponse(
                    this::isSearchResponse,
                    nextButton::click
            );
            validateSearchResponse(response);
            parseSearchResponse(response);
            rateGuard.completed(LiepinRateGuard.Action.PAGE);
            return true;
        } catch (RateLimitSignalException signal) {
            throw signal;
        } catch (RuntimeException e) {
            if (isTargetClosedFailure(e)) {
                throw pageClosed(e, false);
            }
            throw new IllegalStateException("猎聘下一页加载失败: " + e.getMessage(), e);
        }
    }

    private boolean isSearchResponse(Response response) {
        try {
            String url = response.url();
            return url != null
                    && url.contains(SEARCH_API_FRAGMENT)
                    && !url.contains(SEARCH_API_FRAGMENT + "-cond-init");
        } catch (Exception e) {
            return false;
        }
    }

    private void parseSearchResponse(Response response) {
        if (response == null) {
            return;
        }
        try {
            String contentType = response.headers().get("content-type");
            if (contentType != null && !contentType.toLowerCase().contains("json")) {
                return;
            }
            String text = response.text();
            if (text != null && !text.isBlank()) {
                parseAndPersistLiepinData(text);
            }
        } catch (Exception e) {
            log.warn("读取猎聘搜索响应失败: {}", e.getMessage());
        }
    }

    private void validateSearchResponse(Response response) {
        if (response == null) {
            throw new IllegalStateException("猎聘搜索响应为空");
        }
        int status = response.status();
        if (status == 403 || status == 429) {
            String reason = status == 429 ? "请求频率响应429" : "访问被拒绝响应403";
            rateGuard.stopForSignal(reason);
            throw new RateLimitSignalException(reason + "，本次任务已停止");
        }
        String signal = detectRiskSignal(readResponseText(response));
        if (signal != null) {
            rateGuard.stopForSignal("响应出现风控信号：" + signal);
            throw new RateLimitSignalException("响应出现风控信号：" + signal + "，本次任务已停止");
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("猎聘搜索响应状态异常: " + status);
        }
    }

    private String readResponseText(Response response) {
        try {
            return response == null ? null : response.text();
        } catch (Exception ignored) {
            return null;
        }
    }

    static String detectRiskSignal(String text) {
        if (text == null || text.isBlank()) return null;
        String normalized = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String[] signals = {
                "请求频繁", "访问频繁", "操作频繁", "频繁操作", "访问受限", "操作受限",
                "安全验证", "人机验证", "验证码", "行为异常", "账号异常", "请稍后再试",
                "toomanyrequests", "captcha", "forbidden", "rate_limit"
        };
        for (String signal : signals) {
            if (normalized.contains(signal)) return signal;
        }
        return null;
    }

    private void stopForVisibleRiskSignal() {
        try {
            String signal = detectRiskSignal(page.locator("body").innerText());
            if (signal != null) {
                rateGuard.stopForSignal("页面出现风控信号：" + signal);
            }
        } catch (Exception ignored) {
        }
    }

    /** @param zeroBasedPage 猎聘 URL 的 currentPage，从 0 起 */
    private String getSearchUrl(int zeroBasedPage) {
        String baseUrl = "https://www.liepin.com/zhaopin/?";
        StringBuilder sb = new StringBuilder(baseUrl);
        // 直接拼接参数，参数为空则忽略
        if (config.getCityCode() != null && !config.getCityCode().isEmpty()) {
            sb.append("city=").append(config.getCityCode()).append("&");
            sb.append("dq=").append(config.getCityCode()).append("&");
        }
        if (config.getSalary() != null && !config.getSalary().isEmpty()) {
            sb.append("salary=").append(config.getSalary()).append("&");
        }
        sb.append("currentPage=").append(Math.max(0, zeroBasedPage));
        return sb.toString();
    }

    private void setMaxPage(Locator lis) {
        try {
            int count = lis.count();
            if (count >= 2) {
                String pageText = lis.nth(count - 2).textContent();
                int page = Integer.parseInt(pageText);
                if (page > 1) {
                    maxPage = page;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private PageScanResult submitJob(String keyword) {
        // 获取hr数量
        Locator jobCards = page.locator(JOB_CARDS);
        int count = jobCards.count();
        StringBuilder sb = new StringBuilder();
        List<PreparedJob> batchJobs = new ArrayList<>();
        boolean batchMode = config.isBatchAutoDeliveryEnabled() || config.isBatchShadowEnabled();
        for (int i = 0; i < count; i++) {
            if (shouldStop()) {
                info("收到停止指令，结束卡片遍历");
                return PageScanResult.STOPPED;
            }
            if (resultList.size() >= config.effectiveMaxPerRun()) {
                info(String.format("已达到本次成功聊天上限%d个", config.effectiveMaxPerRun()));
                return PageScanResult.LIMIT_REACHED;
            }
            // 获取当前岗位卡片（用于后续操作与缺省展示）
            Locator currentJobCard = page.locator(JOB_CARDS).nth(i);
            // 接口数据是薪资过滤的唯一可信来源；缺失时宁可跳过，也不使用页面占位数据投递
            LiepinEntity apiEntity = i < lastApiEntities.size() ? lastApiEntities.get(i) : null;
            if (apiEntity == null) {
                log.warn("跳过猎聘岗位卡片：未匹配到搜索接口数据，index={}", i);
                info(String.format("已跳过第%d个岗位：薪资数据缺失", i + 1));
                continue;
            }

            String jobName = null;
            String companyName = null;
            String salary = null;
            String recruiterName = null;
            jobName = safeText(apiEntity.getJobTitle());
            companyName = safeText(apiEntity.getCompName());
            salary = safeText(apiEntity.getJobSalaryText());
            recruiterName = safeText(apiEntity.getHrName());
            if (recruiterName == null) recruiterName = "HR";
            if (jobName == null) jobName = "岗位";
            if (companyName == null) companyName = "公司";
            if (salary == null) salary = "";

            LiepinService.SalaryCheck salaryCheck = LiepinService.checkSalaryInRange(
                    apiEntity.getJobSalaryText(), config.getSalary()
            );
            if (!salaryCheck.allowed) {
                log.info("跳过猎聘岗位：jobId={}, 岗位={}, 公司={}, 薪资={}, 原因={}",
                        apiEntity.getJobId(), jobName, companyName, salary, salaryCheck.reason);
                info(String.format("已跳过【%s】【%s】【%s】：%s",
                        companyName, jobName, salary, salaryCheck.reason));
                continue;
            }

            try {
                // 使用JavaScript滚动到卡片位置，更稳定
                try {
                    // 先滚动到卡片位置
                    page.evaluate("(element) => element.scrollIntoView({behavior: 'instant', block: 'center'})", currentJobCard.elementHandle());
                    // PlaywrightUtil.sleep(1); // 等待滚动完成
                    
                    // 再次确保元素在视窗中
                    page.evaluate("(element) => { const rect = element.getBoundingClientRect(); if (rect.top < 0 || rect.bottom > window.innerHeight) { element.scrollIntoView({behavior: 'instant', block: 'center'}); } }", currentJobCard.elementHandle());
                    // PlaywrightUtil.sleep(1);
                } catch (Exception scrollError) {
                    if (isTargetClosedFailure(scrollError)) {
                        throw pageClosed(scrollError, false);
                    }
                    log.warn("JavaScript滚动失败，尝试页面滚动: {}", scrollError.getMessage());
                    // 备用方案：滚动页面到大概位置
                    page.evaluate("window.scrollBy(0, " + (i * 200) + ")");
                    // PlaywrightUtil.sleep(1);
                }
                
                // 查找HR区域 - 尝试多种可能的HR标签选择器
                Locator hrArea = null;
                String[] hrSelectors = {
                    ".recruiter-info-box",  // 根据页面源码，这是主要的HR区域类名
                    ".recruiter-info, .hr-info, .contact-info",
                    "[class*='recruiter'], [class*='hr-'], [class*='contact']",
                    ".job-card-footer, .card-footer",
                    ".job-bottom, .bottom-info"
                };
                
                for (String selector : hrSelectors) {
                    Locator tempHrArea = currentJobCard.locator(selector);
                    if (tempHrArea.count() > 0) {
                        hrArea = tempHrArea.first();
                        log.debug("找到HR区域，使用选择器: {}", selector);
                        break;
                    }
                }
                
                // 如果找不到特定的HR区域，使用整个卡片
                if (hrArea == null) {
                    log.debug("未找到特定HR区域，使用整个岗位卡片");
                    hrArea = currentJobCard;
                }
                
                // 鼠标悬停到HR区域，触发按钮显示 - 简化悬停逻辑
                boolean hoverSuccess = false;
                int hoverRetries = 3;
                for (int retry = 0; retry < hoverRetries; retry++) {
                    try {
                        // 检查HR区域是否可见，如果不可见则跳过悬停
                        if (!hrArea.isVisible()) {
                            log.debug("HR区域不可见，跳过悬停操作");
                            hoverSuccess = true; // 设为成功，继续后续流程
                            break;
                        }
                        
                        // 直接悬停，不再进行复杂的微调
                        hrArea.hover(new Locator.HoverOptions().setTimeout(5000));
                        hoverSuccess = true;
                        break;
                    } catch (Exception hoverError) {
                        if (isTargetClosedFailure(hoverError)) {
                            throw pageClosed(hoverError, false);
                        }
                        log.warn("第{}次悬停失败: {}", retry + 1, hoverError.getMessage());
                        if (retry < hoverRetries - 1) {
                            // 重试前重新滚动确保元素可见
                            try {
                                page.evaluate("(element) => element.scrollIntoView({behavior: 'instant', block: 'center'})", currentJobCard.elementHandle());
                                Thread.sleep(500); // 等待滚动完成
                            } catch (Exception e) {
                                if (isTargetClosedFailure(e)) {
                                    throw pageClosed(e, false);
                                }
                                log.warn("重试前滚动失败: {}", e.getMessage());
                            }
                        }
                    }
                }
                
                if (!hoverSuccess) {
                    log.warn("悬停操作失败，但继续查找按钮");
                    // 不再跳过，而是继续查找按钮，因为有些按钮可能不需要悬停就能显示
                }
                
                // PlaywrightUtil.sleep(1); // 等待按钮显示
                
                // 获取hr名字
                // 已从接口获取 HR 名字，无需再从页面读
                
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) {
                    throw pageClosed(e, false);
                }
                log.error("处理岗位卡片失败: {}", e.getMessage());
                continue;
            }
            
            // 查找聊一聊按钮
            Locator button = null;
            String buttonText = "";
            try {
                // 在当前岗位卡片中查找按钮，尝试多种选择器
                
                String[] buttonSelectors = {
                    "button.ant-btn.ant-btn-primary.ant-btn-round",
                    "button.ant-btn.ant-btn-round.ant-btn-primary", 
                    "button[class*='ant-btn'][class*='primary']",
                    "button[class*='ant-btn'][class*='round']",
                    "button[class*='chat'], button[class*='talk']",
                    ".chat-btn, .talk-btn, .contact-btn",
                    "button:has-text('聊一聊')",
                    "button" // 最后尝试所有按钮
                };
                
                for (String selector : buttonSelectors) {
                    try {
                        Locator tempButtons = currentJobCard.locator(selector);
                        int buttonCount = tempButtons.count();
                        log.debug("选择器 '{}' 找到 {} 个按钮", selector, buttonCount);
                        
                        for (int j = 0; j < buttonCount; j++) {
                            Locator tempButton = tempButtons.nth(j);
                            try {
                                if (tempButton.isVisible()) {
                                    String text = tempButton.textContent();
                                    log.debug("按钮文本: '{}'", text);
                                    if (text != null && !text.trim().isEmpty()) {
                                        button = tempButton;
                                        buttonText = text.trim();
                                        // 只关注"聊一聊"按钮
                                        if (text.contains("聊一聊")) {
                                            log.debug("找到目标按钮: '{}'", text);
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception ignore) {
                                if (isTargetClosedFailure(ignore)) {
                                    throw pageClosed(ignore, false);
                                }
                                log.debug("获取按钮文本失败: {}", ignore.getMessage());
                            }
                        }
                        
                        if (button != null && buttonText.contains("聊一聊")) {
                            break;
                        }
                    } catch (Exception e) {
                        if (isTargetClosedFailure(e)) {
                            throw pageClosed(e, false);
                        }
                        log.debug("选择器 '{}' 查找失败: {}", selector, e.getMessage());
                    }
                }
                
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) {
                    throw pageClosed(e, false);
                }
                log.error("查找按钮失败: {}", e.getMessage());
                // 不再保存页面源码
                continue;
            }
            
            // 提取 jobId（用于更新投递状态）
            Long jobIdForUpdate = null;
            if (i < lastApiEntities.size()) {
                jobIdForUpdate = lastApiEntities.get(i).getJobId();
            }
            if (jobIdForUpdate == null) {
                jobIdForUpdate = extractJobIdFromCard(currentJobCard);
            }

            // 已有会话不能冒充本次新发送；没有可用的“聊一聊”按钮也不读取JD或等待确认。
            if (button == null || !buttonText.contains("聊一聊")) {
                if (buttonText.contains("继续聊")) {
                    info(String.format("【%s】【%s】已有会话，跳过本次发送", companyName, jobName));
                } else if (button != null) {
                    log.debug("跳过岗位（按钮文本不匹配）: 【{}】的【{}·{}】岗位，按钮文本: '{}'", companyName, jobName, salary, buttonText);
                }
                continue;
            }

            if (shouldStop()) {
                info("收到停止指令，结束当前岗位处理");
                return PageScanResult.STOPPED;
            }

            String jd = null;
            if (config.isAiEnabled()) {
                jd = resolveJobDescription(apiEntity);
                if (jd == null || jd.isBlank()) {
                    info(String.format("【%s】【%s】未读取到JD，直接跳过，未发送平台预设语", companyName, jobName));
                    continue;
                }
            }
            if (batchMode) {
                batchJobs.add(new PreparedJob(
                        jobIdForUpdate, apiEntity, button, companyName, jobName, salary, recruiterName, jd));
                aiStats.candidateCount++;
                continue;
            }

            AiGreetingResult aiResult = new AiGreetingResult(null, false);
            if (config.isAiEnabled()) {
                if (config.isAutoAiDeliveryEnabled()) {
                    // 自动模式交给结构化评分，避免旧的硬编码关键词或 FALSE 招呼语漏掉可尝试岗位。
                    LiepinAiAssessment assessment = generateAiAssessment(keyword, jobName, jd, "");
                    if (assessment == null) {
                        info(String.format("【%s】【%s】AI评分结果无效，直接跳过", companyName, jobName));
                        continue;
                    }
                    info(String.format("【%s】【%s】AI评分%d/100：%s",
                            companyName, jobName, assessment.score(), assessment.reason()));
                    if (!passesAutoAiScreening(config, assessment)) {
                        info(String.format("【%s】【%s】AI评分低于%d分，跳过自动投递",
                                companyName, jobName, config.effectiveAiMinScore()));
                        continue;
                    }
                    aiResult = new AiGreetingResult(assessment.message(), false);
                } else {
                    if (!isRelevantJob(jobName, jd)) {
                        info(String.format("【%s】【%s】标题或JD与目标医疗设备方向不相关，直接跳过", companyName, jobName));
                        continue;
                    }
                    aiResult = generateAiGreeting(keyword, jobName, jd);
                    if (!isUsableAiGreeting(aiResult)) {
                        String reason = aiResult != null && aiResult.rejected() ? "AI判定不匹配" : "AI未生成有效话术";
                        info(String.format("【%s】【%s】%s，直接跳过，未发送平台预设语", companyName, jobName, reason));
                        continue;
                    }
                }
            }

            GreetingRequest greetingRequest = new GreetingRequest(
                    apiEntity.getJobId(), companyName, jobName, salary, jd,
                    aiResult.message() != null ? aiResult.message() : "猎聘App预设语",
                    aiResult.message() != null
            );
            GreetingAction action;
            if (config.isAutoAiDeliveryEnabled()) {
                action = GreetingAction.SEND_AI;
            } else {
                action = greetingConfirmation == null
                        ? GreetingAction.SKIP
                        : greetingConfirmation.confirm(greetingRequest);
            }
            if (action == null || action == GreetingAction.SKIP || shouldStop()) {
                info(String.format("已跳过【%s】【%s】", companyName, jobName));
                continue;
            }
            boolean useAiMessage = action == GreetingAction.SEND_AI && aiResult.message() != null;

            try {
                    // 在点击按钮前进行鼠标微调，先向右移动2像素，再向左移动2像素
                    try {
                        var boundingBox = button.boundingBox();
                        if (boundingBox != null) {
                            double centerX = boundingBox.x + boundingBox.width / 2;
                            double centerY = boundingBox.y + boundingBox.height / 2;
                            
                            // 先移动到按钮中心
                            page.mouse().move(centerX, centerY);
                            Thread.sleep(50);
                            
                            // 向右移动2像素
                            page.mouse().move(centerX + 2, centerY);
                            Thread.sleep(50);
                            
                            // 向左移动2像素（回到中心再向左2像素）
                            page.mouse().move(centerX - 2, centerY);
                            Thread.sleep(50);
                            
                            // 回到中心位置
                            page.mouse().move(centerX, centerY);
                            Thread.sleep(50);
                            
                            log.debug("完成鼠标微调，准备点击按钮");
                        }
                    } catch (Exception moveError) {
                        log.warn("鼠标微调失败，直接点击按钮: {}", moveError.getMessage());
                    }
                    
                    if (!rateGuard.before(LiepinRateGuard.Action.SEND)) return PageScanResult.STOPPED;
                    SendResult sendResult = clickAndObserveMessage(button, useAiMessage ? aiResult.message() : null);
                    if (sendResult.sent()) {
                        resultList.add(sb.append("【").append(companyName).append(" ").append(jobName).append(" ").append(salary).append(" ").append(recruiterName).append("】").toString());
                        sb.setLength(0);
                        if (jobIdForUpdate != null) {
                            liepinService.markDelivered(jobIdForUpdate);
                        }
                        info(String.format("【%s】【%s】已发送%s", companyName, jobName,
                                sendResult.aiApplied()
                                        ? (sendResult.resumeApplied() ? "AI话术和猎聘简历" : "AI话术")
                                        : "猎聘预设语"));
                        if (!rateGuard.afterSuccessfulSend(
                                resultList.size() < config.effectiveMaxPerRun())) return PageScanResult.STOPPED;
                        if (resultList.size() >= config.effectiveMaxPerRun()) {
                            info(String.format("已达到本次成功聊天上限%d个", config.effectiveMaxPerRun()));
                            return PageScanResult.LIMIT_REACHED;
                        }
                    } else {
                        info(String.format("【%s】【%s】发送状态不确定，未更新已投递状态", companyName, jobName));
                    }
                    
                } catch (Exception e) {
                    if (isTargetClosedFailure(e)) {
                        throw pageClosed(e, true);
                    }
                    log.error("点击按钮失败: {}", e.getMessage());
                }
        }
        if (batchMode) {
            return processBatchJobs(keyword, batchJobs);
        }
        return PageScanResult.COMPLETED;
    }

    private PageScanResult processBatchJobs(String keyword, List<PreparedJob> jobs) {
        if (jobs.isEmpty()) {
            return PageScanResult.COMPLETED;
        }

        Map<String, LiepinAiBatchAssessment.Item> assessments = generateBatchAssessments(keyword, jobs);
        List<PreparedJob> accepted = new ArrayList<>();
        for (PreparedJob job : jobs) {
            String jobId = String.valueOf(job.jobId());
            LiepinAiBatchAssessment.Item assessment = assessments.get(jobId);
            if (assessment == null) {
                aiStats.invalid++;
                info(String.format("【%s】【%s】AI评分缺少岗位结果，跳过", job.companyName(), job.jobName()));
                continue;
            }
            info(String.format("【%s】【%s】AI评分%d/100：%s",
                    job.companyName(), job.jobName(), assessment.score(), assessment.reason()));
            boolean hardMismatch = assessment.reasonCodes().contains("HARD_MISMATCH");
            if ("PASS".equals(assessment.decision())
                    && assessment.score() >= config.effectiveAiMinScore()
                    && !hardMismatch) {
                aiStats.passed++;
                accepted.add(job);
            } else if (!hardMismatch && assessment.score() >= config.effectiveAiReviewMinScore()
                    && ("REVIEW".equals(assessment.decision())
                    || ("PASS".equals(assessment.decision())
                    && assessment.score() < config.effectiveAiMinScore()))) {
                aiStats.review++;
                info(String.format("【%s】【%s】进入AI复核记录，不自动发送", job.companyName(), job.jobName()));
            } else {
                aiStats.skipped++;
                info(String.format("【%s】【%s】AI评分未达到%d分，跳过自动投递",
                        job.companyName(), job.jobName(), config.effectiveAiMinScore()));
            }
        }

        Map<String, String> messages = generateBatchMessages(keyword, accepted);
        if (config.isBatchShadowEnabled()) {
            for (PreparedJob job : accepted) {
                if (messages.containsKey(String.valueOf(job.jobId()))) {
                    info(String.format("【%s】【%s】批量评分预演通过，已生成话术，未执行发送",
                            job.companyName(), job.jobName()));
                }
            }
            return PageScanResult.COMPLETED;
        }

        for (PreparedJob job : accepted) {
            if (shouldStop()) return PageScanResult.STOPPED;
            String message = messages.get(String.valueOf(job.jobId()));
            if (!isValidAiMessage(message)) {
                aiStats.invalid++;
                info(String.format("【%s】【%s】AI话术校验失败，跳过发送", job.companyName(), job.jobName()));
                continue;
            }
            PageScanResult result = sendPreparedJob(job, message);
            if (result != PageScanResult.COMPLETED) return result;
        }
        return PageScanResult.COMPLETED;
    }

    private Map<String, LiepinAiBatchAssessment.Item> generateBatchAssessments(
            String keyword, List<PreparedJob> jobs) {
        AiEntity aiConfig = aiService.getAiConfig();
        String candidate = safeValue(aiConfig == null ? null : aiConfig.getIntroduce());
        String model = safeValue(aiService.getCurrentModel());
        Map<String, LiepinAiBatchAssessment.Item> results = new LinkedHashMap<>();
        Map<PreparedJob, String> hashes = new LinkedHashMap<>();
        List<PreparedJob> uncached = new ArrayList<>();

        for (PreparedJob job : jobs) {
            String hash = screenInputHash(keyword, candidate, model, aiConfig, job);
            hashes.put(job, hash);
            LiepinService.AiScreenCache cached = liepinService.findAiScreenCache(job.jobId(), hash);
            if (cached != null && cached.score() != null && cached.decision() != null) {
                results.put(String.valueOf(job.jobId()), new LiepinAiBatchAssessment.Item(
                        String.valueOf(job.jobId()), cached.score(), cached.decision(),
                        splitReasonCodes(cached.reasonCodes()), safeValue(cached.reason())));
                aiStats.cacheHits++;
            } else {
                uncached.add(job);
            }
        }

        for (List<PreparedJob> group : splitJobs(uncached, config.effectiveAiBatchSize())) {
            LiepinAiBatchAssessment.Result parsed = requestBatchScreen(
                    keyword, candidate, model, aiConfig, group, false);
            if (parsed == null) {
                parsed = requestBatchScreen(keyword, candidate, model, aiConfig, group, true);
            }
            Set<String> seen = new HashSet<>();
            if (parsed != null) {
                Set<String> requested = new HashSet<>();
                for (PreparedJob job : group) requested.add(String.valueOf(job.jobId()));
                for (LiepinAiBatchAssessment.Item item : parsed.items()) {
                    if (!requested.contains(item.jobId()) || !seen.add(item.jobId())) {
                        aiStats.invalid++;
                        continue;
                    }
                    PreparedJob job = findJob(group, item.jobId());
                    if (job == null) continue;
                    results.put(item.jobId(), item);
                    liepinService.saveAiScreenCache(job.jobId(), hashes.get(job), model,
                            item.score(), item.decision(), String.join(",", item.reasonCodes()),
                            item.reason(), 0);
                }
            }
            for (PreparedJob job : group) {
                String jobId = String.valueOf(job.jobId());
                if (results.containsKey(jobId)) continue;
                aiStats.invalid++;
                LiepinAiAssessment fallback = generateAiAssessment(keyword, job.jobName(), job.jd(), "");
                if (fallback == null) continue;
                String decision = fallback.score() >= config.effectiveAiMinScore()
                        ? "PASS"
                        : fallback.score() >= config.effectiveAiReviewMinScore() ? "REVIEW" : "SKIP";
                LiepinAiBatchAssessment.Item item = new LiepinAiBatchAssessment.Item(
                        jobId, fallback.score(), decision, List.of("SINGLE_FALLBACK"), fallback.reason());
                results.put(jobId, item);
                liepinService.saveAiScreenCache(job.jobId(), hashes.get(job), model, item.score(),
                        item.decision(), "SINGLE_FALLBACK", item.reason(), 0);
            }
        }
        return results;
    }

    private LiepinAiBatchAssessment.Result requestBatchScreen(
            String keyword, String candidate, String model, AiEntity aiConfig,
            List<PreparedJob> jobs, boolean repair) {
        try {
            Map<String, String> values = new HashMap<>();
            values.put("candidate", candidate);
            values.put("keyword", safeValue(keyword));
            values.put("min_score", String.valueOf(config.effectiveAiMinScore()));
            values.put("rules", "薪资已通过本地筛选；优先判断方向、技能、经验和JD职责匹配度；硬性冲突标记HARD_MISMATCH");
            values.put("jobs", jobsJson(jobs));
            String template = aiConfig == null || aiConfig.getScreenPrompt() == null
                    || aiConfig.getScreenPrompt().isBlank()
                    ? AiService.DEFAULT_SCREEN_PROMPT : aiConfig.getScreenPrompt();
            String prompt = aiService.renderNamedTemplate(template, values);
            if (repair) prompt += "\n只返回符合上述格式的JSON对象，items中的jobId必须来自输入岗位。";
            long started = System.currentTimeMillis();
            aiStats.screenCalls++;
            String raw = aiService.sendStructuredRequest(prompt, 0.1, 384);
            long latency = System.currentTimeMillis() - started;
            aiStats.totalLatencyMs += latency;
            aiStats.latencySamples++;
            return LiepinAiBatchAssessment.parse(raw);
        } catch (Exception e) {
            log.warn("批量 AI 评分请求失败，岗位数={}, model={}: {}", jobs.size(), model, e.getMessage());
            return null;
        }
    }

    private Map<String, String> generateBatchMessages(String keyword, List<PreparedJob> jobs) {
        Map<String, String> results = new LinkedHashMap<>();
        if (jobs.isEmpty()) return results;
        AiEntity aiConfig = aiService.getAiConfig();
        String candidate = safeValue(aiConfig == null ? null : aiConfig.getIntroduce());
        String model = safeValue(aiService.getCurrentModel());
        Map<PreparedJob, String> hashes = new LinkedHashMap<>();
        List<PreparedJob> uncached = new ArrayList<>();
        for (PreparedJob job : jobs) {
            String hash = messageInputHash(keyword, candidate, model, aiConfig, job);
            hashes.put(job, hash);
            LiepinService.AiMessageCache cached = liepinService.findAiMessageCache(job.jobId(), hash);
            if (cached != null && isValidAiMessage(cached.message())) {
                results.put(String.valueOf(job.jobId()), cached.message());
                aiStats.cacheHits++;
            } else {
                uncached.add(job);
            }
        }

        for (List<PreparedJob> group : splitJobs(uncached, config.effectiveAiBatchSize())) {
            LiepinAiBatchMessage.Result parsed = requestBatchMessages(
                    keyword, candidate, model, aiConfig, group, false);
            if (parsed == null) {
                parsed = requestBatchMessages(keyword, candidate, model, aiConfig, group, true);
            }
            Set<String> requested = new HashSet<>();
            for (PreparedJob job : group) requested.add(String.valueOf(job.jobId()));
            Set<String> seen = new HashSet<>();
            if (parsed != null) {
                for (LiepinAiBatchMessage.Item item : parsed.items()) {
                    if (!requested.contains(item.jobId()) || !seen.add(item.jobId())
                            || !isValidAiMessage(item.message())) {
                        aiStats.invalid++;
                        continue;
                    }
                    PreparedJob job = findJob(group, item.jobId());
                    if (job == null) continue;
                    results.put(item.jobId(), item.message());
                    liepinService.saveAiMessageCache(job.jobId(), hashes.get(job), model, item.message(), 0);
                }
            }
            for (PreparedJob job : group) {
                String jobId = String.valueOf(job.jobId());
                if (results.containsKey(jobId)) continue;
                aiStats.invalid++;
                LiepinAiBatchMessage.Result single = requestBatchMessages(
                        keyword, candidate, model, aiConfig, List.of(job), true);
                if (single == null || single.items().size() != 1
                        || !jobId.equals(single.items().get(0).jobId())
                        || !isValidAiMessage(single.items().get(0).message())) continue;
                String message = single.items().get(0).message();
                results.put(jobId, message);
                liepinService.saveAiMessageCache(job.jobId(), hashes.get(job), model, message, 0);
            }
        }
        return results;
    }

    private LiepinAiBatchMessage.Result requestBatchMessages(
            String keyword, String candidate, String model, AiEntity aiConfig,
            List<PreparedJob> jobs, boolean repair) {
        try {
            Map<String, String> values = new HashMap<>();
            values.put("candidate", candidate);
            values.put("style_rules", "称呼自然，突出岗位相关技能，表达愿意沟通，不虚构事实");
            values.put("accepted_jobs", jobsJson(jobs));
            String template = aiConfig == null || aiConfig.getMessagePrompt() == null
                    || aiConfig.getMessagePrompt().isBlank()
                    ? AiService.DEFAULT_MESSAGE_PROMPT : aiConfig.getMessagePrompt();
            String prompt = aiService.renderNamedTemplate(template, values);
            if (repair) prompt += "\n只返回符合上述格式的JSON对象，message必须是单行且不超过60字。";
            long started = System.currentTimeMillis();
            aiStats.messageCalls++;
            String raw = aiService.sendStructuredRequest(prompt, 0.25, 512);
            long latency = System.currentTimeMillis() - started;
            aiStats.totalLatencyMs += latency;
            aiStats.latencySamples++;
            return LiepinAiBatchMessage.parse(raw);
        } catch (Exception e) {
            log.warn("批量 AI 话术请求失败，岗位数={}, model={}: {}", jobs.size(), model, e.getMessage());
            return null;
        }
    }

    private PageScanResult sendPreparedJob(PreparedJob job, String message) {
        try {
            var boundingBox = job.button().boundingBox();
            if (boundingBox != null) {
                double centerX = boundingBox.x + boundingBox.width / 2;
                double centerY = boundingBox.y + boundingBox.height / 2;
                page.mouse().move(centerX, centerY);
            }
            if (!rateGuard.before(LiepinRateGuard.Action.SEND)) return PageScanResult.STOPPED;
            SendResult sendResult = clickAndObserveMessage(job.button(), message);
            if (!sendResult.sent()) {
                info(String.format("【%s】【%s】发送状态不确定，未更新已投递状态", job.companyName(), job.jobName()));
                return PageScanResult.COMPLETED;
            }
            resultList.add(String.format("【%s %s %s %s】", job.companyName(), job.jobName(),
                    job.salary(), job.recruiterName()));
            if (job.jobId() != null) liepinService.markDelivered(job.jobId());
            info(String.format("【%s】【%s】已发送AI话术%s", job.companyName(), job.jobName(),
                    sendResult.resumeApplied() ? "和简历" : ""));
            if (!rateGuard.afterSuccessfulSend(
                    resultList.size() < config.effectiveMaxPerRun())) return PageScanResult.STOPPED;
            if (resultList.size() >= config.effectiveMaxPerRun()) {
                info(String.format("已达到本次成功聊天上限%d个", config.effectiveMaxPerRun()));
                return PageScanResult.LIMIT_REACHED;
            }
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) throw pageClosed(e, true);
            log.error("批量模式点击按钮失败: {}", e.getMessage());
        }
        return PageScanResult.COMPLETED;
    }

    private List<List<PreparedJob>> splitJobs(List<PreparedJob> jobs, int size) {
        List<List<PreparedJob>> groups = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i += size) {
            groups.add(new ArrayList<>(jobs.subList(i, Math.min(jobs.size(), i + size))));
        }
        return groups;
    }

    private PreparedJob findJob(List<PreparedJob> jobs, String jobId) {
        for (PreparedJob job : jobs) {
            if (String.valueOf(job.jobId()).equals(jobId)) return job;
        }
        return null;
    }

    private String jobsJson(List<PreparedJob> jobs) {
        JSONArray array = new JSONArray();
        for (PreparedJob job : jobs) {
            JSONObject item = new JSONObject();
            item.put("jobId", String.valueOf(job.jobId()));
            item.put("company", job.companyName());
            item.put("title", job.jobName());
            item.put("salary", job.salary());
            item.put("jd", truncate(job.jd(), 4000));
            array.put(item);
        }
        return array.toString();
    }

    private String screenInputHash(String keyword, String candidate, String model,
                                    AiEntity aiConfig, PreparedJob job) {
        return hash("screen|" + safeValue(model) + "|" + safeValue(keyword) + "|"
                + safeValue(candidate) + "|" + safeValue(aiConfig == null ? null : aiConfig.getScreenPrompt())
                + "|" + jobFingerprint(job));
    }

    private String messageInputHash(String keyword, String candidate, String model,
                                    AiEntity aiConfig, PreparedJob job) {
        return hash("message|" + safeValue(model) + "|" + safeValue(keyword) + "|"
                + safeValue(candidate) + "|" + safeValue(aiConfig == null ? null : aiConfig.getMessagePrompt())
                + "|" + jobFingerprint(job));
    }

    private String jobFingerprint(PreparedJob job) {
        return String.join("|", safeValue(job.jobId()), safeValue(job.jobName()),
                safeValue(job.salary()), safeValue(job.jd()));
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("生成 AI 缓存哈希失败", e);
        }
    }

    private List<String> splitReasonCodes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split(","));
    }

    private boolean isValidAiMessage(String message) {
        return message != null && !message.isBlank() && message.length() <= 60
                && !message.contains("\n") && !message.contains("```") && !message.contains("{{");
    }

    private String safeValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String truncate(String value, int maxLength) {
        String normalized = safeValue(value);
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String resolveJobDescription(LiepinEntity entity) {
        if (entity.getJobDescription() != null && !entity.getJobDescription().isBlank()) {
            return entity.getJobDescription();
        }
        String link = normalizeJobLink(entity.getJobLink());
        if (link == null) return null;

        Page detailPage = null;
        try {
            if (!rateGuard.before(LiepinRateGuard.Action.DETAIL)) return null;
            detailPage = page.context().newPage();
            detailPage.setDefaultTimeout(10000);
            detailPage.navigate(link, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(10000));
            detailPage.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(10000));

            String[] selectors = {
                    ".job-intro-container", ".job-intro", ".job-description",
                    "[class*='job-intro']", "[class*='job-description']",
                    "[class*='job-detail']"
            };
            for (String selector : selectors) {
                Locator locator = detailPage.locator(selector);
                if (locator.count() == 0) continue;
                for (int i = 0; i < Math.min(locator.count(), 3); i++) {
                    Locator candidate = locator.nth(i);
                    if (!candidate.isVisible()) continue;
                    String text = normalizeJd(candidate.innerText());
                    if (text != null && text.length() >= 20) {
                        entity.setJobDescription(text);
                        liepinService.updateJobDescription(entity.getJobId(), text);
                        return text;
                    }
                }
            }

            String body = normalizeJd(detailPage.locator("body").innerText());
            if (body != null && (body.contains("职位描述") || body.contains("岗位职责") || body.contains("任职要求"))) {
                entity.setJobDescription(body);
                liepinService.updateJobDescription(entity.getJobId(), body);
                return body;
            }
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) {
                throw pageClosed(e, false);
            }
            log.warn("读取猎聘岗位JD失败 job_id={}, link={}: {}", entity.getJobId(), link, e.getMessage());
        } finally {
            rateGuard.completed(LiepinRateGuard.Action.DETAIL);
            if (detailPage != null) {
                try {
                    detailPage.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private String normalizeJobLink(String link) {
        if (link == null || link.isBlank()) return null;
        String normalized = link.trim();
        if (normalized.startsWith("//")) return "https:" + normalized;
        if (normalized.startsWith("/")) return "https://www.liepin.com" + normalized;
        return normalized.startsWith("http://") || normalized.startsWith("https://") ? normalized : null;
    }

    private String normalizeJd(String text) {
        if (text == null) return null;
        String normalized = text.replace('\u00A0', ' ').replaceAll("[\\t\\r ]+", " ").trim();
        return normalized.isBlank() ? null : normalized;
    }

    private AiGreetingResult generateAiGreeting(String keyword, String jobName, String jd) {
        try {
            AiEntity aiConfig = aiService.getAiConfig();
            String introduce = aiConfig == null || aiConfig.getIntroduce() == null ? "" : aiConfig.getIntroduce();
            String prompt = aiConfig == null ? null : aiConfig.getPrompt();
            if (prompt == null || prompt.isBlank()) return new AiGreetingResult(null, false);

            String request = aiService.renderPrompt(prompt, introduce, keyword, jobName, jd, "");
            String result = aiService.sendRequest(request);
            return classifyAiResponse(result);
        } catch (Exception e) {
            log.warn("猎聘AI请求失败，将跳过当前岗位，不发送平台预设语: {}", e.getMessage());
            return new AiGreetingResult(null, false);
        }
    }

    private LiepinAiAssessment generateAiAssessment(String keyword, String jobName, String jd, String greeting) {
        try {
            AiEntity aiConfig = aiService.getAiConfig();
            String introduce = aiConfig == null || aiConfig.getIntroduce() == null ? "" : aiConfig.getIntroduce();
            String request = """
                    你是一个严格的求职岗位匹配评分器。请根据候选人简介、搜索关键词、岗位名称、岗位JD和参考招呼语，判断这个岗位是否值得尝试投递。
                    评分范围是0到100：岗位职责、候选人经历和方向越匹配，分数越高；明显无关岗位应给低分。
                    只返回一个JSON对象，不要Markdown代码块，不要额外解释。字段必须完整：
                    {"score":整数,"reason":"不超过80字的中文理由","message":"可直接发送的简短中文招呼语"}

                    候选人简介：%s
                    搜索关键词：%s
                    岗位名称：%s
                    岗位JD：%s
                    参考招呼语：%s
                    """.formatted(introduce, keyword, jobName, jd, greeting);
            return LiepinAiAssessment.parse(aiService.sendRequest(request));
        } catch (Exception e) {
            log.warn("猎聘AI评分请求失败，将跳过当前岗位: {}", e.getMessage());
            return null;
        }
    }

    static AiGreetingResult classifyAiResponse(String result) {
        if (result == null) return new AiGreetingResult(null, false);
        String normalized = result.trim();
        if (normalized.isBlank()) return new AiGreetingResult(null, false);
        if (normalized.equalsIgnoreCase("false")) return new AiGreetingResult(null, true);
        String message = normalized.replaceAll("^```(?:text)?\\s*|\\s*```$", "").trim();
        return message.isBlank() ? new AiGreetingResult(null, false) : new AiGreetingResult(message, false);
    }

    static boolean isUsableAiGreeting(AiGreetingResult result) {
        return result != null
                && !result.rejected()
                && result.message() != null
                && !result.message().isBlank();
    }

    static boolean passesAutoAiScreening(LiepinConfig config, LiepinAiAssessment assessment) {
        return config != null
                && config.isAutoAiDeliveryEnabled()
                && assessment != null
                && assessment.passes(config.effectiveAiMinScore());
    }

    static boolean isRelevantJob(String jobTitle, String jd) {
        String title = normalizeMatchText(jobTitle);
        String description = normalizeMatchText(jd);
        if (title.isBlank() || description.isBlank()) return false;

        boolean hasRelevantDomain = RELEVANT_DOMAIN_TERMS.stream()
                .anyMatch(term -> title.contains(term) || description.contains(term));
        if (!hasRelevantDomain) return false;

        if (EXCLUDED_TITLE_TERMS.stream().anyMatch(title::contains)) return false;
        return GENERIC_MANUFACTURING_TERMS.stream().noneMatch(title::contains);
    }

    private static String normalizeMatchText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private record SendResult(boolean sent, boolean aiApplied, boolean resumeApplied) {
    }

    private SendResult clickAndObserveMessage(Locator button, String aiMessage) {
        int initialOutgoingMessages = visibleOutgoingMessageCount();
        try {
            // “聊一聊”会打开聊天窗口，平台随后通过实时通道自动下发默认沟通语。
            // 发送结果以聊天窗口里新增的本人消息为准，不等待不存在的HTTP发送响应。
            button.click();
            boolean presetSent = waitForNewOutgoingMessage(initialOutgoingMessages, 20000);
            if (!presetSent) {
                stopForVisibleRiskSignal();
                log.warn("猎聘平台默认沟通语未在聊天窗口确认");
                closeChatWindow();
                return new SendResult(false, false, false);
            }

            boolean aiSent = false;
            boolean resumeSent = false;
            if (aiMessage != null && !aiMessage.isBlank()) {
                aiSent = sendAiMessageInChat(aiMessage);
                if (!aiSent) {
                    log.warn("猎聘AI话术未确认，保留已确认的平台默认沟通语");
                } else {
                    resumeSent = sendResumeInChat();
                    if (!resumeSent) {
                        log.warn("猎聘AI话术已确认，但自动发简历未确认");
                    }
                }
            }
            log.info("猎聘平台默认沟通语已确认: aiApplied={}, resumeApplied={}", aiSent, resumeSent);
            closeChatWindow();
            return new SendResult(true, aiSent, resumeSent);
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) {
                throw pageClosed(e, true);
            }
            log.warn("猎聘聊天发送确认失败: {}", e.getMessage());
            closeChatWindow();
            return new SendResult(false, false, false);
        } finally {
            rateGuard.completed(LiepinRateGuard.Action.SEND);
        }
    }

    private int visibleOutgoingMessageCount() {
        return page.locator(".im-ui-txt.send:visible").count();
    }

    private boolean waitForNewOutgoingMessage(int initialCount, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (visibleOutgoingMessageCount() > initialCount) {
                return true;
            }
            page.waitForTimeout(200);
        }
        return false;
    }

    private boolean sendAiMessageInChat(String message) {
        Locator input = page.locator("textarea[placeholder*='请输入文字']").first();
        input.waitFor(new Locator.WaitForOptions().setTimeout(5000));
        int before = visibleOutgoingMessageCount();
        input.fill(message);
        input.press("Enter");
        return waitForNewOutgoingMessage(before, 10000);
    }

    private boolean sendResumeInChat() {
        Locator resumeAction = findVisibleResumeAction();
        if (resumeAction == null) {
            log.warn("聊天窗口未找到可见的发简历动作");
            return false;
        }
        int before = visibleOutgoingMessageCount();
        try {
            resumeAction.click();
            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline) {
                if (visibleOutgoingMessageCount() > before) {
                    return true;
                }
                Locator submit = findVisibleResumeSubmitButton();
                if (submit != null) {
                    submit.click();
                    boolean sent = waitForNewOutgoingMessage(before, 15000);
                    if (!sent) {
                        log.warn("点击立即投递后未在聊天窗口确认简历消息");
                        closeVisibleResumeModal();
                    }
                    return sent;
                }
                page.waitForTimeout(200);
            }
            log.warn("聊天窗口未找到可见的立即投递确认按钮");
            closeVisibleResumeModal();
            return false;
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) {
                throw pageClosed(e, true);
            }
            log.warn("自动发简历失败: {}", e.getMessage());
            closeVisibleResumeModal();
            return false;
        }
    }

    private Locator findVisibleResumeSubmitButton() {
        Locator buttons = page.locator("div.ant-im-modal-wrap:visible button");
        for (int i = 0; i < buttons.count(); i++) {
            Locator item = buttons.nth(i);
            try {
                if (item.isVisible() && RESUME_SUBMIT_TEXT.equals(item.innerText().trim())) {
                    return item;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void closeVisibleResumeModal() {
        try {
            Locator close = page.locator("div.ant-im-modal-wrap:visible button.ant-im-modal-close");
            if (close.count() > 0 && close.first().isVisible()) {
                close.first().click();
            }
        } catch (Exception e) {
            log.debug("关闭发简历确认弹窗失败: {}", e.getMessage());
        }
    }

    private Locator findVisibleResumeAction() {
        Locator exactText = page.getByText(RESUME_ACTION_TEXT,
                new Page.GetByTextOptions().setExact(true));
        Locator labeledAction = page.locator("[aria-label*='" + RESUME_ACTION_TEXT + "'], [title*='"
                + RESUME_ACTION_TEXT + "']");
        Locator[] candidates = {exactText, labeledAction};
        for (Locator candidate : candidates) {
            for (int i = 0; i < candidate.count(); i++) {
                Locator item = candidate.nth(i);
                try {
                    if (item.isVisible()) return item;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private void closeChatWindow() {
        try {
            Locator close = page.locator("button[aria-label='Close']");
            if (close.count() == 0) {
                close = page.locator("button.ant-im-drawer-close");
            }
            if (close.count() == 0) {
                close = page.locator(CHAT_CLOSE);
            }
            if (close.count() > 0) {
                close.first().click();
            }
        } catch (Exception e) {
            log.debug("关闭猎聘聊天窗口失败: {}", e.getMessage());
        }
    }

    // 从岗位卡片的 data 属性中提取 jobId（兼容 lastApiEntities 缺失场景）
    private Long extractJobIdFromCard(Locator card) {
        try {
            String ext = card.getAttribute("data-tlg-ext");
            if (ext != null && !ext.isEmpty()) {
                try {
                    String decoded = java.net.URLDecoder.decode(ext, java.nio.charset.StandardCharsets.UTF_8);
                    com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(decoded);
                    String jobIdStr = node.path("jobId").asText(null);
                    if (jobIdStr != null && !jobIdStr.isEmpty()) {
                        return Long.parseLong(jobIdStr);
                    }
                } catch (Exception ignore) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\\\\"jobId\\\\\":\\\\\"(\\d+)\\\\\"").matcher(ext);
                    if (m.find()) {
                        return Long.parseLong(m.group(1));
                    }
                }
            }
            String scm = card.getAttribute("data-tlg-scm");
            if (scm != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("jobId=(\\d+)").matcher(scm);
                if (m.find()) {
                    return Long.parseLong(m.group(1));
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static final class RateLimitSignalException extends RuntimeException {
        private RateLimitSignalException(String message) {
            super(message);
        }
    }
}
