package com.jobradar.worker.job51;

import com.jobradar.application.service.Job51Service;
import com.jobradar.application.service.JobFunnelService;
import com.jobradar.application.service.AiService;
import com.jobradar.application.entity.AiEntity;
import com.jobradar.application.entity.Job51Entity;
import com.jobradar.worker.liepin.LiepinAiBatchAssessment;
import com.jobradar.worker.utils.JobUtils;
import com.jobradar.worker.utils.JobDescriptionExtractor;
import com.jobradar.worker.utils.PlaywrightUtil;
import com.jobradar.worker.manager.ReadRequestStatus;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/napstablook666/JobRadar">https://github.com/napstablook666/JobRadar</a>
 * 前程无忧自动投递简历 - Playwright版本
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Job51 {

    // 显式setter，避免对 Lombok 的依赖导致编译问题
    @Setter
    private Page page;

    @Getter
    @Setter
    private Job51Config config;

    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private Supplier<Boolean> shouldStopCallback;

    /** 页面关闭或 CDP 重连后，由管理器提供新的可用 Page。 */
    @Setter
    private Supplier<Page> pageRecovery;

    /** 当前详情页等待人工完成访问验证的游标。 */
    @Getter
    private VerificationRequest verificationRequest;

    private final List<String> resultList = new ArrayList<>();
    private final Job51Service job51Service;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private JobFunnelService jobFunnelService;
    private final AiService aiService;
    private boolean networkHooked = false;
    private boolean reachedDailyLimit = false;
    private boolean detailAccessBlocked = false;
    private final java.util.Set<String> processedRequestIds = new java.util.HashSet<>();
    @Getter
    private int currentPageNum = 0;
    @Getter
    private String executionError;
    @Getter
    private String stopReason;
    @Getter
    private RestRequest restRequest;
    @Getter
    private int selectedCount;
    @Getter
    private int confirmedSuccessCount;
    @Getter
    private int aiProcessedCount;
    @Getter
    private int aiCandidateCount;
    @Getter
    private int greetingSentCount;
    @Getter
    private int greetingSkippedCount;
    @Getter
    private int greetingFailedCount;
    @Getter
    private int aiDeliveredCount;
    @Getter
    private int reviewCount;
    @Getter
    private int retryableCount;
    @Getter
    private boolean successfulProgressSinceRest;
    @Getter
    private volatile ReadRequestStatus readRequestStatus =
            ReadRequestStatus.browser(false, "尚未读取51job搜索");
    // 当前页从JSON拦截到的jobId列表
    private final java.util.List<Long> currentPageJobIds = new java.util.ArrayList<>();
    private int currentKeywordIndex;
    private String currentKeyword;
    private int resumeKeywordIndex;
    private int resumePage = 1;
    private final java.util.Set<Long> verificationSkippedJobIds = new java.util.HashSet<>();

    private static final int DEFAULT_MAX_PAGE = 50;
    private static final String BASE_URL = "https://we.51job.com/pc/search?";
    private static final long DETAIL_TIMEOUT_MS = 15000L;
    private static final long DETAIL_ACTION_TIMEOUT_MS = 15000L;
    private static final long JD_WAIT_TIMEOUT_MS = 10000L;
    private static final int MIN_JOB_DESCRIPTION_LENGTH = 20;
    private static final long MESSAGE_CONFIRM_TIMEOUT_MS = 12000L;
    private static final long MESSAGE_PAGE_WAIT_TIMEOUT_MS = 5000L;
    private static final int AI_MAX_ATTEMPTS = 3;
    private static final long AI_FIRST_RETRY_DELAY_MS = 1000L;
    private static final long AI_SECOND_RETRY_DELAY_MS = 2000L;
    private static final Pattern AI_STATUS_CODE_PATTERN = Pattern.compile(
            "(?i)(?:状态码|status(?:\\s+code)?|http)\\s*[:=]?\\s*(\\d{3})");

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    public enum ExecutionOutcome {
        COMPLETED,
        REST_REQUIRED,
        VERIFICATION_REQUIRED,
        USER_CANCELLED,
        FAILED
    }

    public record RestRequest(String reason, String message, String keyword,
                              int keywordIndex, int page) {
    }

    public record VerificationRequest(Long jobId, String detailUrl, String keyword,
                                      int keywordIndex, int page, int cardIndex,
                                      String company, String title) {
    }

    public record ExecutionResult(int deliveredCount, ExecutionOutcome outcome,
                                  RestRequest restRequest,
                                  VerificationRequest verificationRequest) {
        public ExecutionResult(int deliveredCount, ExecutionOutcome outcome,
                               RestRequest restRequest) {
            this(deliveredCount, outcome, restRequest, null);
        }
    }

    /**
     * 准备工作：加载配置、初始化数据
     */
    public void prepare() {
        resultList.clear();
        executionError = null;
        stopReason = null;
        restRequest = null;
        verificationRequest = null;
        selectedCount = 0;
        confirmedSuccessCount = 0;
        aiProcessedCount = 0;
        aiCandidateCount = 0;
        greetingSentCount = 0;
        greetingSkippedCount = 0;
        greetingFailedCount = 0;
        aiDeliveredCount = 0;
        reviewCount = 0;
        retryableCount = 0;
        successfulProgressSinceRest = false;
        readRequestStatus = ReadRequestStatus.browser(false, "尚未读取51job搜索");
        currentPageNum = 0;
        reachedDailyLimit = false;
        detailAccessBlocked = false;
        currentKeywordIndex = 0;
        currentKeyword = null;
        resumeKeywordIndex = 0;
        resumePage = 1;
        synchronized (currentPageJobIds) {
            currentPageJobIds.clear();
        }
        verificationSkippedJobIds.clear();
    }

    /**
     * 执行投递任务
     * @return 投递数量
     */
    public int execute() {
        return executeWithResult().deliveredCount();
    }

    public ExecutionResult executeWithResult() {
        long startTime = System.currentTimeMillis();

        try {
            // 检查配置是否有效
            if (config == null) {
                log.error("[51job] 配置为空，无法执行投递任务");
                sendProgress("配置为空，无法执行投递任务", null, null);
                executionError = "配置为空";
                return new ExecutionResult(0, ExecutionOutcome.FAILED, null);
            }
            
            if (config.getKeywords() == null || config.getKeywords().isEmpty()) {
                log.warn("[51job] 关键词列表为空，无法执行投递任务");
                sendProgress("关键词列表为空，请先配置搜索关键词", null, null);
                executionError = "关键词列表为空";
                return new ExecutionResult(0, ExecutionOutcome.FAILED, null);
            }
            
            if (config.isAiEnabled()) {
                sendProgress(String.format("AI逐岗位模式已启用，本次最多处理%d个岗位，间隔%d-%d秒",
                        config.effectiveMaxPerRun(), config.effectiveMinDelaySeconds(), config.effectiveMaxDelaySeconds()), null, null);
                executeAiDelivery();
            } else {
                // 遍历所有关键词进行投递
                for (int keywordIndex = resumeKeywordIndex; keywordIndex < config.getKeywords().size(); keywordIndex++) {
                    String keyword = config.getKeywords().get(keywordIndex);
                    currentKeywordIndex = keywordIndex;
                    currentKeyword = keyword;
                    if (shouldStop()) {
                        stopReason = "user_cancelled";
                        sendProgress("用户取消投递", null, null);
                        break;
                    }
                    if (hasReachedMaxPerRun()) {
                        handleMaxPerRunReached();
                        break;
                    }

                    String searchUrl = buildSearchUrl(keyword);
                    deliverByKeyword(keyword, searchUrl, keywordIndex);
                    if (restRequest != null) break;
                }
            }

            if (verificationRequest != null) {
                return new ExecutionResult(resultList.size(), ExecutionOutcome.VERIFICATION_REQUIRED,
                        null, verificationRequest);
            }
            if (restRequest != null) {
                return new ExecutionResult(resultList.size(), ExecutionOutcome.REST_REQUIRED, restRequest);
            }
            if ("user_cancelled".equals(stopReason)) {
                return new ExecutionResult(resultList.size(), ExecutionOutcome.USER_CANCELLED, null);
            }

            long duration = System.currentTimeMillis() - startTime;
            String message = config.isAiEnabled()
                    ? String.format("51job AI处理完成，投递%d个，打招呼%d个，跳过%d个，失败%d个，用时%s",
                    aiDeliveredCount, greetingSentCount, greetingSkippedCount, greetingFailedCount,
                    formatDuration(duration))
                    : String.format("51job投递完成，共投递%d个简历，用时%s",
                    resultList.size(), formatDuration(duration));
            sendProgress(message, null, null);
            return new ExecutionResult(resultList.size(), ExecutionOutcome.COMPLETED, null);

        } catch (Exception e) {
            executionError = messageOf(e, "投递过程异常");
            log.error("51job投递过程出现异常", e);
            sendProgress("投递出现异常: " + e.getMessage(), null, null);
            return new ExecutionResult(resultList.size(), ExecutionOutcome.FAILED, null);
        }
    }

    private void executeAiDelivery() {
        int maxPerRun = config.effectiveMaxPerRun();
        for (int keywordIndex = resumeKeywordIndex; keywordIndex < config.getKeywords().size(); keywordIndex++) {
            String keyword = config.getKeywords().get(keywordIndex);
            currentKeywordIndex = keywordIndex;
            currentKeyword = keyword;
            if (shouldStop() || hasReachedMaxPerRun() || detailAccessBlocked) break;
            deliverAiByKeyword(keyword, buildSearchUrl(keyword), maxPerRun, keywordIndex);
            if (restRequest != null) break;
        }
        if (shouldStop()) {
            stopReason = "user_cancelled";
            sendProgress("用户取消 AI 打招呼任务", null, null);
        } else if (hasReachedMaxPerRun()) {
            handleMaxPerRunReached();
        }
    }

    /** 清除本轮休息信号，并把下一次执行定位到触发休息的关键词和页面。 */
    public void prepareForResume(RestRequest request) {
        if (request == null) {
            return;
        }
        restRequest = null;
        detailAccessBlocked = false;
        reachedDailyLimit = false;
        resumeKeywordIndex = Math.max(0, request.keywordIndex());
        resumePage = Math.max(1, request.page());
        currentKeywordIndex = resumeKeywordIndex;
        currentKeyword = request.keyword();
        currentPageNum = resumePage;
        successfulProgressSinceRest = false;
    }

    /** 验证通过后回到触发验证的关键词和页面，重新读取搜索快照并重试当前岗位。 */
    public void prepareForVerificationResume(VerificationRequest request) {
        if (request == null) return;
        verificationRequest = null;
        detailAccessBlocked = false;
        resumeKeywordIndex = Math.max(0, request.keywordIndex());
        resumePage = Math.max(1, request.page());
        currentKeywordIndex = resumeKeywordIndex;
        currentKeyword = request.keyword();
        currentPageNum = resumePage;
    }

    /** 验证超时后跳过当前岗位；智能休息开启时沿用既有休息调度。 */
    public void prepareForVerificationTimeout(VerificationRequest request) {
        if (request == null) return;
        verificationSkippedJobIds.add(request.jobId());
        verificationRequest = null;
        detailAccessBlocked = false;
        resumeKeywordIndex = Math.max(0, request.keywordIndex());
        resumePage = Math.max(1, request.page());
        currentKeywordIndex = resumeKeywordIndex;
        currentKeyword = request.keyword();
        currentPageNum = resumePage;
        if (config != null && config.isRestEnabled()) {
            restRequest = new RestRequest(
                    "verification_timeout",
                    "访问验证等待超时，跳过当前岗位后进入间歇休息",
                    request.keyword(),
                    request.keywordIndex(),
                    request.page()
            );
        }
    }

    private void requestRest(String reason, String message) {
        if (config == null || restRequest != null
                || (!config.isRestEnabled() && !"max_per_run".equals(reason))) {
            return;
        }
        restRequest = new RestRequest(
                reason,
                message,
                currentKeyword,
                currentKeywordIndex,
                Math.max(1, currentPageNum)
        );
        sendProgress(message, null, null);
    }

    private boolean hasReachedMaxPerRun() {
        if (config == null || config.effectiveMaxPerRun() == 0) return false;
        return resultList.size() >= config.effectiveMaxPerRun();
    }

    private int remainingMaxPerRun() {
        int max = config == null ? 0 : config.effectiveMaxPerRun();
        return max == 0 ? Integer.MAX_VALUE : Math.max(0, max - resultList.size());
    }

    private void handleMaxPerRunReached() {
        if (!hasReachedMaxPerRun()) return;
        int max = config.effectiveMaxPerRun();
        if (config.isStopAfterMaxPerRun()) {
            sendProgress(String.format("已达到本轮成功投递上限%d个，按配置停止", max), null, null);
            return;
        }
        requestRest("max_per_run", String.format("已达到本轮成功投递上限%d个，进入短间歇休息", max));
    }

    private void markDailyLimitRest() {
        reachedDailyLimit = true;
        stopReason = "daily_limit";
        requestRest("daily_limit", "检测到日投递上限，进入跨日休息");
    }

    /**
     * 按关键词投递
     */
    private void deliverByKeyword(String keyword, String searchUrl, int keywordIndex) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensurePageReady();
                deliverByKeywordInternal(keyword, searchUrl, keywordIndex);
                return;
            } catch (RuntimeException e) {
                if (!isTargetClosedFailure(e) || pageRecovery == null || attempt == 1) {
                    throw e;
                }
                log.warn("51job Page 已失效，执行第{}次页面恢复后重试关键词", attempt + 1);
                page = pageRecovery.get();
                networkHooked = false;
                synchronized (currentPageJobIds) {
                    currentPageJobIds.clear();
                }
            }
        }
    }

    private void deliverByKeywordInternal(String keyword, String searchUrl, int keywordIndex) {
        try {
            // 收敛日志：不输出关键词级日志，仅保留页级摘要

            // 在跳转前监听搜索接口，AI与普通投递共用同一套快照解析。
            if (!networkHooked) {
                try {
                    page.onResponse(this::captureSearchResponse);
                    networkHooked = true;
                } catch (Throwable e) {
                    log.debug("51job搜索响应监听注册失败: {}", e.getMessage());
                }
            }

            // 导航到搜索页面
            try {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
                headers.put("Accept-Language", "zh-CN,zh;q=0.9");
                headers.put("Sec-Fetch-Site", "same-site");
                headers.put("Sec-Fetch-Mode", "navigate");
                page.setExtraHTTPHeaders(headers);
            } catch (Throwable ignored) {}
            page.navigate(searchUrl);
            PlaywrightUtil.sleep(1);

            // 检查是否需要登录
            if (checkNeedLogin()) {
                sendProgress("需要重新登录，跳过关键词: " + keyword, null, null);
                return;
            }

            // 点击排序选项（选择第一个排序方式）
            try {
                Locator sortOptions = page.locator("div.ss");
                if (sortOptions.count() > 0) {
                    sortOptions.first().click();
                    PlaywrightUtil.sleep(1);
                }
            } catch (Exception e) { /* 静默 */ }

            // 遍历页面投递
            int firstPage = keywordIndex == resumeKeywordIndex ? Math.max(1, resumePage) : 1;
            for (int pageNum = firstPage; pageNum <= DEFAULT_MAX_PAGE; pageNum++) {
                currentKeywordIndex = keywordIndex;
                currentKeyword = keyword;
                if (shouldStop()) {
                    stopReason = "user_cancelled";
                    sendProgress("用户取消投递", null, null);
                    return;
                }

                sendProgress(String.format("正在投递第%d页", pageNum), pageNum, DEFAULT_MAX_PAGE);
                currentPageNum = pageNum;

                // 跳转到指定页码
                synchronized (currentPageJobIds) {
                    currentPageJobIds.clear();
                }
                if (pageNum > 1 && !jumpToPage(pageNum)) {
                    if (checkAccessVerification()) {
                        requestRest("access_verification", "访问验证仍在页面上，进入间歇休息");
                    }
                    break;
                }

                PlaywrightUtil.sleep(2);

                // 检查是否出现访问验证
                if (checkAccessVerification()) {
                    requestRest("access_verification", "出现访问验证，进入间歇休息");
                    return;
                }

                // 检测“无职位”文案，提前结束当前关键词
                try {
                    if (detectNoJobs51job()) {
                        sendProgress("该关键词暂无职位，提前结束", null, null);
                        break;
                    }
                } catch (Exception ignored) {}

                // 投递当前页面的所有职位
                deliverCurrentPage();
                if (restRequest != null) return;
                if (reachedDailyLimit) break;
                if (hasReachedMaxPerRun()) {
                    handleMaxPerRunReached();
                    return;
                }

                PlaywrightUtil.sleep(3);
            }

            // 关键词完成不输出日志
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("51job关键词投递失败: " + e.getMessage(), e);
        }
    }

    private void deliverAiByKeyword(String keyword, String searchUrl, int maxPerRun, int keywordIndex) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensurePageReady();
                deliverAiByKeywordInternal(keyword, searchUrl, maxPerRun, keywordIndex);
                return;
            } catch (RuntimeException e) {
                if (!isTargetClosedFailure(e) || pageRecovery == null || attempt == 1) {
                    throw e;
                }
                log.warn("51job AI模式页面失效，执行第{}次页面恢复后重试关键词", attempt + 1);
                page = pageRecovery.get();
                networkHooked = false;
                synchronized (currentPageJobIds) {
                    currentPageJobIds.clear();
                }
            }
        }
    }

    private void ensureSearchResponseHook() {
        if (networkHooked) return;
        try {
            page.onResponse(this::captureSearchResponse);
            networkHooked = true;
        } catch (Exception e) {
            log.debug("51job搜索响应监听注册失败: {}", e.getMessage());
        }
    }

    private void captureSearchResponse(Response response) {
        try {
            String url = response.url();
            if (url == null || !url.contains("/api/job/search-pc")
                    || !"GET".equalsIgnoreCase(response.request().method())) return;

            int status = response.status();

            String text = null;
            try {
                text = response.text();
            } catch (Throwable ignored) {
            }
            if (status < 200 || status >= 300) {
                readRequestStatus = ReadRequestStatus.browser(false,
                        "51job浏览器搜索响应状态异常: " + status);
                log.warn("[51job] 搜索接口响应异常 status={}, bodyLength={}", status,
                        text == null ? 0 : text.length());
                return;
            }
            if (text == null || text.isBlank()) {
                readRequestStatus = ReadRequestStatus.browser(false, "51job浏览器搜索响应为空");
                log.warn("[51job] 搜索接口响应为空 status={}", status);
                return;
            }

            if (!looksLikeJson(text, response.headers())) {
                readRequestStatus = ReadRequestStatus.browser(false,
                        "51job浏览器搜索响应不是可解析JSON");
                log.warn("[51job] 搜索接口响应不是可解析 JSON，contentType={}, bodyLength={}",
                        response.headers().get("content-type"), text.length());
                return;
            }

            String requestId = null;
            try {
                java.net.URI uri = new java.net.URI(url);
                String query = uri.getQuery();
                if (query != null) {
                    for (String part : query.split("&")) {
                        int separator = part.indexOf('=');
                        if (separator > 0 && "requestId".equals(part.substring(0, separator))) {
                            requestId = java.net.URLDecoder.decode(part.substring(separator + 1), java.nio.charset.StandardCharsets.UTF_8);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            if (requestId != null && !requestId.isBlank() && processedRequestIds.contains(requestId)) return;

            job51Service.parseAndPersistJob51SearchJson(text);
            List<Long> jobIds = extractJobIdsFromJson(text);
            if (!jobIds.isEmpty()) {
                synchronized (currentPageJobIds) {
                    currentPageJobIds.clear();
                    currentPageJobIds.addAll(jobIds);
                }
            }
            log.info("[51job] 搜索快照解析完成 jobIds={}, bodyLength={}",
                    jobIds.size(), text.length());
            readRequestStatus = ReadRequestStatus.browser(false, "51job浏览器搜索响应读取成功");
            if (requestId != null && !requestId.isBlank()) processedRequestIds.add(requestId);
        } catch (Throwable e) {
            readRequestStatus = ReadRequestStatus.browser(false,
                    "51job浏览器搜索响应解析失败: " + messageOf(e, "未知错误"));
            log.debug("51job搜索响应解析失败: {}", e.getMessage());
        }
    }

    static boolean looksLikeJson(String text, java.util.Map<String, String> headers) {
        if (text == null || text.isBlank()) return false;
        try {
            String contentType = headers == null ? null
                    : headers.getOrDefault("content-type", headers.get("Content-Type"));
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) return true;
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void deliverAiByKeywordInternal(String keyword, String searchUrl, int maxPerRun, int keywordIndex) {
        ensureSearchResponseHook();
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            headers.put("Sec-Fetch-Site", "same-site");
            headers.put("Sec-Fetch-Mode", "navigate");
            page.setExtraHTTPHeaders(headers);
        } catch (Exception ignored) {
        }

        page.navigate(searchUrl);
        PlaywrightUtil.sleep(1);
        if (checkNeedLogin()) {
            sendProgress("需要重新登录，跳过 AI 关键词: " + keyword, null, null);
            return;
        }

        int firstPage = keywordIndex == resumeKeywordIndex ? Math.max(1, resumePage) : 1;
        for (int pageNum = firstPage; pageNum <= DEFAULT_MAX_PAGE; pageNum++) {
            if (shouldStop() || hasReachedMaxPerRun()) return;

            currentKeywordIndex = keywordIndex;
            currentKeyword = keyword;
            currentPageNum = pageNum;
            sendProgress(String.format("AI正在处理第%d页", pageNum), pageNum, DEFAULT_MAX_PAGE);
            synchronized (currentPageJobIds) {
                currentPageJobIds.clear();
            }
            if (pageNum > 1 && !jumpToPage(pageNum)) {
                if (checkAccessVerification()) {
                    requestRest("access_verification", "访问验证仍在页面上，进入 AI 间歇休息");
                }
                return;
            }

            PlaywrightUtil.sleep(2);
            if (checkAccessVerification()) {
                requestRest("access_verification", "出现访问验证，进入 AI 间歇休息");
                return;
            }
            if (detectNoJobs51job()) return;

            int processed = deliverAiCurrentPage(keyword, maxPerRun);
            if (verificationRequest != null) {
                return;
            }
            if (detailAccessBlocked) {
                requestRest("detail_blocked", "详情页出现访问验证，进入间歇休息");
                return;
            }
            if (restRequest != null) return;
            if (processed == 0) {
                if (page.locator("div.ick").count() == 0) return;
                sendProgress("当前页没有可处理的岗位，继续下一页", null, null);
            }
        }
    }

    private int deliverAiCurrentPage(String keyword, int maxPerRun) {
        Locator cards = findJobCards();
        int cardCount = cards.count();
        if (cardCount == 0) return 0;

        List<Long> pageJobIds = snapshotCurrentPageJobIds();
        List<Long> domJobIds = collectJobIdsOnPage();
        if (domJobIds.size() == cardCount || pageJobIds.isEmpty()) {
            pageJobIds = domJobIds;
        }
        if (pageJobIds.isEmpty()) {
            requestRest("empty_job_ids", "当前页岗位标识读取异常，进入间歇休息");
            return 0;
        }

        int processed = 0;
        for (int i = 0; i < cardCount && !hasReachedMaxPerRun(); i++) {
            if (shouldStop()) {
                stopReason = "user_cancelled";
                return processed;
            }

            Locator card = cards.nth(i);
            if (isAlreadyAppliedCard(card)) continue;

            Long jobId = resolveJobIdForCard(readJobIdFromCard(card), i, pageJobIds);
            if (jobId == null || verificationSkippedJobIds.contains(jobId)) continue;
            String title = firstText(card, ".jname", "[class*='jname']");
            String company = firstText(card, ".cname", "[class*='cname']");
            String cardLink = readRealJobLinkFromCard(card, jobId);
            Job51Entity entity = job51Service.findByJobId(jobId);
            if (entity == null) {
                Job51Entity snapshot = buildSearchCardSnapshot(jobId, title, company, cardLink);
                job51Service.batchInsertIfNotExists(List.of(snapshot));
                entity = job51Service.findByJobId(jobId);
            }
            if (title == null || title.isBlank()) {
                title = entity == null ? "" : entity.getJobTitle();
            }
            if (company == null || company.isBlank()) {
                company = entity == null ? "" : entity.getCompName();
            }
            if (entity == null) {
                greetingSkippedCount++;
                sendProgress(String.format("跳过岗位%s：岗位快照未持久化", title), null, null);
                continue;
            }
            if (jobFunnelService != null) jobFunnelService.collected("51job", jobId);
            if (Job51Service.isExternalApplicationRoute(cardLink)) {
                entity.setApplicationRoute(Job51Service.APPLICATION_ROUTE_EXTERNAL);
                entity.setApplicationUrl(Job51Service.normalizeApplicationUrl(cardLink));
                entity.setExternalApplyStatus(Job51Service.externalApplyStatus(entity));
                job51Service.markExternalApplicationPending(jobId, cardLink);
                sendProgress(String.format("待外部申请：%s | %s", company, title), null, null);
                continue;
            }
            if (isBetterDetailLink(entity.getJobLink(), cardLink)) {
                entity.setJobLink(cardLink);
                job51Service.updateJobLink(jobId, cardLink);
                log.info("[51job] 使用搜索卡片真实详情链接 job_id={} link={}", jobId, cardLink);
            }
            if (Job51Service.GREETING_SENT.equalsIgnoreCase(entity.getGreetingStatus())) continue;

            processed++;
            aiCandidateCount++;
            AiJobOutcome outcome = processAiJob(keyword, entity, company, title, card, i);
            if (outcome == AiJobOutcome.SENT) {
                greetingSentCount++;
                successfulProgressSinceRest = true;
                resultList.add(company + " | " + title);
                sendProgress(String.format("已发送 AI 打招呼：%s | %s", company, title), null, null);
            } else if (outcome == AiJobOutcome.DELIVERED) {
                aiDeliveredCount++;
                successfulProgressSinceRest = true;
                resultList.add(company + " | " + title);
                sendProgress(String.format("已投递 AI 筛选岗位：%s | %s", company, title), null, null);
            } else if (outcome == AiJobOutcome.SKIPPED) {
                greetingSkippedCount++;
            } else if (outcome == AiJobOutcome.DETAIL_BLOCKED) {
                greetingSkippedCount++;
                return processed;
            } else if (outcome == AiJobOutcome.VERIFICATION_REQUIRED) {
                return processed;
            } else {
                greetingFailedCount++;
            }

            if (!hasReachedMaxPerRun() && i + 1 < cardCount) {
                waitBetweenAiJobs();
            }
        }
        return processed;
    }

    private AiJobOutcome processAiJob(String keyword, Job51Entity entity, String company, String title,
                                      Locator searchCard, int cardIndex) {
        Page detailPage = null;
        try {
            String link = normalizeJobLink(entity.getJobLink());
            if (link == null) {
                link = generatedDetailLink(entity.getJobId());
                if (link != null) {
                    entity.setJobLink(link);
                    job51Service.updateJobLink(entity.getJobId(), link);
                }
            }
            if (link == null) {
                return markReview(entity.getJobId(), title, "job_link_missing");
            }
            funnelMark("detail_link", entity.getJobId());
            if (Job51Service.isExternalApplication(entity) || isExternalApplicationRoute(link)) {
                job51Service.markExternalApplicationPending(entity.getJobId(), link);
                funnelMark("external_pending", entity.getJobId());
                sendProgress(String.format("待外部申请：%s", title), null, null);
                return AiJobOutcome.SKIPPED;
            }

            JobDescriptionResult description = resolveJobDescription(null, entity.getJobDescription());
            AiGreetingResult aiResult = null;
            if (description.usable()) {
                funnelMark("jd", entity.getJobId());
                aiProcessedCount++;
                LiepinAiBatchAssessment.Item assessment = screenAiJob(keyword, entity, description.text());
                if (assessment == null) return markRetryable(entity.getJobId(), title, "ai_screening_request_failed");
                funnelMark("ai_valid", entity.getJobId());
                if (!passesAiScreening(assessment, config.effectiveAiMinScore())) {
                    return skipAiScreening(entity.getJobId(), title, assessment);
                }
                funnelMark("ai_pass", entity.getJobId());
                sendProgress(String.format("AI JD分析通过：%s（%d分）", title, assessment.score()), null, null);
                aiResult = generateAiGreeting(keyword, title, description.text());
                if (!isUsableAiGreeting(aiResult)) {
                    return skipInvalidAiGreeting(entity.getJobId(), title, aiResult);
                }
            }

            detailPage = page.context().newPage();
            detailPage.setDefaultTimeout(10000);
            if (!navigateDetailPage(detailPage, entity.getJobId(), link)) {
                boolean accessBlocked = checkAccessVerification(detailPage) || checkNeedLogin(detailPage);
                if (description.usable()) {
                    String fallbackMessage = "详情页未就绪，使用已筛选的搜索快照 JD 尝试搜索页投递";
                    sendProgress(String.format("%s：%s", fallbackMessage, title), null, null);
                    if (applySingleJobFromSearchPage(searchCard, cardIndex, entity.getJobId(), company, title)) {
                        job51Service.updateGreetingState(entity.getJobId(), Job51Service.GREETING_SKIPPED,
                                "apply_only_no_chat");
                        funnelMark("formal_apply_success", entity.getJobId());
                        return AiJobOutcome.DELIVERED;
                    }
                }
                if (accessBlocked) {
                    verificationRequest = new VerificationRequest(
                            entity.getJobId(), link, keyword, currentKeywordIndex,
                            Math.max(1, currentPageNum), cardIndex, company, title);
                    return AiJobOutcome.VERIFICATION_REQUIRED;
                }
                return markReview(entity.getJobId(), title, "detail_navigation_failed");
            }

            if (!description.usable()) {
                description = resolveJobDescription(detailPage, entity.getJobDescription());
                if (!description.usable()) {
                    return markReview(entity.getJobId(), title, description.reason());
                }
                String jd = description.text();
                funnelMark("jd", entity.getJobId());
                if (!jd.equals(normalizeJobDescription(entity.getJobDescription()))) {
                    entity.setJobDescription(jd);
                    job51Service.updateJobDescription(entity.getJobId(), jd);
                }

                aiProcessedCount++;
                LiepinAiBatchAssessment.Item assessment = screenAiJob(keyword, entity, jd);
                if (assessment == null) return markRetryable(entity.getJobId(), title, "ai_screening_request_failed");
                funnelMark("ai_valid", entity.getJobId());
                if (!passesAiScreening(assessment, config.effectiveAiMinScore())) {
                    return skipAiScreening(entity.getJobId(), title, assessment);
                }
                funnelMark("ai_pass", entity.getJobId());
                aiResult = generateAiGreeting(keyword, title, jd);
                if (!isUsableAiGreeting(aiResult)) {
                    return skipInvalidAiGreeting(entity.getJobId(), title, aiResult);
                }
            }

            Locator greeting = findVisibleTextAction(detailPage, Job51Locators.GREETING_TEXTS);
            if (greeting != null) {
                funnelMark("button_visible", entity.getJobId());
                if (sendGreetingInDetail(detailPage, aiResult == null ? null : aiResult.message())) {
                    job51Service.updateGreetingState(entity.getJobId(), Job51Service.GREETING_SENT, null);
                    funnelMark("chat_success", entity.getJobId());
                    return AiJobOutcome.SENT;
                }
                return markRetryable(entity.getJobId(), title, "chat_not_confirmed");
            }

            if (applyJobInDetail(detailPage, entity.getJobId())) {
                job51Service.updateGreetingState(entity.getJobId(), Job51Service.GREETING_SKIPPED,
                        "apply_only_no_chat");
                funnelMark("formal_apply_success", entity.getJobId());
                return AiJobOutcome.DELIVERED;
            }
            return markReview(entity.getJobId(), title, "message_and_apply_not_confirmed");
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) throw propagate(e);
            log.warn("51job AI岗位处理失败 job_id={} title={} reason={}",
                    entity.getJobId(), title, messageOf(e, "unknown"));
            return markRetryable(entity.getJobId(), title, "job_processing_error");
        } finally {
            if (detailPage != null) {
                try {
                    detailPage.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private LiepinAiBatchAssessment.Item screenAiJob(String keyword, Job51Entity entity, String jd) {
        try {
            AiEntity aiConfig = aiService.getAiConfig();
            String introduce = aiConfig == null || aiConfig.getIntroduce() == null ? "" : aiConfig.getIntroduce();
            JSONObject job = new JSONObject();
            job.put("jobId", entity.getJobId());
            job.put("title", valueOrEmpty(entity.getJobTitle()));
            job.put("company", valueOrEmpty(entity.getCompName()));
            job.put("salary", valueOrEmpty(entity.getJobSalaryText()));
            job.put("area", valueOrEmpty(entity.getJobArea()));
            job.put("education", valueOrEmpty(entity.getJobEduReq()));
            job.put("experience", valueOrEmpty(entity.getJobExpReq()));
            job.put("jd", valueOrEmpty(jd));

            Map<String, String> values = new HashMap<>();
            values.put("candidate", introduce);
            values.put("keyword", valueOrEmpty(keyword));
            values.put("min_score", String.valueOf(config.effectiveAiMinScore()));
            values.put("jobs", "[" + job + "]");
            String screenPrompt = aiConfig == null ? null : aiConfig.getScreenPrompt();
            String jdAnalysisPrompt = aiConfig == null ? null : aiConfig.getJdAnalysisPrompt();
            String raw = aiService.sendStructuredRequest(
                    aiService.renderScreeningPrompt(screenPrompt, jdAnalysisPrompt, values), 0.1, 256);
            LiepinAiBatchAssessment.Result parsed = LiepinAiBatchAssessment.parse(raw);
            if (parsed == null) return null;
            String jobId = String.valueOf(entity.getJobId());
            return parsed.items().stream()
                    .filter(item -> jobId.equals(item.jobId()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[51job] AI JD分析失败 job_id={} title={} reason={}",
                    entity.getJobId(), entity.getJobTitle(), messageOf(e, "unknown"));
            return null;
        }
    }

    static boolean passesAiScreening(LiepinAiBatchAssessment.Item assessment, int minimumScore) {
        if (assessment == null || assessment.score() < Math.max(0, Math.min(100, minimumScore))) {
            return false;
        }
        return "PASS".equals(assessment.decision())
                && !assessment.reasonCodes().contains("HARD_MISMATCH")
                && !assessment.reason().contains("HARD_MISMATCH");
    }

    private AiJobOutcome skipAiScreening(Long jobId, String title,
                                         LiepinAiBatchAssessment.Item assessment) {
        if (assessment == null) return markRetryable(jobId, title, "ai_screening_request_failed");
        String reason = "ai_screening_" + assessment.decision().toLowerCase(Locale.ROOT);
        String detail = assessment.score() + "分，" + assessment.reason();
        if ("REVIEW".equalsIgnoreCase(assessment.decision())) {
            return markReview(jobId, title, detail);
        }
        job51Service.markScreeningStatus(jobId, "SKIPPED", detail);
        markGreetingSkipped(jobId, reason);
        sendProgress(String.format("AI JD分析跳过：%s（%s）", title, detail), null, null);
        return AiJobOutcome.SKIPPED;
    }

    private AiJobOutcome markReview(Long jobId, String title, String reason) {
        reviewCount++;
        job51Service.markScreeningStatus(jobId, "REVIEW", reason);
        sendProgress(String.format("待人工复核：%s（%s）", title, reason), null, null);
        return AiJobOutcome.SKIPPED;
    }

    private AiJobOutcome markRetryable(Long jobId, String title, String reason) {
        retryableCount++;
        job51Service.markScreeningStatus(jobId, "AI_FAILURE_RETRYABLE", reason);
        sendProgress(String.format("AI失败待重试：%s（%s）", title, reason), null, null);
        return AiJobOutcome.SKIPPED;
    }

    private void funnelMark(String stage, Long jobId) {
        if (jobFunnelService == null || jobId == null) return;
        jobFunnelService.mark("51job", jobId, stage);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private void markGreetingSkipped(Long jobId, String reason) {
        job51Service.updateGreetingState(jobId, Job51Service.GREETING_SKIPPED, reason);
    }

    private AiJobOutcome skipInvalidAiGreeting(Long jobId, String title, AiGreetingResult aiResult) {
        String reason = aiResult == null || aiResult.reason() == null
                ? "ai_response_invalid" : aiResult.reason();
        if (aiResult != null && aiResult.rejected()) {
            job51Service.markScreeningStatus(jobId, "SKIPPED", reason);
            markGreetingSkipped(jobId, reason);
            sendProgress(String.format("AI判定不匹配，跳过%s（%s）", title, reason), null, null);
            return AiJobOutcome.SKIPPED;
        }
        return markRetryable(jobId, title, reason);
    }

    private void waitBetweenAiJobs() {
        int min = config.effectiveMinDelaySeconds();
        int max = config.effectiveMaxDelaySeconds();
        int seconds = ThreadLocalRandom.current().nextInt(min, max + 1);
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline && !shouldStop()) {
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(250L, Math.max(1L, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopReason = "user_cancelled";
                return;
            }
        }
    }

    private Locator findJobCards() {
        for (String selector : Job51Locators.JOB_CARD_SELECTORS) {
            Locator candidate = page.locator(selector);
            if (candidate.count() > 0) return candidate;
        }
        return page.locator("div.ick");
    }

    private boolean isAlreadyAppliedCard(Locator card) {
        try {
            String text = card.innerText();
            return hasAppliedJobMarker(text);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean hasAppliedJobMarker(String text) {
        if (text == null || text.isBlank()) return false;
        return text.contains("已申请") || text.contains("已投递") || text.contains("已投简历");
    }

    private Long readJobIdFromCard(Locator card) {
        String[] attrs = {"data-jobid", "data-job-id", "data-analysis-jobid"};
        for (String attr : attrs) {
            try {
                String value = card.getAttribute(attr);
                if (value != null && !value.isBlank()) return Long.parseLong(value.replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
            }
        }
        try {
            Locator links = card.locator("a[href]");
            for (int i = 0; i < links.count(); i++) {
                Long id = parseJobIdFromHref(links.nth(i).getAttribute("href"));
                if (id != null) return id;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static Long resolveJobIdForCard(Long cardJobId, int cardIndex, List<Long> pageJobIds) {
        if (cardJobId != null) return cardJobId;
        if (pageJobIds == null || cardIndex < 0 || cardIndex >= pageJobIds.size()) return null;
        return pageJobIds.get(cardIndex);
    }

    private Job51Entity buildSearchCardSnapshot(Long jobId, String title, String company, String cardLink) {
        Job51Entity snapshot = new Job51Entity();
        snapshot.setJobId(jobId);
        snapshot.setJobTitle(title == null ? "" : title.trim());
        snapshot.setCompName(company == null ? "" : company.trim());
        String link = cardLink == null || cardLink.isBlank()
                ? generatedDetailLink(jobId) : cardLink.trim();
        snapshot.setJobLink(link);
        if (Job51Service.isExternalApplicationRoute(cardLink)) {
            snapshot.setApplicationRoute(Job51Service.APPLICATION_ROUTE_EXTERNAL);
            snapshot.setApplicationUrl(Job51Service.normalizeApplicationUrl(cardLink));
        } else {
            snapshot.setApplicationRoute(Job51Service.APPLICATION_ROUTE_INTERNAL);
        }
        return snapshot;
    }

    private String firstText(Locator root, String... selectors) {
        for (String selector : selectors) {
            try {
                Locator candidate = root.locator(selector).first();
                if (candidate.count() > 0) {
                    String text = candidate.textContent();
                    if (text != null && !text.isBlank()) return text.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static String generatedDetailLink(Long jobId) {
        return jobId == null ? null : "https://we.51job.com/pc/jobdetail?jobId=" + jobId;
    }

    private String normalizeJobLink(String link) {
        if (link == null || link.isBlank()) return null;
        String value = link.trim();
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("/")) return "https://jobs.51job.com" + value;
        return value.startsWith("http://") || value.startsWith("https://") ? value : null;
    }

    static boolean isExternalApplicationRoute(String link) {
        if (link == null || link.isBlank()) return false;
        try {
            String host = new java.net.URI(link).getHost();
            return host != null && !host.equalsIgnoreCase("jobs.51job.com")
                    && !host.equalsIgnoreCase("we.51job.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String readRealJobLinkFromCard(Locator card, Long jobId) {
        if (card == null || card.count() == 0) return null;
        String fallback = null;
        String externalFallback = null;
        Locator anchors = card.locator("a[href]");
        for (int i = 0; i < anchors.count(); i++) {
            try {
                String href = normalizeSearchCardLink(anchors.nth(i).getAttribute("href"));
                if (Job51Service.isExternalApplicationRoute(href)) {
                    if (externalFallback == null) externalFallback = href;
                    continue;
                }
                if (!isSupportedDetailRoute(href)) continue;
                if (fallback == null) fallback = href;
                Long hrefJobId = parseJobIdFromHref(href);
                if (jobId == null || jobId.equals(hrefJobId)) return href;
            } catch (Exception ignored) {
            }
        }
        return externalFallback == null ? fallback : externalFallback;
    }

    private String normalizeSearchCardLink(String link) {
        if (link == null || link.isBlank()) return null;
        String value = link.trim();
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("/pc/jobdetail")) return "https://we.51job.com" + value;
        if (value.startsWith("/")) return "https://jobs.51job.com" + value;
        return value.startsWith("http://") || value.startsWith("https://") ? value : null;
    }

    private boolean isBetterDetailLink(String current, String candidate) {
        if (!isSupportedDetailRoute(candidate)) return false;
        String normalizedCurrent = normalizeSearchCardLink(current);
        return !isSupportedDetailRoute(normalizedCurrent) || isSyntheticDetailRoute(normalizedCurrent);
    }

    private boolean navigateDetailPage(Page detailPage, Long jobId, String storedLink) {
        installDetailDiagnostics(detailPage, jobId);
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            String referer = safePageUrl(page);
            if (referer != null && referer.startsWith("http")) headers.put("Referer", referer);
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            detailPage.setExtraHTTPHeaders(headers);
        } catch (Exception ignored) {
        }
        for (String link : detailLinkCandidates(jobId, storedLink)) {
            try {
                Response response = detailPage.navigate(link, new Page.NavigateOptions()
                        .setTimeout(DETAIL_TIMEOUT_MS)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                if (response != null && (response.status() < 200 || response.status() >= 300)) {
                    log.warn("[51job] 详情页响应异常 job_id={} status={} link={}",
                            jobId, response.status(), link);
                    continue;
                }
                try {
                    detailPage.waitForLoadState(LoadState.DOMCONTENTLOADED,
                            new Page.WaitForLoadStateOptions().setTimeout(10000));
                } catch (Exception ignored) {
                }
                boolean accessBlocked = checkAccessVerification(detailPage);
                boolean loginRequired = checkNeedLogin(detailPage);
                if (accessBlocked) {
                    log.warn("[51job] 详情页出现访问验证，尝试兼容详情路由 job_id={} link={}", jobId, link);
                    continue;
                }
                if (loginRequired) {
                    log.warn("[51job] 详情页跳转到登录页，尝试备用详情链接 job_id={} link={}", jobId, link);
                    continue;
                }
                if (waitForDetailAction(detailPage, DETAIL_ACTION_TIMEOUT_MS) == DetailAction.NONE) {
                    logDetailDiagnostics(detailPage, jobId);
                    log.warn("[51job] 详情页已打开但没有沟通或投递入口，尝试下一个详情链接 job_id={} url={} link={}",
                            jobId, safePageUrl(detailPage), link);
                    continue;
                }
                return true;
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) throw propagate(e);
                log.warn("[51job] 详情页导航失败 job_id={} link={} reason={}",
                        jobId, link, messageOf(e, "unknown"));
            }
        }
        return false;
    }

    private void installDetailDiagnostics(Page detailPage, Long jobId) {
        detailPage.onPageError(error -> log.warn("[51job] 详情脚本错误 job_id={} url={} error={}",
                jobId, safePageUrl(detailPage), compactLog(error, 400)));
        detailPage.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type())) {
                log.warn("[51job] 详情控制台错误 job_id={} url={} text={}",
                        jobId, safePageUrl(detailPage), compactLog(message.text(), 400));
            }
        });
        detailPage.onRequestFailed(request -> log.warn("[51job] 详情资源失败 job_id={} url={} request={}",
                jobId, safePageUrl(detailPage), compactLog(request.url(), 400)));
        detailPage.onResponse(response -> {
            String responseUrl = response.url();
            String normalizedUrl = responseUrl == null ? "" : responseUrl.toLowerCase(Locale.ROOT);
            if (response.status() >= 400
                    && (normalizedUrl.contains("api") || normalizedUrl.contains("job")
                    || normalizedUrl.contains("resume") || normalizedUrl.contains("detail"))) {
                log.warn("[51job] 详情接口响应异常 job_id={} status={} request={}",
                        jobId, response.status(), compactLog(responseUrl, 500));
            }
        });
    }

    private List<String> detailLinkCandidates(Long jobId, String storedLink) {
        java.util.LinkedHashSet<String> links = new java.util.LinkedHashSet<>();
        String normalized = normalizeJobLink(storedLink);
        if (normalized != null && isSupportedDetailRoute(normalized)
                && !isSyntheticDetailRoute(normalized)) {
            // 搜索接口提供的 jobs.51job.com 链接才是真实详情页，必须优先使用。
            links.add(normalized);
        }
        if (jobId != null) {
            // 公开详情页可能被 WAF 拦截，登录态下的站内详情路由作为第二条路径。
            links.add("https://we.51job.com/pc/jobdetail?jobId=" + jobId);
            // 旧快照可能只有空壳链接，按岗位 ID 使用公开详情路由最后兜底。
            links.add("https://jobs.51job.com/all/" + jobId + ".html");
        }
        return new ArrayList<>(links);
    }

    private boolean isSupportedDetailRoute(String link) {
        try {
            java.net.URI uri = new java.net.URI(link);
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && host.equalsIgnoreCase("jobs.51job.com")
                    && path != null && path.matches("/[^/]+/\\d+\\.html");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isSyntheticDetailRoute(String link) {
        if (link == null || link.isBlank()) return false;
        try {
            java.net.URI uri = new java.net.URI(link);
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && host.equalsIgnoreCase("jobs.51job.com")
                    && path != null && path.matches("/all/\\d+\\.html");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean applySingleJobFromSearchPage(Locator card, int cardIndex, Long jobId,
                                                  String company, String title) {
        if (jobId == null) return false;
        try {
            Locator checkbox = card == null ? page.locator("div.ick").nth(cardIndex)
                    : card.locator("div.ick").first();
            if (checkbox.count() == 0) {
                Locator allCheckboxes = page.locator("div.ick");
                if (cardIndex < 0 || cardIndex >= allCheckboxes.count()) return false;
                checkbox = allCheckboxes.nth(cardIndex);
            }
            if (!isSearchCheckboxSelected(checkbox)) {
                checkbox.evaluate("el => el.click()");
                try { page.waitForTimeout(400); } catch (Exception ignored) {}
            }
            boolean clicked = false;
            if (!reachedDailyLimit) {
                Locator directApply = findCardApplyButton(card);
                if (directApply == null) {
                    directApply = findSearchApplyButton(page);
                }
                if (directApply == null) {
                    directApply = findVisibleTextAction(page, new String[]{"立即投递", "投递", "立即申请", "申请"});
                }
                if (directApply != null) {
                    Locator nestedApply = directApply.locator("button.apply, button[class*='apply']");
                    if (nestedApply.count() > 0) directApply = nestedApply.first();
                    log.info("[51job] 搜索页直接投递入口命中 job_id={} text={} class={}", jobId,
                            compactLog(directApply.innerText(), 80), directApply.getAttribute("class"));
                    directApply.click(new Locator.ClickOptions().setForce(true));
                    clicked = true;
                    PlaywrightUtil.sleep(1);
                    if (detectDailyLimitToast51job()) {
                        markDailyLimitRest();
                        log.warn("[51job] 岗位级投递后命中日投递上限 job_id={}", jobId);
                        return false;
                    }
                }
            }
            if (!clicked && !reachedDailyLimit) {
                try {
                    clicked = clickBatchDeliverButton();
                } catch (Exception e) {
                    log.warn("[51job] 批量投递入口未命中，尝试岗位级入口 job_id={} reason={}",
                            jobId, messageOf(e, "unknown"));
                }
            }
            if (!clicked && !reachedDailyLimit) {
                clicked = clickExactVisibleTextOnPage(new String[]{"一键投递", "批量投递", "立即投递", "投递"});
            }
            if (!clicked) return false;
            PlaywrightUtil.sleep(2);
            if (waitForDeliverySuccess(page, 2500L)) {
                job51Service.markDeliveredBatch(List.of(jobId));
                sendProgress(String.format("详情页受验证影响，已通过搜索页投递：%s | %s", company, title), null, null);
                return true;
            }
            Locator confirm = waitForTextAction(page, Job51Locators.APPLY_CONFIRM_TEXTS, 5000L);
            if (confirm == null) {
                confirm = waitForNormalizedConfirmAction(page, 5000L);
            }
            if (confirm != null) {
                confirm.click();
                if (waitForDeliverySuccess(page, MESSAGE_CONFIRM_TIMEOUT_MS)) {
                    job51Service.markDeliveredBatch(List.of(jobId));
                    sendProgress(String.format("详情页受验证影响，已通过搜索页投递：%s | %s", company, title), null, null);
                    return true;
                }
            }
            List<String> selected = List.of(company + " | " + title);
            int confirmed = handleDeliverySuccessDialog(selected, List.of(jobId));
            handleSeparateDeliveryDialog();
            if (confirmed > 0) {
                sendProgress(String.format("详情页受验证影响，已通过搜索页投递：%s | %s", company, title), null, null);
                return true;
            }
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) throw propagate(e);
            log.warn("[51job] 搜索页单岗位投递回退失败 job_id={} reason={}",
                    jobId, messageOf(e, "unknown"));
        }
        return false;
    }

    private Locator findSearchApplyButton(Page target) {
        Locator candidates = target.locator("button.apply, .br button, button[class*='apply']");
        for (int i = 0; i < candidates.count(); i++) {
            Locator candidate = candidates.nth(i);
            try {
                if (!candidate.isVisible() || !candidate.isEnabled()) continue;
                String text = candidate.innerText();
                String normalized = text == null ? "" : text.replaceAll("\\s+", "").trim();
                if (normalized.equals("投递") || normalized.equals("申请")
                        || normalized.equals("立即投递") || normalized.equals("立即申请")) return candidate;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Locator waitForNormalizedConfirmAction(Page target, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Locator candidates = target.locator(".el-dialog__footer button, .el-message-box__btns button, [role='dialog'] button, [class*='dialog'] button");
            for (int i = 0; i < candidates.count(); i++) {
                Locator candidate = candidates.nth(i);
                try {
                    if (!candidate.isVisible() || !candidate.isEnabled()) continue;
                    String text = candidate.innerText();
                    String normalized = text == null ? "" : text.replaceAll("\\s+", "").trim();
                    if (normalized.equals("立即申请") || normalized.equals("确认投递")
                            || normalized.equals("确认申请") || normalized.equals("确定")
                            || normalized.equals("确认")) return candidate;
                } catch (Exception ignored) {
                }
            }
            try {
                target.waitForTimeout(200);
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) throw propagate(e);
                return null;
            }
        }
        return null;
    }

    private boolean isSearchCheckboxSelected(Locator checkbox) {
        try {
            String classes = checkbox.getAttribute("class");
            String checked = checkbox.getAttribute("aria-checked");
            return (classes != null && (classes.contains("sel-yes") || classes.contains("checked")))
                    || "true".equalsIgnoreCase(checked);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Locator findCardApplyButton(Locator card) {
        if (card == null || card.count() == 0) return null;
        Locator candidates = card.locator("button, a, [role='button'], [class*='apply'], [class*='p_but'], div, span");
        for (int i = 0; i < candidates.count(); i++) {
            Locator candidate = candidates.nth(i);
            try {
                if (!candidate.isVisible() || !candidate.isEnabled()) continue;
                String text = candidate.innerText();
                if (text == null) continue;
                String normalized = text.replaceAll("\\s+", "").trim();
                if (normalized.equals("投递") || normalized.equals("申请")
                        || normalized.equals("立即投递") || normalized.equals("立即申请")) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Locator findExactTextAction(Page target, String[] texts) {
        for (String text : texts) {
            Locator candidates = target.locator("text=" + text);
            for (int i = 0; i < candidates.count(); i++) {
                Locator candidate = candidates.nth(i);
                try {
                    if (!candidate.isVisible() || !candidate.isEnabled()) continue;
                    String value = candidate.innerText();
                    if (value != null && value.replaceAll("\\s+", "").trim().equals(text)) {
                        return candidate;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private boolean clickExactVisibleTextOnPage(String[] texts) {
        try {
            Object result = page.evaluate("texts => {"
                    + "const wanted = new Set(texts);"
                    + "const visible = el => { const s = getComputedStyle(el);"
                    + "return s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0'; };"
                    + "const nodes = Array.from(document.querySelectorAll('*'));"
                    + "const preferred = nodes.filter(el => wanted.has((el.innerText || '').replace(/\\s+/g, '').trim())"
                    + " && visible(el) && /^(BUTTON|A)$/.test(el.tagName));"
                    + "const candidates = preferred.length ? preferred : nodes.filter(el => wanted.has((el.innerText || '').replace(/\\s+/g, '').trim()) && visible(el));"
                    + "if (!candidates.length) return false; candidates[0].click(); return true;"
                    + "}", texts);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.debug("[51job] JS精确文字点击失败: {}", messageOf(e, "unknown"));
            return false;
        }
    }

    private Locator waitForGreetingAction(Page target, long timeoutMs) {
        return waitForTextAction(target, Job51Locators.GREETING_TEXTS, timeoutMs);
    }

    private Locator waitForTextAction(Page target, String[] texts, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Locator action = findVisibleTextAction(target, texts);
            if (action != null) return action;
            try {
                target.waitForTimeout(200);
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) throw propagate(e);
                return null;
            }
        }
        return null;
    }

    private DetailAction waitForDetailAction(Page target, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (findVisibleTextAction(target, Job51Locators.GREETING_TEXTS) != null) {
                return DetailAction.GREETING;
            }
            if (findVisibleTextAction(target, Job51Locators.APPLY_TEXTS) != null) {
                return DetailAction.APPLY;
            }
            try {
                target.waitForTimeout(200);
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) throw propagate(e);
                return DetailAction.NONE;
            }
        }
        return DetailAction.NONE;
    }

    private void logDetailDiagnostics(Page target, Long jobId) {
        try {
            String title = target.title();
            String body = target.locator("body").innerText();
            String controls = String.join(" | ", target.locator("button, a, [role='button']").allInnerTexts());
            log.warn("[51job] 详情诊断 job_id={} url={} title={} body={} controls={}",
                    jobId, safePageUrl(target), compactLog(title, 160), compactLog(body, 600), compactLog(controls, 600));
        } catch (Exception e) {
            log.warn("[51job] 详情诊断读取失败 job_id={} url={} reason={}",
                    jobId, safePageUrl(target), messageOf(e, "unknown"));
        }
    }

    private String compactLog(String value, int limit) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= limit ? compact : compact.substring(0, limit) + "...";
    }

    private boolean applyJobInDetail(Page detailPage, Long jobId) {
        Locator apply = waitForTextAction(detailPage, Job51Locators.APPLY_TEXTS, 3000L);
        if (apply == null) {
            log.warn("[51job] 详情页没有投递入口 job_id={} url={}", jobId, safePageUrl(detailPage));
            return false;
        }
        try {
            apply.click();
            if (waitForDeliverySuccess(detailPage, 2500L)) {
                job51Service.markDeliveredBatch(List.of(jobId));
                return true;
            }

            Locator confirm = waitForTextAction(detailPage, Job51Locators.APPLY_CONFIRM_TEXTS, 8000L);
            if (confirm == null) {
                log.warn("[51job] 投递简历弹窗没有确认入口 job_id={} url={}", jobId, safePageUrl(detailPage));
                return false;
            }
            confirm.click();
            if (!waitForDeliverySuccess(detailPage, MESSAGE_CONFIRM_TIMEOUT_MS)) {
                log.warn("[51job] 单岗位投递成功未确认 job_id={} url={}", jobId, safePageUrl(detailPage));
                return false;
            }
            job51Service.markDeliveredBatch(List.of(jobId));
            return true;
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) throw propagate(e);
            log.warn("[51job] 单岗位投递异常 job_id={} reason={}", jobId, messageOf(e, "unknown"));
            return false;
        }
    }

    private boolean waitForDeliverySuccess(Page target, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Locator success = target.getByText("投递成功", new Page.GetByTextOptions().setExact(true));
                if (success.count() > 0 && success.first().isVisible()) return true;
                String body = target.locator("body").innerText();
                if (body != null && body.contains("投递成功")) return true;
                if (checkNeedLogin(target) || checkAccessVerification(target)) return false;
                target.waitForTimeout(250);
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) throw propagate(e);
                return false;
            }
        }
        return false;
    }

    private JobDescriptionResult resolveJobDescription(Page detailPage, String cached) {
        String cachedText = normalizeJobDescription(cached);
        if (isUsableJobDescription(cachedText)) {
            return new JobDescriptionResult(cachedText, "cache", "ok");
        }
        if (detailPage == null) {
            return new JobDescriptionResult(null, "none", "jd_missing");
        }

        long deadline = System.currentTimeMillis() + JD_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String dom = extractDescriptionFromDom(detailPage);
            if (isUsableJobDescription(dom)) {
                return new JobDescriptionResult(dom, "dom", "ok");
            }
            try {
                detailPage.waitForTimeout(250);
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) throw propagate(e);
                break;
            }
        }

        String embedded = extractEmbeddedJobDescription(detailPage);
        if (isUsableJobDescription(embedded)) {
            return new JobDescriptionResult(embedded, "embedded_json", "ok");
        }

        String body = extractMarkedDescription(detailPage);
        if (isUsableJobDescription(body)) {
            return new JobDescriptionResult(body, "body_section", "ok");
        }

        // Last resort: copy the rendered page text, remove UI noise, and keep only job content.
        String cleanedPage = JobDescriptionExtractor.extractFromPage(detailPage);
        if (isUsableJobDescription(cleanedPage)) {
            return new JobDescriptionResult(cleanedPage, "page_cleaned", "ok");
        }
        return new JobDescriptionResult(null, "none", "jd_missing");
    }

    private String extractDescriptionFromDom(Page detailPage) {
        for (String selector : Job51Locators.DESCRIPTION_SELECTORS) {
            Locator candidates = detailPage.locator(selector);
            for (int i = 0; i < Math.min(candidates.count(), 5); i++) {
                try {
                    Locator candidate = candidates.nth(i);
                    if (!candidate.isVisible()) continue;
                    String text = normalizeJobDescription(candidate.innerText());
                    if (isUsableJobDescription(text)) return text;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private String extractEmbeddedJobDescription(Page detailPage) {
        try {
            Locator scripts = detailPage.locator(
                    "script[type='application/ld+json'], script#__NEXT_DATA__, script[type='application/json']");
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (int i = 0; i < Math.min(scripts.count(), 30); i++) {
                String content = scripts.nth(i).textContent();
                if (content == null || content.isBlank()) continue;
                try {
                    String result = findEmbeddedDescription(mapper.readTree(content));
                    if (isUsableJobDescription(result)) return result;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String findEmbeddedDescription(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT).replace("_", "");
                com.fasterxml.jackson.databind.JsonNode value = field.getValue();
                if (isDescriptionKey(key) && value.isTextual()) {
                    String result = normalizeJobDescription(value.asText());
                    if (isUsableJobDescription(result)) return result;
                }
                String nested = findEmbeddedDescription(value);
                if (isUsableJobDescription(nested)) return nested;
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                String nested = findEmbeddedDescription(child);
                if (isUsableJobDescription(nested)) return nested;
            }
        }
        return null;
    }

    private boolean isDescriptionKey(String key) {
        return key.equals("jobdescription") || key.equals("jobdesc")
                || key.equals("postdescription") || key.equals("jobintro")
                || key.equals("jobduty") || key.equals("jobrequirement")
                || key.equals("requirement") || key.equals("responsibility");
    }

    private String extractMarkedDescription(Page detailPage) {
        try {
            return JobDescriptionExtractor.extractRelevantText(detailPage.locator("body").innerText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isUsableJobDescription(String text) {
        return JobDescriptionExtractor.isUsable(text);
    }

    private String normalizeJobDescription(String text) {
        return JobDescriptionExtractor.normalize(text);
    }

    private AiGreetingResult generateAiGreeting(String keyword, String jobName, String jd) {
        try {
            AiEntity aiConfig = aiService.getAiConfig();
            String introduce = aiConfig == null || aiConfig.getIntroduce() == null ? "" : aiConfig.getIntroduce();
            String prompt = aiConfig == null ? null : aiConfig.getPrompt();
            if (prompt == null || prompt.isBlank()) {
                return new AiGreetingResult(null, false, "ai_prompt_missing", 0);
            }
            String request = aiService.renderPrompt(prompt, introduce, keyword, jobName, jd, "");
            String repairRequest = request + "\n上次输出未通过格式校验，请只返回一条单行最终话术：必须以指定前缀开头，排除姓名和命名表达，长度不超过60字。";
            String lastReason = "ai_response_invalid";
            int attemptsUsed = 0;
            for (int attempt = 1; attempt <= AI_MAX_ATTEMPTS; attempt++) {
                attemptsUsed = attempt;
                try {
                    String raw = aiService.sendRequest(attempt == 1 ? request : repairRequest);
                    AiGreetingResult classified = classifyAiResponse(raw);
                    if (classified.rejected()) {
                        lastReason = "ai_response_false";
                        log.warn("[51job] AI回复未遵守话术模板 attempt={} reason={}", attempt, lastReason);
                        if (attempt < AI_MAX_ATTEMPTS && shouldRetryAiReason(lastReason)) {
                            if (waitForAiRetry(attempt)) continue;
                        }
                        break;
                    }
                    AiService.GreetingValidation validation = AiService.validateGreeting(
                            classified.message(), introduce);
                    if (validation.usable()) {
                        return new AiGreetingResult(validation.message(), false, "ok", attempt);
                    }
                    lastReason = "ai_" + validation.reason();
                    log.warn("[51job] AI回复校验失败 attempt={} reason={} rawLength={}",
                            attempt, lastReason, raw == null ? 0 : raw.length());
                    if (attempt < AI_MAX_ATTEMPTS && shouldRetryAiReason(lastReason)) {
                        if (waitForAiRetry(attempt)) continue;
                    }
                    break;
                } catch (Exception e) {
                    lastReason = classifyAiFailure(e);
                    log.warn("[51job] AI请求失败 attempt={} reason={}", attempt, lastReason);
                    if (attempt < AI_MAX_ATTEMPTS && isRetryableAiFailure(e)) {
                        if (waitForAiRetry(attempt)) continue;
                    }
                    break;
                }
            }
            return new AiGreetingResult(null, false, lastReason, attemptsUsed);
        } catch (IllegalArgumentException e) {
            log.warn("[51job] AI提示词配置无效: {}", e.getMessage());
            return new AiGreetingResult(null, false, "ai_prompt_error", 0);
        } catch (Exception e) {
            log.warn("[51job] AI配置读取失败: {}", e.getMessage());
            return new AiGreetingResult(null, false, "ai_config_error", 0);
        }
    }

    static boolean shouldRetryAiReason(String reason) {
        return reason != null
                && !reason.equals("ai_prompt_error")
                && !reason.equals("ai_prompt_missing");
    }

    static boolean isRetryableAiFailure(Throwable error) {
        Integer statusCode = extractAiStatusCode(error);
        if (statusCode != null) {
            return statusCode == 424 || statusCode == 429 || (statusCode >= 500 && statusCode <= 599);
        }
        String reason = classifyAiFailure(error);
        if ("ai_tls_error".equals(reason) || "ai_timeout".equals(reason)) {
            return true;
        }
        return hasNetworkFailure(error);
    }

    static String classifyAiFailure(Throwable error) {
        if (hasTlsHandshakeFailure(error)) return "ai_tls_error";
        if (hasTimeoutFailure(error)) return "ai_timeout";

        Integer statusCode = extractAiStatusCode(error);
        if (statusCode != null) {
            if (statusCode == 424) return "ai_upstream_auth_error";
            if (statusCode == 429) return "ai_rate_limited";
            if (statusCode >= 400 && statusCode < 500) return "ai_request_rejected";
        }
        return "ai_request_error";
    }

    static long aiRetryDelayMillis(int failedAttempt) {
        return switch (failedAttempt) {
            case 1 -> AI_FIRST_RETRY_DELAY_MS;
            case 2 -> AI_SECOND_RETRY_DELAY_MS;
            default -> 0L;
        };
    }

    private boolean waitForAiRetry(int failedAttempt) {
        long delayMillis = aiRetryDelayMillis(failedAttempt);
        if (delayMillis <= 0 || shouldStop()) return false;
        try {
            Thread.sleep(delayMillis);
            return !shouldStop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Integer extractAiStatusCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AiService.AiRequestException requestException
                    && requestException.statusCode() != null) {
                return requestException.statusCode();
            }
            Matcher matcher = AI_STATUS_CODE_PATTERN.matcher(current.getMessage() == null ? "" : current.getMessage());
            if (matcher.find()) {
                try {
                    return Integer.valueOf(matcher.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean hasTlsHandshakeFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof javax.net.ssl.SSLHandshakeException) return true;
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("sslhandshakeexception")
                        || normalized.contains("ssl handshake")
                        || normalized.contains("handshake_failure")
                        || normalized.contains("remote host terminated the handshake")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasTimeoutFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("timed out") || normalized.contains("timeout")) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasNetworkFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.io.IOException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.NoRouteToHostException
                    || current instanceof java.net.SocketException
                    || current instanceof java.net.UnknownHostException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static AiGreetingResult classifyAiResponse(String result) {
        if (result == null) return new AiGreetingResult(null, false, "ai_empty", 1);
        String normalized = result.trim();
        if (normalized.isBlank()) return new AiGreetingResult(null, false, "ai_empty", 1);
        if (normalized.equalsIgnoreCase("false")) return new AiGreetingResult(null, true, "ai_rejected", 1);
        String message = normalized.replaceAll("^```(?:text)?\\s*|\\s*```$", "").trim();
        return message.isBlank()
                ? new AiGreetingResult(null, false, "ai_empty", 1)
                : new AiGreetingResult(message, false, null, 1);
    }

    static boolean isUsableAiGreeting(AiGreetingResult result) {
        return result != null && !result.rejected() && result.message() != null && !result.message().isBlank();
    }

    private boolean sendGreetingInDetail(Page detailPage, String message) {
        Locator action = findVisibleTextAction(detailPage, Job51Locators.GREETING_TEXTS);
        if (action == null) {
            log.warn("[51job] 详情页未找到沟通入口 url={}", safePageUrl(detailPage));
            return false;
        }
        Page messagePage = detailPage;
        boolean closeMessagePage = false;
        try {
            int pageCountBefore = detailPage.context().pages().size();
            action.click();
            messagePage = waitForMessagePage(detailPage, pageCountBefore, MESSAGE_PAGE_WAIT_TIMEOUT_MS);
            closeMessagePage = messagePage != detailPage;
            if (messagePage != detailPage) {
                try {
                    messagePage.waitForLoadState(LoadState.DOMCONTENTLOADED,
                            new Page.WaitForLoadStateOptions().setTimeout(5000));
                } catch (Exception ignored) {
                }
            }
            Locator input = waitForMessageInput(messagePage, 7000L);
            if (input == null) {
                log.warn("[51job] 沟通页未找到消息输入框 url={} popup={}",
                        safePageUrl(messagePage), closeMessagePage);
                return false;
            }
            int before = visibleOutgoingMessageCount(messagePage);
            input.fill(message);
            Locator send = findVisibleTextAction(messagePage, Job51Locators.SEND_TEXTS);
            boolean clickedSend = false;
            if (send != null && send.isEnabled()) {
                send.click();
                clickedSend = true;
            } else {
                input.press("Enter");
            }
            if (waitForOutgoingMessage(messagePage, before, message, MESSAGE_CONFIRM_TIMEOUT_MS)) {
                return true;
            }
            // 某些版本的沟通面板只响应回车，且不提供可用的发送按钮。
            if (!clickedSend && hasEditableValue(input)) {
                input.press("Enter");
                if (waitForOutgoingMessage(messagePage, before, message, 3000L)) {
                    return true;
                }
            }
            log.warn("[51job] 沟通消息发送后未确认 url={} popup={} outgoingBefore={} inputValuePresent={}",
                    safePageUrl(messagePage), closeMessagePage, before, hasEditableValue(input));
            return false;
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) throw propagate(e);
            log.warn("[51job] 沟通消息发送异常 url={} reason={}", safePageUrl(messagePage), messageOf(e, "unknown"));
            return false;
        } finally {
            if (closeMessagePage) {
                try {
                    messagePage.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Page waitForMessagePage(Page detailPage, int pageCountBefore, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<Page> pages = detailPage.context().pages();
                if (pages.size() > pageCountBefore) {
                    return pages.get(pages.size() - 1);
                }
                detailPage.waitForTimeout(200);
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) throw propagate(e);
                return detailPage;
            }
        }
        return detailPage;
    }

    private Locator waitForMessageInput(Page target, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String selector : Job51Locators.MESSAGE_INPUT_SELECTORS) {
                Locator input = target.locator(selector);
                if (input.count() > 0 && input.first().isVisible()) return input.first();
            }
            try {
                target.waitForTimeout(200);
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) throw propagate(e);
                return null;
            }
        }
        return null;
    }

    private boolean hasEditableValue(Locator input) {
        try {
            String value = input.inputValue();
            if (value != null && !value.isBlank()) return true;
        } catch (Exception ignored) {
        }
        try {
            String text = input.textContent();
            return text != null && !text.isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String safePageUrl(Page target) {
        try {
            return target == null ? "null" : target.url();
        } catch (Exception ignored) {
            return "<closed>";
        }
    }

    private Locator findVisibleTextAction(Page target, String[] texts) {
        for (String text : texts) {
            Locator exact = target.getByText(text, new Page.GetByTextOptions().setExact(true));
            for (int i = 0; i < exact.count(); i++) {
                Locator candidate = exact.nth(i);
                try {
                    if (candidate.isVisible() && candidate.isEnabled()) return candidate;
                } catch (Exception ignored) {
                }
            }
            Locator buttons = target.locator("button:has-text('" + text + "'), a:has-text('" + text + "'), [role='button']:has-text('" + text + "')");
            for (int i = 0; i < buttons.count(); i++) {
                Locator candidate = buttons.nth(i);
                try {
                    if (candidate.isVisible() && candidate.isEnabled()) return candidate;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private int visibleOutgoingMessageCount(Page target) {
        int count = 0;
        for (String selector : Job51Locators.OUTGOING_MESSAGE_SELECTORS) {
            try {
                count = Math.max(count, target.locator(selector).count());
            } catch (Exception ignored) {
            }
        }
        return count;
    }

    private boolean waitForOutgoingMessage(Page target, int initialCount, String message, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (visibleOutgoingMessageCount(target) > initialCount) return true;
            try {
                Locator body = target.locator("body");
                String text = body.innerText();
                if (text != null && text.contains(message)) return true;
                String normalizedBody = comparableText(text);
                String normalizedMessage = comparableText(message);
                if (!normalizedMessage.isBlank() && normalizedBody.contains(normalizedMessage)) return true;
                for (String successText : Job51Locators.SUCCESS_TEXTS) {
                    if (text != null && text.contains(successText)) return true;
                }
                target.waitForTimeout(250);
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) throw propagate(e);
                return false;
            }
        }
        return false;
    }

    private String comparableText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").trim();
    }

    record AiGreetingResult(String message, boolean rejected, String reason, int attempts) {
        AiGreetingResult(String message, boolean rejected) {
            this(message, rejected, rejected ? "ai_rejected" : null, 1);
        }
    }

    record JobDescriptionResult(String text, String source, String reason) {
        boolean usable() {
            return text != null && !text.isBlank() && "ok".equals(reason);
        }
    }

    private enum DetailAction {
        GREETING, APPLY, NONE
    }

    private enum AiJobOutcome {
        SENT, DELIVERED, SKIPPED, DETAIL_BLOCKED, VERIFICATION_REQUIRED, FAILED
    }

    /**
     * 投递当前页面的所有职位
     */
    private void deliverCurrentPage() {
        PlaywrightUtil.sleep(1);

        Locator checkboxes = page.locator("div.ick");
        int jobCount = checkboxes.count();
        if (jobCount == 0) {
            throw new IllegalStateException("当前页未找到岗位选择框，页面结构可能已变化");
        }

        Locator titles = page.locator("[class*='jname text-cut']");
        Locator companies = page.locator("[class*='cname text-cut']");
        List<Long> pageJobIds = snapshotCurrentPageJobIds();
        if (pageJobIds.size() < jobCount) {
            List<Long> domJobIds = collectJobIdsOnPage();
            if (!domJobIds.isEmpty()) {
                pageJobIds = domJobIds;
            }
        }

        if (pageJobIds.isEmpty()) {
            requestRest("empty_job_ids", "当前页岗位标识读取异常，进入间歇休息");
            return;
        }

        List<String> selectedJobInfos = new ArrayList<>();
        List<Long> selectedJobIds = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            if (shouldStop()) {
                stopReason = "user_cancelled";
                return;
            }
            if (hasReachedMaxPerRun() || selectedJobInfos.size() >= remainingMaxPerRun()) {
                break;
            }
            Locator checkbox = checkboxes.nth(i);
            Long jobId = i < pageJobIds.size() ? pageJobIds.get(i) : readJobIdFromCard(checkbox);
            Job51Entity entity = job51Service.findByJobId(jobId);
            String cardLink = readRealJobLinkFromCard(checkbox, jobId);
            if (Job51Service.isExternalApplicationRoute(cardLink)) {
                job51Service.markExternalApplicationPending(jobId, cardLink);
                sendProgress(String.format("待外部申请：%s", i < titles.count() ? titles.nth(i).textContent() : "未知职位"), null, null);
                continue;
            }
            if (Job51Service.isExternalApplication(entity)) {
                job51Service.markExternalApplicationPending(jobId,
                        entity.getApplicationUrl() == null ? entity.getJobLink() : entity.getApplicationUrl());
                sendProgress(String.format("待外部申请：%s", i < titles.count() ? titles.nth(i).textContent() : "未知职位"), null, null);
                continue;
            }
            checkbox.evaluate("el => el.click()");
            String title = i < titles.count() ? titles.nth(i).textContent() : "未知职位";
            String company = i < companies.count() ? companies.nth(i).textContent() : "未知公司";
            selectedJobInfos.add(company + " | " + title);
            if (jobId != null) selectedJobIds.add(jobId);
        }
        selectedCount += selectedJobInfos.size();

        if (selectedJobInfos.isEmpty()) {
            sendProgress("当前页岗位均需前往外部招聘页申请，已加入分析页待办", null, null);
            return;
        }

        PlaywrightUtil.sleep(1);
        page.evaluate("window.scrollTo(0, 0)");
        PlaywrightUtil.sleep(1);

        if (!clickBatchDeliverButton()) {
            return;
        }

        PlaywrightUtil.sleep(3);
        int successNum = handleDeliverySuccessDialog(selectedJobInfos, selectedJobIds);
        handleSeparateDeliveryDialog();

        if (successNum == 0 && !reachedDailyLimit && !shouldStop()) {
            throw new IllegalStateException("点击批量投递后未确认成功结果");
        }
        if (successNum > 0) {
            successfulProgressSinceRest = true;
        }
        confirmedSuccessCount += successNum;
    }

    /**
     * 点击批量投递按钮
     */
    private boolean clickBatchDeliverButton() {
        int retryCount = 0;
        boolean success = false;

        while (!success && retryCount < 5) {
            try {
                if (shouldStop()) {
                    stopReason = "user_cancelled";
                    return false;
                }

                // 查找当前页面真正可见且可用的批量投递入口。
                Locator button = findBatchDeliverButton();

                if (button != null) {
                    PlaywrightUtil.sleep(1);
                    button.click();
                    
                    // 🚨 点击后立即检测“日投递上限”提示（短暂出现，需快速多次检测）
                    for (int i = 0; i < 10; i++) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {} // 每200ms检测一次
                        if (detectDailyLimitToast51job()) {
                            markDailyLimitRest();
                            log.warn("点击投递按钮后，检测到 51job 日投递上限提示，进入间歇休息");
                            return false;
                        }
                    }
                    
                    success = true;
                } else {
                    logBatchDeliverDiagnostics();
                    throw new IllegalStateException("未找到可用的一键投递按钮");
                }
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) {
                    throw propagate(e);
                }
                retryCount++;
                PlaywrightUtil.sleep(1);
            }
        }
        if (!success) {
            throw new IllegalStateException("一键投递按钮点击失败");
        }
        return true;
    }

    private Locator findBatchDeliverButton() {
        String[] selectors = {
                "button.p_but.all_apply",
                "a.p_but.all_apply",
                "[role='button'].p_but.all_apply",
                "div.p_but.all_apply",
                "button.p_but",
                "a.p_but",
                "[role='button'].p_but",
                "div.p_but",
                "[class*='all_apply']",
                "[class*='all-apply']",
                "[class*='allApply']",
                "button:has-text('一键投递')",
                "button:has-text('批量投递')",
                "a:has-text('一键投递')",
                "a:has-text('批量投递')",
                "[role='button']:has-text('一键投递')",
                "[role='button']:has-text('批量投递')"
        };
        Locator fallback = findBatchDeliverButtonIn(page.locator("div.tabs_in"), selectors);
        if (fallback != null) return fallback;
        return findBatchDeliverButtonIn(page.locator("body"), selectors);
    }

    private Locator findBatchDeliverButtonIn(Locator scope, String[] selectors) {
        Locator fallback = null;
        for (String selector : selectors) {
            Locator candidates = scope.locator(selector);
            for (int i = 0; i < candidates.count(); i++) {
                Locator candidate = candidates.nth(i);
                try {
                    if (!candidate.isVisible() || !candidate.isEnabled()) continue;
                    String text = candidate.innerText();
                    String classes = candidate.getAttribute("class");
                    boolean actionClass = classes != null
                            && (classes.contains("all_apply") || classes.contains("all-apply")
                            || classes.contains("allApply"));
                    if (text != null && (text.contains("投递") || text.contains("申请"))) {
                        return candidate;
                    }
                    if (actionClass && fallback == null) fallback = candidate;
                } catch (Exception ignored) {
                }
            }
        }
        return fallback;
    }

    private void logBatchDeliverDiagnostics() {
        try {
            Locator parent = page.locator("div.tabs_in");
            Locator controls = page.locator("button, a, [role='button'], [class*='p_but'], [class*='apply']");
            log.warn("[51job] 批量投递入口诊断 tabs_in={} scopedControls={} pageControls={} texts={}",
                    parent.count(), parent.locator("button, a, [role='button'], [class*='p_but'], [class*='apply']").count(),
                    controls.count(), compactLog(String.join(" | ", controls.allInnerTexts()), 800));
        } catch (Exception e) {
            log.warn("[51job] 批量投递入口诊断读取失败: {}", messageOf(e, "unknown"));
        }
    }

    /**
     * 处理投递成功弹窗
     */
    private int handleDeliverySuccessDialog(List<String> selectedJobInfos, List<Long> selectedJobIds) {
        int confirmed = 0;
        try {
            PlaywrightUtil.sleep(2);

            Locator successContent = page.locator("//div[@class='successContent']");
            if (successContent.count() > 0) {
                String text = successContent.textContent();
                if (text != null && text.contains("快来扫码下载")) {
                    log.info("检测到下载App弹窗，关闭中...");
                    // 关闭弹窗
                    Locator closeButton = page.locator("[class*='van-icon van-icon-cross van-popup__close-icon van-popup__close-icon--top-right']");
                    if (closeButton.count() > 0) {
                        closeButton.click();
                        log.info("成功关闭下载App弹窗");
                    }
                }
            }

            // 页面当前会弹“投递成功！”且不显示数字，也兼容带成功/失败数量的旧文案。
            String dialogText = findVisibleDeliveryFeedback();
            if (dialogText != null && dialogText.contains("投递成功")) {
                    Integer successNum = parseDeliveryCount(dialogText, "投递成功");
                    Integer failNum = parseDeliveryCount(dialogText, "未投递");
                    // 明确出现成功弹窗时，本次已勾选岗位就是平台确认成功的批次。
                    if (successNum == null) {
                        successNum = selectedJobInfos == null ? 0 : selectedJobInfos.size();
                    }
                    log.info("[51job] 投递结果：成功 {} 个，未投递 {} 个", successNum, failNum);
                    sendProgress(String.format("投递结果：成功 %s 个，未投递 %s 个", successNum == null ? "?" : successNum, failNum == null ? "?" : failNum), null, null);

                    // ✅ 投递成功后，标记数据库中的岗位为已投递
                    if (successNum != null && successNum > 0) {
                        try {
                            List<Long> deliveredIds = selectedJobIds == null ? new ArrayList<>() : new ArrayList<>(selectedJobIds);
                            if (!deliveredIds.isEmpty()) {
                                // 只标记成功投递的数量（取成功数和缓存数的较小值）
                                int markCount = Math.min(successNum, deliveredIds.size());
                                List<Long> toMark = new ArrayList<>(deliveredIds.subList(0, markCount));
                                job51Service.markDeliveredBatch(toMark);
                                log.info("[51job] 标记已投递 {} 个职位", toMark.size());
                            } else {
                                log.warn("[51job] 当前页没有缓存的jobId，无法标记投递状态");
                            }
                        } catch (Exception e) {
                            log.warn("[51job] 标记投递状态失败: {}", e.getMessage());
                        }
                    } else {
                        log.warn("[51job] 投递成功数量为0或未解析到，不标记投递状态");
                    }
                    confirmed = successNum == null ? 0 : Math.max(0, successNum);
                    int infoCount = confirmedInfoCount(confirmed, selectedJobInfos == null ? 0 : selectedJobInfos.size());
                    if (selectedJobInfos != null) {
                        resultList.addAll(selectedJobInfos.subList(0, infoCount));
                    }

                    // 优先点击“确定/关闭”按钮，其次点右上角关闭，再次退格键
                    try {
                        Locator okBtn = page.locator(".el-dialog__footer button:has-text('确定'), .el-message-box__btns button:has-text('确定')");
                        if (okBtn.count() > 0) {
                            okBtn.first().click();
                        } else {
                            // 1) 点击关闭图标的父按钮
                            Locator iconClose = page.locator("i.el-dialog__close.el-icon.el-icon-close");
                            boolean closed = false;
                            if (iconClose.count() > 0 && iconClose.first().isVisible()) {
                                try {
                                    // 有些站点图标本身不接收点击，点击其父按钮更稳定
                                    iconClose.first().evaluate("el => el.parentElement && el.parentElement.click()");
                                    closed = true;
                                } catch (Exception ignored) {}
                            }
                            // 2) 直接点击 header 关闭按钮（带 aria-label="Close"）
                            if (!closed) {
                                Locator headerBtn = page.locator("button.el-dialog__headerbtn, .el-dialog__header button.el-dialog__headerbtn, button[aria-label='Close']");
                                if (headerBtn.count() > 0 && headerBtn.first().isVisible()) {
                                    try {
                                        headerBtn.first().click(new Locator.ClickOptions().setForce(true).setTimeout(2000));
                                        closed = true;
                                    } catch (Exception ignored) {}
                                }
                            }
                            // 3) JS 兜底点击
                            if (!closed) {
                                try {
                                    page.evaluate("document.querySelector('button.el-dialog__headerbtn')?.click() || document.querySelector('button[aria-label=\\'Close\\']')?.click() ");
                                    closed = true;
                                } catch (Exception ignored) {}
                            }
                            // 4) 最终兜底：按 ESC
                            if (!closed) {
                                page.keyboard().press("Escape");
                            }
                        }
                        PlaywrightUtil.sleep(1);
                    } catch (Exception ignored) {}
            }

            // 统一尝试关闭任何残留的弹框覆盖层
            closeAnyModalOverlays();
            // 弹窗处理后再次检测是否出现“日投递上限”提示
            try {
                if (detectDailyLimitToast51job()) {
                    markDailyLimitRest();
                    log.warn("处理成功弹窗后，检测到日投递上限，进入间歇休息");
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) {
                throw propagate(e);
            }
            log.debug("未找到投递成功弹窗或处理失败: {}", e.getMessage());
        }
        return confirmed;
    }

    private String findVisibleDeliveryFeedback() {
        Locator candidates = page.locator(".el-dialog__body:visible, .el-dialog:visible, .el-message-box__content:visible, [role='dialog']:visible");
        for (int i = 0; i < candidates.count(); i++) {
            try {
                String text = candidates.nth(i).innerText();
                if (text != null && text.contains("投递成功")) {
                    return text;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    static Integer parseDeliveryCount(String text, String marker) {
        if (text == null || marker == null) return null;
        String normalized = text.replaceAll("\\s+", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(marker) + "[^0-9]{0,20}(\\d+)")
                .matcher(normalized);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    /**
     * 处理单独投递申请弹窗
     */
    private void handleSeparateDeliveryDialog() {
        try {
            Locator dialogContent = page.locator("//div[@class='el-dialog__body']/span");
            if (dialogContent.count() > 0) {
                String text = dialogContent.textContent();
                if (text != null && text.contains("需要到企业招聘平台单独申请")) {
                    log.info("检测到单独投递申请弹窗，关闭中...");
                    // 关闭弹窗
                    Locator closeButton = page.locator("#app > div > div.post > div > div > div.j_result > div > div:nth-child(2) > div > div:nth-child(2) > div:nth-child(2) > div > div.el-dialog__header > button > i");
                    if (closeButton.count() > 0) {
                        closeButton.click();
                        log.info("成功关闭单独投递申请弹窗");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("未找到单独投递申请弹窗或处理失败: {}", e.getMessage());
        }
    }

    /**
     * 跳转到指定页码
     */
    private boolean jumpToPage(int pageNum) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                if (shouldStop()) {
                    stopReason = "user_cancelled";
                    return false;
                }

                // 跳页前先尝试关闭可能遮挡操作的弹框
                closeAnyModalOverlays();

                if (isActivePage(pageNum)) {
                    return true;
                }

                Locator pageNumbers = page.locator(".bottom-page .el-pagination .el-pager li.number");
                boolean clicked = false;
                for (int i = 0; i < pageNumbers.count(); i++) {
                    String text = pageNumbers.nth(i).textContent();
                    if (text != null && text.trim().equals(String.valueOf(pageNum))) {
                        pageNumbers.nth(i).click();
                        clicked = true;
                        break;
                    }
                }

                if (!clicked) {
                    Locator next = page.locator(".bottom-page .el-pagination button.btn-next");
                    if (next.count() == 0 || next.isDisabled()) {
                        log.info("分页已到末页，目标第{}页未找到", pageNum);
                        return false;
                    }
                    next.click();
                }

                if (waitForActivePage(pageNum, 10000)) {
                    log.info("成功跳转到第{}页", pageNum);
                    return true;
                }
                throw new IllegalStateException("分页点击后未切换到第" + pageNum + "页");
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) {
                    throw propagate(e);
                }
                log.warn("跳转到第{}页失败，重试第{}次: {}", pageNum, retry + 1, e.getMessage());
                PlaywrightUtil.sleep(1);

                // 检查是否出现异常，如果出现则刷新页面
                if (checkAccessVerification()) {
                    return false;
                }
                try {
                    page.reload();
                } catch (Exception reloadError) {
                    if (isTargetClosedFailure(reloadError)) {
                        throw propagate(reloadError);
                    }
                }
                PlaywrightUtil.sleep(2);
            }
        }
        return false;
    }

    private boolean isActivePage(int pageNum) {
        Locator active = page.locator(".bottom-page .el-pagination .el-pager li.number.active");
        return active.count() > 0 && pageNumberMatches(active.first().textContent(), pageNum);
    }

    static boolean pageNumberMatches(String text, int pageNum) {
        return text != null && text.trim().equals(String.valueOf(pageNum));
    }

    static int confirmedInfoCount(int confirmed, int selected) {
        return Math.min(Math.max(confirmed, 0), Math.max(selected, 0));
    }

    private boolean waitForActivePage(int pageNum, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isActivePage(pageNum) && page.locator("div.ick").count() > 0) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 统一关闭可能出现的弹框覆盖层（ElementUI/VanPopup 等）。
     */
    private void closeAnyModalOverlays() {
        try {
            boolean closedOnce = false;
            for (int t = 0; t < 3; t++) {
                boolean closedThisRound = false;
                Locator headerClose = page.locator("button.el-dialog__headerbtn, button[aria-label='Close']");
                if (headerClose.count() > 0 && headerClose.first().isVisible()) {
                    try {
                        headerClose.first().click(new Locator.ClickOptions().setForce(true).setTimeout(2000));
                        closedThisRound = true;
                    } catch (Exception ignored) {}
                }
                // 直接点击关闭图标或其父按钮
                Locator iconClose = page.locator("i.el-dialog__close.el-icon.el-icon-close");
                if (iconClose.count() > 0 && iconClose.first().isVisible()) {
                    try {
                        iconClose.first().evaluate("el => el.parentElement && el.parentElement.click()");
                        closedThisRound = true;
                    } catch (Exception ignored) {}
                }
                Locator okBtn = page.locator(".el-dialog__footer button:has-text('确定'), .el-message-box__btns button:has-text('确定')");
                if (okBtn.count() > 0 && okBtn.first().isVisible()) {
                    okBtn.first().click();
                    closedThisRound = true;
                }
                // JS 一次性点击所有可能的关闭按钮，作为强兜底
                if (!closedThisRound) {
                    try {
                        page.evaluate("document.querySelectorAll('button.el-dialog__headerbtn, button[aria-label=\\'Close\\']').forEach(b=>b.click())");
                    } catch (Exception ignored) {}
                }
                Locator popupClose = page.locator(".van-popup__close-icon, .van-icon-cross");
                if (popupClose.count() > 0 && popupClose.first().isVisible()) {
                    popupClose.first().click();
                    closedThisRound = true;
                }
                if (!closedThisRound) break;
                closedOnce = true;
                PlaywrightUtil.sleep(1);
            }
            if (closedOnce) {
                // 等待弹层移除
                try { page.waitForSelector(".el-dialog__wrapper, .van-popup", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED).setTimeout(2000)); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.debug("关闭弹框覆盖层失败: {}", e.getMessage());
        }
    }

    /**
     * 检查是否需要登录
     */
    private boolean checkNeedLogin() {
        return checkNeedLogin(page);
    }

    private boolean checkNeedLogin(Page target) {
        try {
            if (target == null) return false;
            if (isLoginPageUrl(target.url())) return true;
            Locator loginElement = target.locator("//a[contains(@class, 'uname')]");
            if (loginElement.count() > 0) {
                String text = loginElement.textContent();
                return text != null && text.contains("登录");
            }
            String title = target.title();
            if (title != null && title.contains("欢迎登录")) return true;
            String body = target.locator("body").innerText();
            return body != null && body.contains("登录/注册")
                    && (body.contains("密码登录") || body.contains("发送验证码"));
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isLoginPageUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String normalized = url.toLowerCase(Locale.ROOT);
        return normalized.contains("login.51job.com")
                || normalized.contains("/login")
                || normalized.contains("login.php");
    }

    /**
     * 检测 51job 页面是否出现“日投递上限”提示的浮框（短暂存在，需及时检查）。
     */
    private boolean detectDailyLimitToast51job() {
        try {
            String[] kws = new String[]{
                    "今日投递太多", "您今日投递太多", "休息一下明天再来", "达到上限", "次数过多"
            };
            for (String kw : kws) {
                Locator textToast = page.locator("text=" + kw);
                if (textToast.count() > 0 && textToast.first().isVisible()) {
                    return true;
                }
            }
            Locator msg = page.locator(".el-message, .el-message--info, .toast, .message, div[role='alert'], .el-notification__content");
            for (int i = 0; i < msg.count(); i++) {
                try {
                    Locator item = msg.nth(i);
                    if (!item.isVisible()) continue;
                    String tt = item.innerText().replace('\n', ' ').trim();
                    for (String kw : kws) {
                        if (tt.contains(kw)) {
                            log.info("[51job] 日投递上限提示命中: {}", compactLog(tt, 200));
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
            }
            Object foundObj = page.evaluate("() => { const kws = ['今日投递太多','您今日投递太多','休息一下明天再来','达到上限','次数过多']; const bodyText = document.body ? (document.body.innerText || '') : ''; return kws.some(k=>bodyText.includes(k)); }");
            if (foundObj instanceof Boolean) {
                if ((Boolean) foundObj) log.info("[51job] 日投递上限提示命中: 页面可见文本");
                return (Boolean) foundObj;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否出现访问验证
     */
    private boolean checkAccessVerification() {
        return checkAccessVerification(page);
    }

    private boolean checkAccessVerification(Page target) {
        try {
            if (target == null) return false;
            Locator wafTitle = target.locator("//p[@class='waf-nc-title']");
            Locator wafScript = target.locator("script[name^='aliyunwaf_']");
            String body = target.locator("body").innerText();
            boolean bodyBlocked = body != null
                    && (body.contains("访问验证") || body.contains("请按住滑块") || body.contains("滑动验证页面"));
            if ((wafTitle.count() > 0 && wafTitle.first().isVisible()) || wafScript.count() > 0 || bodyBlocked) {
                log.error("出现访问验证，需要手动处理");
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测 51job 页面是否显示“无职位/没有符合条件的职位”等提示。
     */
    private boolean detectNoJobs51job() {
        try {
            String[] kws = new String[]{
                    "暂无职位", "没有符合条件的职位", "暂无符合条件职位", "暂无符合职位", "暂无相关职位"
            };
            for (String kw : kws) {
                Locator t = page.locator("text=" + kw);
                if (t.count() > 0 && t.first().isVisible()) {
                    return true;
                }
            }
            // 常见空态容器（若存在则进一步通过文本确认）
            Locator empty = page.locator(".el-empty, .empty, .no-result, .no_res");
            if (empty.count() > 0) {
                java.util.List<String> texts = new java.util.ArrayList<>();
                try { texts = empty.allInnerTexts(); } catch (Exception ignored) {}
                for (String t : texts) {
                    if (t == null) continue;
                    String tt = t.replace('\n', ' ').trim();
                    for (String kw : kws) {
                        if (tt.contains(kw)) return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建搜索URL
     */
    private String buildSearchUrl(String keyword) {
        StringBuilder url = new StringBuilder(BASE_URL);
        url.append(JobUtils.appendListParam("jobArea", config.getJobArea()));
        url.append(JobUtils.appendListParam("salary", config.getSalary()));
        url.append("&keyword=").append(keyword);
        return url.toString();
    }

    /**
     * 采集当前页所有岗位的 jobId（解析 jobdetail 链接/数据属性）
     */
    private List<Long> collectJobIdsOnPage() {
        List<Long> ids = new ArrayList<>();
        try {
            // 1) 解析常见 jobdetail 链接形态
            Locator anchors = page.locator(
                    "a[href*='/pc/jobdetail?jobId='], " +
                    "a[href*='/pc/jobdetail'], " +
                    "a[href*='jobs.51job.com/'], " +
                    "a.jname[href]"
            );
            int count = anchors.count();
            for (int i = 0; i < count; i++) {
                try {
                    String href = anchors.nth(i).getAttribute("href");
                    Long id = parseJobIdFromHref(href);
                    if (id != null) ids.add(id);
                } catch (Exception ignored) {}
            }

            // 2) 解析卡片上的数据属性（部分页面存在）
            try {
                Locator cards = page.locator("[data-jobid], [data-analysis-jobid], [data-job-id]");
                int c = cards.count();
                for (int i = 0; i < c; i++) {
                    try {
                        String v = null;
                        Locator card = cards.nth(i);
                        v = v == null ? card.getAttribute("data-jobid") : v;
                        v = v == null ? card.getAttribute("data-analysis-jobid") : v;
                        v = v == null ? card.getAttribute("data-job-id") : v;
                        if (v != null) {
                            try {
                                Long id = Long.parseLong(v.replaceAll("[^0-9]", ""));
                                if (id != null) ids.add(id);
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            // 去重
            java.util.Set<Long> uniq = new java.util.LinkedHashSet<>(ids);
            ids = new java.util.ArrayList<>(uniq);

            // 记录采集到的数量与部分样例，便于诊断
            try {
                if (!ids.isEmpty()) {
                    String sample = ids.stream().limit(5).map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
                    log.info("[51job] 当前页采集到 {} 个 jobId, 示例: {}", ids.size(), sample);
                } else {
                    // 采集为空：按用户约定视为达到投递上限/页面结构变化，立即通知并停止
                    log.warn("[51job] 当前页未采集到任何 jobId，可能页面结构变化或选择器不匹配");
                    // 向前端推送警告，便于按钮重置
                    sendProgress("[51job] 当前页未采集到任何 jobId，可能页面结构变化或选择器不匹配", null, null);
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.debug("采集当前页 jobId 失败: {}", e.getMessage());
        }
        return ids;
    }

    private Long parseJobIdFromHref(String href) {
        if (href == null || href.isEmpty()) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]jobId=(\\d+)").matcher(href);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("/(\\d+)\\.html").matcher(href);
            if (m2.find()) {
                return Long.parseLong(m2.group(1));
            }
            // 兜底：从路径段中找较长数字片段
            java.util.regex.Matcher m3 = java.util.regex.Pattern.compile("(\\d{5,})").matcher(href);
            if (m3.find()) {
                return Long.parseLong(m3.group(1));
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }

    /**
     * 发送进度消息
     */
    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) {
            progressCallback.accept(message, current, total);
        }
    }

    private void ensurePageReady() {
        try {
            if (page != null && !page.isClosed()) {
                page.url();
                return;
            }
        } catch (Exception e) {
            if (!isTargetClosedFailure(e)) {
                throw propagate(e);
            }
        }
        if (pageRecovery == null) {
            throw new IllegalStateException("51job页面不可用，且没有页面恢复回调");
        }
        page = pageRecovery.get();
        if (page == null || page.isClosed()) {
            throw new IllegalStateException("51job页面恢复后仍不可用");
        }
    }

    private List<Long> snapshotCurrentPageJobIds() {
        synchronized (currentPageJobIds) {
            return new ArrayList<>(currentPageJobIds);
        }
    }

    static boolean isTargetClosedFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
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

    private String messageOf(Throwable error, String fallback) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private RuntimeException propagate(Throwable error) {
        return error instanceof RuntimeException
                ? (RuntimeException) error
                : new IllegalStateException(messageOf(error, "Playwright页面操作失败"), error);
    }

    /**
     * 检查是否应该停止
     */
    private boolean shouldStop() {
        return shouldStopCallback != null && shouldStopCallback.get();
    }

    /**
     * 从JSON文本中提取jobId列表
     */
    private List<Long> extractJobIdsFromJson(String json) {
        return extractJobIdsFromJsonText(json);
    }

    static List<Long> extractJobIdsFromJsonText(String json) {
        java.util.LinkedHashSet<Long> jobIds = new java.util.LinkedHashSet<>();
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            collectJobIds(root, jobIds, 0);
        } catch (Exception e) {
            log.warn("[51job] 解析JSON提取jobId失败: {}", e.getMessage());
        }
        return new ArrayList<>(jobIds);
    }

    private static void collectJobIds(com.fasterxml.jackson.databind.JsonNode node,
                                      java.util.Set<Long> jobIds, int depth) {
        if (node == null || node.isNull() || depth > 12) return;
        if (node.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT).replaceAll("[_-]", "");
                com.fasterxml.jackson.databind.JsonNode value = field.getValue();
                if (key.equals("jobid") || key.equals("jobidstr")) {
                    try {
                        String text = value.asText(null);
                        if (text != null) {
                            long parsed = Long.parseLong(text.trim());
                            if (parsed > 0) jobIds.add(parsed);
                        }
                    } catch (Exception ignored) {
                    }
                } else {
                    collectJobIds(value, jobIds, depth + 1);
                }
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                collectJobIds(child, jobIds, depth + 1);
            }
        }
    }
}
