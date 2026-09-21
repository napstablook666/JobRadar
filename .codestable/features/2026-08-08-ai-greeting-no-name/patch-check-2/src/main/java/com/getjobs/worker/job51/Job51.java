package com.getjobs.worker.job51;

import com.getjobs.application.service.Job51Service;
import com.getjobs.application.service.AiService;
import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.entity.Job51Entity;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
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
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
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

    private final List<String> resultList = new ArrayList<>();
    private final Job51Service job51Service;
    private final AiService aiService;
    private boolean networkHooked = false;
    private boolean reachedDailyLimit = false;
    private final java.util.Set<String> processedRequestIds = new java.util.HashSet<>();
    @Getter
    private int currentPageNum = 0;
    @Getter
    private String executionError;
    @Getter
    private String stopReason;
    @Getter
    private int selectedCount;
    @Getter
    private int confirmedSuccessCount;
    @Getter
    private int aiProcessedCount;
    @Getter
    private int greetingSentCount;
    @Getter
    private int greetingSkippedCount;
    @Getter
    private int greetingFailedCount;
    // 当前页从JSON拦截到的jobId列表
    private final java.util.List<Long> currentPageJobIds = new java.util.ArrayList<>();

    private static final int DEFAULT_MAX_PAGE = 50;
    private static final String BASE_URL = "https://we.51job.com/pc/search?";
    private static final long DETAIL_TIMEOUT_MS = 15000L;
    private static final long MESSAGE_CONFIRM_TIMEOUT_MS = 12000L;

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    /**
     * 准备工作：加载配置、初始化数据
     */
    public void prepare() {
        resultList.clear();
        executionError = null;
        stopReason = null;
        selectedCount = 0;
        confirmedSuccessCount = 0;
        aiProcessedCount = 0;
        greetingSentCount = 0;
        greetingSkippedCount = 0;
        greetingFailedCount = 0;
        currentPageNum = 0;
        reachedDailyLimit = false;
        synchronized (currentPageJobIds) {
            currentPageJobIds.clear();
        }
    }

    /**
     * 执行投递任务
     * @return 投递数量
     */
    public int execute() {
        long startTime = System.currentTimeMillis();

        try {
            // 检查配置是否有效
            if (config == null) {
                log.error("[51job] 配置为空，无法执行投递任务");
                sendProgress("配置为空，无法执行投递任务", null, null);
                return 0;
            }
            
            if (config.getKeywords() == null || config.getKeywords().isEmpty()) {
                log.warn("[51job] 关键词列表为空，无法执行投递任务");
                sendProgress("关键词列表为空，请先配置搜索关键词", null, null);
                return 0;
            }
            
            if (config.isAiEnabled()) {
                sendProgress(String.format("AI逐岗位模式已启用，本次最多处理%d个岗位，间隔%d-%d秒",
                        config.effectiveMaxPerRun(), config.effectiveMinDelaySeconds(), config.effectiveMaxDelaySeconds()), null, null);
                executeAiDelivery();
            } else {
                // 遍历所有关键词进行投递
                for (String keyword : config.getKeywords()) {
                    if (shouldStop()) {
                        stopReason = "user_cancelled";
                        sendProgress("用户取消投递", null, null);
                        break;
                    }

                    String searchUrl = buildSearchUrl(keyword);
                    deliverByKeyword(keyword, searchUrl);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            String message = config.isAiEnabled()
                    ? String.format("51job AI打招呼完成，发送%d个，跳过%d个，失败%d个，用时%s",
                    greetingSentCount, greetingSkippedCount, greetingFailedCount, formatDuration(duration))
                    : String.format("51job投递完成，共投递%d个简历，用时%s",
                    resultList.size(), formatDuration(duration));
            sendProgress(message, null, null);

        } catch (Exception e) {
            executionError = messageOf(e, "投递过程异常");
            log.error("51job投递过程出现异常", e);
            sendProgress("投递出现异常: " + e.getMessage(), null, null);
        }

        return resultList.size();
    }

    private void executeAiDelivery() {
        int maxPerRun = config.effectiveMaxPerRun();
        for (String keyword : config.getKeywords()) {
            if (shouldStop() || aiProcessedCount >= maxPerRun) break;
            deliverAiByKeyword(keyword, buildSearchUrl(keyword), maxPerRun);
        }
        if (shouldStop()) {
            stopReason = "user_cancelled";
            sendProgress("用户取消 AI 打招呼任务", null, null);
        } else if (aiProcessedCount >= maxPerRun) {
            sendProgress(String.format("已达到本次 AI 岗位处理上限%d个", maxPerRun), null, null);
        }
    }

    /**
     * 按关键词投递
     */
    private void deliverByKeyword(String keyword, String searchUrl) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensurePageReady();
                deliverByKeywordInternal(keyword, searchUrl);
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

    private void deliverByKeywordInternal(String keyword, String searchUrl) {
        try {
            // 收敛日志：不输出关键词级日志，仅保留页级摘要

            // 在跳转前监听 51job 搜索接口，抓取 JSON 并保存到数据库 + 打印诊断日志
            if (!networkHooked) {
                try {
                    page.onResponse(r -> {
                        try {
                            String url = r.url();
                            if (url != null && url.contains("/api/job/search-pc") && "GET".equalsIgnoreCase(r.request().method())) {
                                int status = 0;
                                try { status = r.status(); } catch (Throwable ignored) {}
                                String text = null;
                                try { text = r.text(); } catch (Throwable ignored) {}
                                int len = text == null ? 0 : text.length();
                                // 基于 URL 的 requestId 做去重，避免重复解析
                                String requestId = null;
                                try {
                                    java.net.URI u = new java.net.URI(url);
                                    String q = u.getQuery();
                                    if (q != null) {
                                        for (String part : q.split("&")) {
                                            int i = part.indexOf('=');
                                            if (i > 0 && "requestId".equals(part.substring(0, i))) {
                                                requestId = java.net.URLDecoder.decode(part.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8);
                                                break;
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                                if (requestId != null && !requestId.isBlank() && processedRequestIds.contains(requestId)) {
                                    return;
                                }
                                if (text != null) {
                                    // 根据 Content-Type 粗判是否为 JSON
                                    boolean isJson = false;
                                    try {
                                        java.util.Map<String, String> headers = r.headers();
                                        if (headers != null) {
                                            String ct = headers.getOrDefault("content-type", headers.get("Content-Type"));
                                            if (ct != null && ct.toLowerCase().contains("json")) isJson = true;
                                        }
                                    } catch (Throwable ignored) {}
                                    if (isJson) {
                                        // 解析并保存到数据库
                                        job51Service.parseAndPersistJob51SearchJson(text);
                                        // 📋 提取当前页的jobId列表并缓存
                                        List<Long> jobIds = extractJobIdsFromJson(text);
                                        if (jobIds != null && !jobIds.isEmpty()) {
                                            synchronized (currentPageJobIds) {
                                                currentPageJobIds.clear();
                                                currentPageJobIds.addAll(jobIds);
                                            }
                                        }
                                        if (requestId != null && !requestId.isBlank()) processedRequestIds.add(requestId);
                                    } // 非JSON静默跳过
                                }
                            }
                        } catch (Throwable e) {
                            // 静默错误
                        }
                    });
                    networkHooked = true;
                } catch (Throwable e) {
                    // 静默错误
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
            for (int pageNum = 1; pageNum <= DEFAULT_MAX_PAGE; pageNum++) {
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
                    break;
                }

                PlaywrightUtil.sleep(2);

                // 检查是否出现访问验证
                if (checkAccessVerification()) {
                    sendProgress("出现访问验证，停止投递", null, null);
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
                if (reachedDailyLimit) break;

                PlaywrightUtil.sleep(3);
            }

            // 关键词完成不输出日志
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("51job关键词投递失败: " + e.getMessage(), e);
        }
    }

    private void deliverAiByKeyword(String keyword, String searchUrl, int maxPerRun) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensurePageReady();
                deliverAiByKeywordInternal(keyword, searchUrl, maxPerRun);
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

            String text = null;
            try {
                text = response.text();
            } catch (Throwable ignored) {
            }
            if (text == null || text.isBlank()) return;

            boolean isJson = false;
            try {
                java.util.Map<String, String> headers = response.headers();
                String contentType = headers == null ? null : headers.getOrDefault("content-type", headers.get("Content-Type"));
                isJson = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
            } catch (Throwable ignored) {
            }
            if (!isJson) return;

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
            if (requestId != null && !requestId.isBlank()) processedRequestIds.add(requestId);
        } catch (Throwable ignored) {
            // 页面关闭期间响应回调可能晚到，忽略单次回调错误。
        }
    }

    private void deliverAiByKeywordInternal(String keyword, String searchUrl, int maxPerRun) {
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

        for (int pageNum = 1; pageNum <= DEFAULT_MAX_PAGE; pageNum++) {
            if (shouldStop() || aiProcessedCount >= maxPerRun) return;

            currentPageNum = pageNum;
            sendProgress(String.format("AI正在处理第%d页", pageNum), pageNum, DEFAULT_MAX_PAGE);
            synchronized (currentPageJobIds) {
                currentPageJobIds.clear();
            }
            if (pageNum > 1 && !jumpToPage(pageNum)) return;

            PlaywrightUtil.sleep(2);
            if (checkAccessVerification()) {
                sendProgress("出现访问验证，停止 AI 打招呼", null, null);
                return;
            }
            if (detectNoJobs51job()) return;

            int processed = deliverAiCurrentPage(keyword, maxPerRun);
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
        if (pageJobIds.size() < cardCount) {
            List<Long> domJobIds = collectJobIdsOnPage();
            if (!domJobIds.isEmpty()) pageJobIds = domJobIds;
        }

        int processed = 0;
        for (int i = 0; i < cardCount && aiProcessedCount < maxPerRun; i++) {
            if (shouldStop()) {
                stopReason = "user_cancelled";
                return processed;
            }

            Locator card = cards.nth(i);
            if (isAlreadyAppliedCard(card)) continue;

            Long jobId = i < pageJobIds.size() ? pageJobIds.get(i) : readJobIdFromCard(card);
            Job51Entity entity = job51Service.findByJobId(jobId);
            if (entity == null && jobId == null) {
                greetingSkippedCount++;
                continue;
            }

            String title = firstText(card, ".jname", "[class*='jname']");
            if (title == null || title.isBlank()) {
                title = entity == null ? "" : entity.getJobTitle();
            }
            String company = firstText(card, ".cname", "[class*='cname']");
            if (company == null || company.isBlank()) {
                company = entity == null ? "" : entity.getCompName();
            }
            if (entity == null) {
                greetingSkippedCount++;
                sendProgress(String.format("跳过岗位%s：未找到岗位快照", title), null, null);
                continue;
            }
            if (Job51Service.GREETING_SENT.equalsIgnoreCase(entity.getGreetingStatus())) continue;

            aiProcessedCount++;
            processed++;
            AiJobOutcome outcome = processAiJob(keyword, entity, company, title);
            if (outcome == AiJobOutcome.SENT) {
                greetingSentCount++;
                resultList.add(company + " | " + title);
                sendProgress(String.format("已发送 AI 打招呼：%s | %s", company, title), null, null);
            } else if (outcome == AiJobOutcome.SKIPPED) {
                greetingSkippedCount++;
            } else {
                greetingFailedCount++;
            }

            if (aiProcessedCount < maxPerRun && i + 1 < cardCount) {
                waitBetweenAiJobs();
            }
        }
        return processed;
    }

    private AiJobOutcome processAiJob(String keyword, Job51Entity entity, String company, String title) {
        Page detailPage = null;
        try {
            String link = normalizeJobLink(entity.getJobLink());
            if (link == null) {
                markGreetingSkipped(entity.getJobId(), "job_link_missing");
                sendProgress(String.format("跳过%s：缺少岗位详情链接", title), null, null);
                return AiJobOutcome.SKIPPED;
            }

            detailPage = page.context().newPage();
            detailPage.setDefaultTimeout(10000);
            detailPage.navigate(link, new Page.NavigateOptions()
                    .setTimeout(DETAIL_TIMEOUT_MS)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            try {
                detailPage.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(10000));
            } catch (Exception ignored) {
            }

            String jd = entity.getJobDescription();
            if (jd == null || jd.isBlank()) jd = extractJobDescription(detailPage);
            if (jd == null || jd.isBlank()) {
                markGreetingSkipped(entity.getJobId(), "jd_missing");
                sendProgress(String.format("跳过%s：未读取到岗位JD", title), null, null);
                return AiJobOutcome.SKIPPED;
            }
            if (entity.getJobDescription() == null || !jd.equals(entity.getJobDescription())) {
                entity.setJobDescription(jd);
                job51Service.updateJobDescription(entity.getJobId(), jd);
            }

            AiGreetingResult aiResult = generateAiGreeting(keyword, title, jd);
            if (!isUsableAiGreeting(aiResult)) {
                String reason = aiResult != null && aiResult.rejected() ? "ai_rejected" : "ai_empty_or_failed";
                markGreetingSkipped(entity.getJobId(), reason);
                sendProgress(String.format("跳过%s：AI未生成有效打招呼语", title), null, null);
                return AiJobOutcome.SKIPPED;
            }

            if (!sendGreetingInDetail(detailPage, aiResult.message())) {
                job51Service.updateGreetingState(entity.getJobId(), Job51Service.GREETING_FAILED, "message_not_confirmed");
                sendProgress(String.format("AI打招呼发送未确认：%s", title), null, null);
                return AiJobOutcome.FAILED;
            }
            job51Service.updateGreetingState(entity.getJobId(), Job51Service.GREETING_SENT, null);
            return AiJobOutcome.SENT;
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) throw propagate(e);
            markGreetingSkipped(entity.getJobId(), "ai_or_detail_error: " + messageOf(e, "unknown"));
            log.warn("51job AI岗位处理失败 job_id={} title={}: {}", entity.getJobId(), title, e.getMessage());
            return AiJobOutcome.SKIPPED;
        } finally {
            if (detailPage != null) {
                try {
                    detailPage.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void markGreetingSkipped(Long jobId, String reason) {
        job51Service.updateGreetingState(jobId, Job51Service.GREETING_SKIPPED, reason);
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
            if (card.locator(".ick.sel-no, .ick.sel-yes").count() > 0) return true;
            String text = card.innerText();
            return text != null && text.contains("已申请");
        } catch (Exception e) {
            return false;
        }
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

    private String normalizeJobLink(String link) {
        if (link == null || link.isBlank()) return null;
        String value = link.trim();
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("/")) return "https://jobs.51job.com" + value;
        return value.startsWith("http://") || value.startsWith("https://") ? value : null;
    }

    private String extractJobDescription(Page detailPage) {
        for (String selector : Job51Locators.DESCRIPTION_SELECTORS) {
            Locator candidates = detailPage.locator(selector);
            for (int i = 0; i < Math.min(candidates.count(), 5); i++) {
                try {
                    Locator candidate = candidates.nth(i);
                    if (!candidate.isVisible()) continue;
                    String text = normalizeJobDescription(candidate.innerText());
                    if (text != null && text.length() >= 20) return text;
                } catch (Exception ignored) {
                }
            }
        }
        try {
            String body = normalizeJobDescription(detailPage.locator("body").innerText());
            if (body != null && (body.contains("职位描述") || body.contains("岗位职责") || body.contains("任职要求"))) return body;
        } catch (Exception ignored) {
        }
        return null;
    }

    private String normalizeJobDescription(String text) {
        if (text == null) return null;
        String value = text.replace('\u00A0', ' ').replaceAll("[\\t\\r ]+", " ").trim();
        return value.isBlank() ? null : value;
    }

    private AiGreetingResult generateAiGreeting(String keyword, String jobName, String jd) {
        try {
            AiEntity aiConfig = aiService.getAiConfig();
            String introduce = aiConfig == null || aiConfig.getIntroduce() == null ? "" : aiConfig.getIntroduce();
            String prompt = aiConfig == null ? null : aiConfig.getPrompt();
            if (prompt == null || prompt.isBlank()) return new AiGreetingResult(null, false);
            String request = aiService.renderPrompt(prompt, introduce, keyword, jobName, jd, "");
            AiGreetingResult classified = classifyAiResponse(aiService.sendRequest(request));
            if (classified.rejected()) return classified;
            return classified;
        } catch (Exception e) {
            log.warn("51job AI请求失败，跳过当前岗位：{}", e.getMessage());
            return new AiGreetingResult(null, false);
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
        return result != null && !result.rejected() && result.message() != null && !result.message().isBlank();
    }

    private boolean sendGreetingInDetail(Page detailPage, String message) {
        Locator action = findVisibleTextAction(detailPage, Job51Locators.GREETING_TEXTS);
        if (action == null) return false;
        try {
            action.click();
            Locator input = waitForMessageInput(detailPage, 5000L);
            if (input == null) return false;
            int before = visibleOutgoingMessageCount(detailPage);
            input.fill(message);
            Locator send = findVisibleTextAction(detailPage, Job51Locators.SEND_TEXTS);
            if (send != null && send.isEnabled()) send.click();
            else input.press("Enter");
            return waitForOutgoingMessage(detailPage, before, message, MESSAGE_CONFIRM_TIMEOUT_MS);
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) throw propagate(e);
            log.debug("51job沟通消息发送失败: {}", e.getMessage());
            return false;
        }
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

    record AiGreetingResult(String message, boolean rejected) {
    }

    private enum AiJobOutcome {
        SENT, SKIPPED, FAILED
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

        List<String> selectedJobInfos = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            if (shouldStop()) {
                stopReason = "user_cancelled";
                return;
            }
            Locator checkbox = checkboxes.nth(i);
            checkbox.evaluate("el => el.click()");
            String title = i < titles.count() ? titles.nth(i).textContent() : "未知职位";
            String company = i < companies.count() ? companies.nth(i).textContent() : "未知公司";
            selectedJobInfos.add(company + " | " + title);
        }
        selectedCount += selectedJobInfos.size();

        PlaywrightUtil.sleep(1);
        page.evaluate("window.scrollTo(0, 0)");
        PlaywrightUtil.sleep(1);

        if (!clickBatchDeliverButton()) {
            return;
        }

        PlaywrightUtil.sleep(3);
        int successNum = handleDeliverySuccessDialog(selectedJobInfos, pageJobIds);
        handleSeparateDeliveryDialog();

        if (successNum == 0 && !reachedDailyLimit && !shouldStop()) {
            throw new IllegalStateException("点击批量投递后未确认成功结果");
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

                // 查找批量投递按钮
                Locator parent = page.locator("div.tabs_in");
                Locator buttons = parent.locator("button.p_but.all_apply");

                if (buttons.count() > 0) {
                    PlaywrightUtil.sleep(1);
                    buttons.first().click();
                    
                    // 🚨 点击后立即检测“日投递上限”提示（短暂出现，需快速多次检测）
                    for (int i = 0; i < 10; i++) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {} // 每200ms检测一次
                        if (detectDailyLimitToast51job()) {
                            reachedDailyLimit = true;
                            stopReason = "daily_limit";
                            log.warn("点击投递按钮后，检测到 51job 日投递上限提示，停止投递");
                            sendProgress("检测到日投递上限，任务已停止", null, null);
                            return false;
                        }
                    }
                    
                    success = true;
                } else {
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
                    reachedDailyLimit = true;
                    stopReason = "daily_limit";
                    log.warn("处理成功弹窗后，检测到 51job 日投递上限提示，停止当前页");
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
        try {
            Locator loginElement = page.locator("//a[contains(@class, 'uname')]");
            if (loginElement.count() > 0) {
                String text = loginElement.textContent();
                return text != null && text.contains("登录");
            }
            return false;
        } catch (Exception e) {
            return false;
        }
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
            if (msg.count() > 0) {
                java.util.List<String> texts = new java.util.ArrayList<>();
                try { texts = msg.allInnerTexts(); } catch (Exception ignored) {}
                for (String t : texts) {
                    if (t == null) continue;
                    String tt = t.replace('\n', ' ').trim();
                    for (String kw : kws) {
                        if (tt.contains(kw)) return true;
                    }
                }
            }
            Object foundObj = page.evaluate("() => { const kws = ['今日投递太多','您今日投递太多','休息一下明天再来','达到上限','次数过多']; const bodyText = document.body ? (document.body.innerText || '') : ''; return kws.some(k=>bodyText.includes(k)); }");
            if (foundObj instanceof Boolean) {
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
        try {
            Locator wafTitle = page.locator("//p[@class='waf-nc-title']");
            Locator wafScript = page.locator("script[name^='aliyunwaf_']");
            Locator verifyText = page.locator("text=访问验证, text=请按住滑块");
            if ((wafTitle.count() > 0 && wafTitle.first().isVisible()) || wafScript.count() > 0 || verifyText.count() > 0) {
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
        List<Long> jobIds = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return jobIds;
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            
            // 兼容多种列表命名
            com.fasterxml.jackson.databind.JsonNode list = root.path("data").path("items");
            if (!list.isArray()) list = root.path("data").path("jobList");
            if (!list.isArray()) list = root.path("data").path("list");
            if (!list.isArray()) list = root.path("data").path("jobs");
            if (!list.isArray()) list = root.path("resultbody").path("job").path("items");
            if (!list.isArray()) list = root.path("job").path("items");
            if (!list.isArray()) list = root.path("resultbody").path("items");
            
            if (!list.isArray()) {
                return jobIds;
            }
            
            // 提取每个jobId
            for (com.fasterxml.jackson.databind.JsonNode item : list) {
                com.fasterxml.jackson.databind.JsonNode jobIdNode = item.path("jobId");
                if (!jobIdNode.isMissingNode() && !jobIdNode.isNull()) {
                    try {
                        Long jobId = jobIdNode.asLong();
                        if (jobId != null && jobId > 0) {
                            jobIds.add(jobId);
                        }
                    } catch (Exception e) {
                        // 忽略单个解析失败
                    }
                }
            }
            
        } catch (Exception e) {
            log.warn("[51job] 解析JSON提取jobId失败: {}", e.getMessage());
        }
        
        return jobIds;
    }
}
