package com.getjobs.worker.liepin;

import com.getjobs.worker.utils.PlaywrightUtil;
import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.LiepinService;
import com.getjobs.application.service.AiService;
import com.getjobs.application.entity.LiepinEntity;
import com.getjobs.worker.manager.BrowserSessionSnapshot;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.manager.ReadRequestStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.LoadState;
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
    private static final long SEARCH_RESPONSE_TIMEOUT_MS = 12_000L;
    private static final long SEARCH_NAVIGATION_TIMEOUT_MS = 15_000L;
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
    private final Map<String, Integer> pendingRetryNextPageByKeyword = new HashMap<>();
    private Set<Long> pendingRetryJobIds;
    private static final String RETRY_REASON_AI_TIMEOUT = "AI_TIMEOUT";
    private static final String RETRY_REASON_AI_REQUEST_FAILED = "AI_REQUEST_FAILED";
    private static final String RETRY_REASON_NETWORK_UNCONFIRMED = "NETWORK_UNCONFIRMED";
    private static final String RETRY_REASON_NETWORK_INTERRUPTED = "NETWORK_INTERRUPTED";
    private static final String RETRY_REASON_NETWORK_SEND_FAILED = "NETWORK_SEND_FAILED";
    private int scannedJobCount;
    private int salaryEligibleCount;
    private int salarySkippedCount;
    private LiepinRateGuard rateGuard;
    @Setter
    private LiepinAccountPacing accountPacing;
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
    @Autowired
    private PlaywrightManager playwrightManager;
    @Autowired
    private LiepinHttpSearchClient liepinHttpSearchClient;
    private volatile ReadRequestStatus readRequestStatus =
            ReadRequestStatus.browser(false, "尚未读取猎聘搜索");

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
        LIMIT_REACHED,
        RETRY
    }

    public enum ExecutionOutcome {
        COMPLETED,
        USER_PAUSED,
        RATE_LIMITED
    }

    public record ExecutionResult(int deliveredCount, ExecutionOutcome outcome, String detail) {
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
            int cardIndex,
            LiepinEntity entity,
            String companyName,
            String jobName,
            String salary,
            String recruiterName,
            String jd
    ) {
    }

    private record LocatedChatButton(Locator locator, String text) {
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
        int aiTimeouts;
        int buttonFailures;
        int confirmationFailures;
        int aiRetryAttempts;
        int aiRetrySuccesses;
        int aiRetryable;
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
            aiTimeouts = 0;
            buttonFailures = 0;
            confirmationFailures = 0;
            aiRetryAttempts = 0;
            aiRetrySuccesses = 0;
            aiRetryable = 0;
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
            result.put("invalidResults", invalid);
            result.put("aiTimeouts", aiTimeouts);
            result.put("buttonFailures", buttonFailures);
            result.put("confirmationFailures", confirmationFailures);
            result.put("aiRetryAttempts", aiRetryAttempts);
            result.put("aiRetrySuccesses", aiRetrySuccesses);
            result.put("aiRetryable", aiRetryable);
            result.put("avgLatencyMs", latencySamples == 0 ? 0 : totalLatencyMs / latencySamples);
            return result;
        }
    }

    private final AiRunStats aiStats = new AiRunStats();
    private final Map<String, Integer> skipReasons = new LinkedHashMap<>();
    private boolean lastAiRequestTimedOut;
    private int pendingAiRetryJobs;

    @FunctionalInterface
    public interface GreetingConfirmation {
        GreetingAction confirm(GreetingRequest request);
    }

    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Runnable statsChangedCallback;
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
        scannedJobCount = 0;
        salaryEligibleCount = 0;
        salarySkippedCount = 0;
        aiStats.reset();
        skipReasons.clear();
        lastAiRequestTimedOut = false;
        pendingAiRetryJobs = 0;
        readRequestStatus = ReadRequestStatus.browser(false, "尚未读取猎聘搜索");
        if (rateGuard == null || pendingRetryJobIds == null) {
            this.rateGuard = LiepinRateGuard.production(
                    this::shouldStop,
                    this::info,
                    config,
                    accountPacing
            );
        }
        notifyStatsChanged();
    }

    /** 设置本次批量重试的固定目标集合；集合只在发送确认成功后缩减。 */
    public void setPendingRetryJobIds(Set<Long> jobIds) {
        pendingRetryJobIds = jobIds == null ? null : new HashSet<>(jobIds);
        pendingRetryNextPageByKeyword.clear();
    }

    public int getPendingRetryRemaining() {
        return pendingRetryJobIds == null ? 0 : pendingRetryJobIds.size();
    }

    public static boolean shouldRetryPendingJob(Set<Long> targetJobIds, Long jobId) {
        return targetJobIds == null || (jobId != null && targetJobIds.contains(jobId));
    }

    private boolean isPendingRetryMode() {
        return pendingRetryJobIds != null;
    }

    public ExecutionResult execute() {
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
            return new ExecutionResult(0, ExecutionOutcome.COMPLETED, "未配置关键词");
        }

        for (String keyword : keywords) {
            if (isPendingRetryMode() && pendingRetryJobIds.isEmpty()) {
                break;
            }
            if (shouldStop()) {
                info("收到停止指令，提前结束关键词循环");
                return new ExecutionResult(resultList.size(), ExecutionOutcome.USER_PAUSED, "收到停止指令");
            }
            int recoveryAttempts = 0;
            while (true) {
                try {
                    submit(keyword);
                    break;
                } catch (RateLimitSignalException signal) {
                    info(signal.getMessage());
                    return new ExecutionResult(resultList.size(), ExecutionOutcome.RATE_LIMITED, signal.getMessage());
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
            if (isPendingRetryMode() && pendingRetryJobIds.isEmpty()) {
                break;
            }
            if (shouldStop()) {
                return new ExecutionResult(resultList.size(), ExecutionOutcome.USER_PAUSED, "收到停止指令");
            }
            if (rateGuard != null && rateGuard.isStopped()) {
                return new ExecutionResult(resultList.size(), ExecutionOutcome.RATE_LIMITED, "节奏控制或平台风控中断");
            }
            if (resultList.size() >= config.effectiveMaxPerRun()) {
                info(String.format("已达到本次成功聊天上限%d个，结束全部关键词", config.effectiveMaxPerRun()));
                break;
            }
        }
        if (isPendingRetryMode() && pendingRetryJobIds.isEmpty()) {
            return new ExecutionResult(resultList.size(), ExecutionOutcome.COMPLETED, "批量重试目标已处理完成");
        }
        if (shouldStop()) {
            return new ExecutionResult(resultList.size(), ExecutionOutcome.USER_PAUSED, "收到停止指令");
        }
        if (rateGuard != null && rateGuard.isStopped()) {
            return new ExecutionResult(resultList.size(), ExecutionOutcome.RATE_LIMITED, "节奏控制或平台风控中断");
        }
        return new ExecutionResult(resultList.size(), ExecutionOutcome.COMPLETED, completionDetail());
    }

    public Map<String, Object> getAiSummary() {
        return aiStats.snapshot();
    }

    public Map<String, Integer> getSkipReasons() {
        return new LinkedHashMap<>(skipReasons);
    }

    /** 返回本轮岗位处理汇总，供结束状态和页面解释零成功原因。 */
    public Map<String, Object> getDeliverySummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scanned", scannedJobCount);
        summary.put("salaryEligible", salaryEligibleCount);
        summary.put("salarySkipped", salarySkippedCount);
        summary.put("otherSkipped", Math.max(0, scannedJobCount - salaryEligibleCount - salarySkippedCount));
        summary.put("delivered", resultList.size());
        summary.put("skipReasons", getSkipReasons());
        return summary;
    }

    public ReadRequestStatus getReadRequestStatus() {
        return readRequestStatus;
    }

    private String completionDetail() {
        if (skipReasons.isEmpty()) {
            return "未记录可跳过原因";
        }
        return skipReasons.entrySet().stream()
                .limit(3)
                .map(entry -> entry.getKey() + entry.getValue() + "个")
                .collect(java.util.stream.Collectors.joining("、", "主要跳过原因：", ""));
    }

    private void recordSkip(String reason) {
        String normalized = reason == null || reason.isBlank() ? "未说明原因" : reason;
        skipReasons.merge(normalized, 1, Integer::sum);
    }

    private String salarySkipReason(String reason) {
        return reason == null || reason.isBlank() ? "薪资筛选未通过" : reason;
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
    private static final Set<String> SEARCH_LIST_KEYS = Set.of(
            "jobcardlist", "joblist", "jobs", "items", "results", "records", "list");
    private static final String[] JOB_NODES = {
            "job", "jobInfo", "jobDetail", "position", "jobData", "detail"};
    private static final String[] COMPANY_NODES = {
            "comp", "company", "companyInfo", "enterprise"};
    private static final String[] RECRUITER_NODES = {
            "recruiter", "recruiterInfo", "hr", "hrInfo"};

    private boolean parseAndPersistLiepinData(String json) {
        SearchParseResult parsed = parseSearchEntities(json);
        if (!parsed.recognized()) {
            log.warn("解析猎聘搜索响应失败: {}", parsed.detail());
            return false;
        }

        lastApiEntities.clear();
        lastApiEntities.addAll(parsed.entities());
        // 批量持久化：重试模式只刷新目标快照，避免把新扫描岗位带入本次任务。
        try {
            List<LiepinEntity> snapshots = isPendingRetryMode()
                    ? lastApiEntities.stream()
                    .filter(entity -> shouldRetryPendingJob(pendingRetryJobIds, entity.getJobId()))
                    .toList()
                    : lastApiEntities;
            liepinService.insertSnapshotsIfNotExistsBatch(snapshots);
            for (LiepinEntity entity : snapshots) {
                liepinService.updateJobDescription(entity.getJobId(), entity.getJobDescription());
            }
        } catch (Exception e) {
            log.warn("批量保存猎聘岗位数据失败: {}", e.getMessage());
        }
        return true;
    }

    /** 将搜索响应的结构识别与字段映射集中到可回归测试的纯解析入口。 */
    static SearchParseResult parseSearchEntities(String json) {
        if (json == null || json.isBlank()) {
            return new SearchParseResult(false, List.of(), "搜索响应正文为空");
        }
        String trimmed = json.trim();
        if (trimmed.startsWith("<")) {
            return new SearchParseResult(false, List.of(), "搜索响应不是JSON正文");
        }

        try {
            JsonNode root = new ObjectMapper().readTree(trimmed);
            if (root == null || root.isNull()) {
                return new SearchParseResult(false, List.of(), "搜索响应JSON为空");
            }
            SearchListCandidate candidate = findJobList(root);
            if (candidate == null) {
                return new SearchParseResult(false, List.of(),
                        "岗位列表未找到，" + describeResponseShape(root, trimmed.length()));
            }

            List<LiepinEntity> entities = new ArrayList<>();
            for (JsonNode item : candidate.list()) {
                JsonNode job = firstObject(item, JOB_NODES);
                JsonNode company = firstObject(item, COMPANY_NODES);
                JsonNode recruiter = firstObject(item, RECRUITER_NODES);
                Long jobId = readJobId(item);
                if (jobId == null) continue;

                LiepinEntity entity = new LiepinEntity();
                entity.setJobId(jobId);
                entity.setJobTitle(readFirstText(job, item, "title", "jobTitle", "jobName"));
                entity.setJobLink(readFirstText(job, item, "link", "jobLink", "jobUrl", "url"));
                entity.setJobDescription(readFirstText(job, item,
                        "jobDesc", "description", "jobDescription", "postDescription",
                        "detail", "requirement", "jobIntro", "jobDuty", "jobRequirement"));
                entity.setJobSalaryText(readFirstText(job, item,
                        "salary", "salaryDesc", "salaryText", "provideSalaryString"));
                entity.setJobArea(readFirstText(job, item, "dq", "area", "jobArea", "cityName"));
                entity.setJobEduReq(readFirstText(job, item,
                        "requireEduLevel", "degree", "degreeString"));
                entity.setJobExpReq(readFirstText(job, item,
                        "requireWorkYears", "workYear", "workYearString"));
                entity.setJobPublishTime(readFirstText(job, item,
                        "refreshTime", "issueDate", "issueDateString", "updateDate"));

                entity.setCompId(readLongFromKeys(company,
                        "compId", "companyId", "compID", "company_id"));
                entity.setCompName(readFirstText(company, item,
                        "compName", "companyName", "fullCompanyName", "ctmName"));
                entity.setCompIndustry(readFirstText(company, item,
                        "compIndustry", "industry", "industryName"));
                entity.setCompScale(readFirstText(company, item,
                        "compScale", "companySize", "companySizeString"));

                entity.setHrId(readFirstText(recruiter, item,
                        "recruiterId", "hrUid", "hrId"));
                entity.setHrName(readFirstText(recruiter, item,
                        "recruiterName", "hrName"));
                entity.setHrTitle(readFirstText(recruiter, item,
                        "recruiterTitle", "hrPosition"));
                entity.setHrImId(readFirstText(recruiter, item, "imId", "imID"));
                entities.add(entity);
            }
            return new SearchParseResult(true, List.copyOf(entities),
                    "岗位列表路径=" + candidate.path());
        } catch (Exception e) {
            String message = e.getMessage();
            return new SearchParseResult(false, List.of(),
                    "搜索响应JSON解析异常: "
                            + (message == null || message.isBlank()
                            ? e.getClass().getSimpleName() : message));
        }
    }

    private static SearchListCandidate findJobList(JsonNode root) {
        String[][] paths = {
                {"data", "data", "jobCardList"}, {"data", "jobCardList"},
                {"data", "data", "jobList"}, {"data", "jobList"},
                {"data", "data", "jobs"}, {"data", "jobs"},
                {"data", "data", "items"}, {"data", "items"},
                {"data", "data", "results"}, {"data", "results"},
                {"result", "data", "jobCardList"}, {"result", "data", "jobList"},
                {"result", "data", "items"}, {"result", "jobCardList"},
                {"result", "jobList"}, {"result", "items"},
                {"resultData", "jobCardList"}, {"resultData", "jobList"},
                {"jobCardList"}, {"jobList"}, {"jobs"}, {"items"},
                {"results"}, {"list"}
        };
        for (String[] path : paths) {
            JsonNode node = atPath(root, path);
            if (isSupportedJobList(node)) {
                return new SearchListCandidate(node, String.join(".", path));
            }
        }
        return findArrayByKey(root, "$root");
    }

    private static JsonNode atPath(JsonNode root, String[] path) {
        JsonNode node = root;
        for (String part : path) {
            if (node == null || !node.isObject()) return null;
            node = node.path(part);
        }
        return node;
    }

    private static boolean isSupportedJobList(JsonNode node) {
        return node != null && node.isArray()
                && (node.size() == 0 || containsJobRecord(node));
    }

    private static SearchListCandidate findArrayByKey(JsonNode node, String path) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                String key = normalizeFieldName(field.getKey());
                if (SEARCH_LIST_KEYS.contains(key) && isSupportedJobList(value)) {
                    return new SearchListCandidate(value, path + "." + field.getKey());
                }
            }
            fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                SearchListCandidate nested = findArrayByKey(
                        field.getValue(), path + "." + field.getKey());
                if (nested != null) return nested;
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                SearchListCandidate nested = findArrayByKey(node.get(i), path + "[" + i + "]");
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static boolean containsJobRecord(JsonNode list) {
        for (JsonNode item : list) {
            if (readJobId(item) != null) return true;
        }
        return false;
    }

    private static Long readJobId(JsonNode item) {
        Long direct = readLongFromKeys(item,
                "jobId", "job_id", "jobID", "jobid", "jobIdStr", "postId", "positionId");
        if (direct != null) return direct;
        if (item == null || !item.isObject()) return null;
        for (String key : JOB_NODES) {
            JsonNode nested = item.path(key);
            Long value = readLongFromKeys(nested,
                    "jobId", "job_id", "jobID", "jobid", "jobIdStr", "postId", "positionId");
            if (value != null) return value;
        }
        return null;
    }

    private static Long readLongFromKeys(JsonNode item, String... keys) {
        if (item == null || !item.isObject()) return null;
        for (String key : keys) {
            Long value = readLong(item.path(key));
            if (value != null && value > 0) return value;
        }
        return null;
    }

    private static JsonNode firstObject(JsonNode parent, String... names) {
        if (parent == null || !parent.isObject()) return null;
        for (String name : names) {
            JsonNode child = parent.path(name);
            if (child.isObject()) return child;
        }
        return null;
    }

    private static String readFirstText(JsonNode primary, JsonNode fallback, String... names) {
        String value = readFirstText(primary, names);
        return value == null ? readFirstText(fallback, names) : value;
    }

    private static String readFirstText(JsonNode parent, String... names) {
        if (parent == null || !parent.isObject() || names == null) return null;
        for (String name : names) {
            String value = readText(parent.path(name));
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String readText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.asText(null);
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value)
                ? null : value.trim();
    }

    private static Long readLong(JsonNode node) {
        String value = readText(node);
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(value);
            return parsed == 0 ? null : parsed;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeFieldName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static String describeResponseShape(JsonNode root, int bodyLength) {
        if (root == null) return "响应长度=" + bodyLength + "，根节点为空";
        if (!root.isObject()) {
            return "响应长度=" + bodyLength + "，根节点类型=" + root.getNodeType();
        }
        StringBuilder keys = new StringBuilder();
        java.util.Iterator<String> fields = root.fieldNames();
        int count = 0;
        while (fields.hasNext() && count < 12) {
            if (count > 0) keys.append(',');
            keys.append(fields.next());
            count++;
        }
        return "响应长度=" + bodyLength + "，顶层字段=" + keys;
    }

    record SearchParseResult(boolean recognized, List<LiepinEntity> entities, String detail) {
    }

    private record SearchListCandidate(JsonNode list, String path) {
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
            throw new IllegalArgumentException("猎聘发送间隔需满足 30<=最小秒数<=最大秒数<=300");
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

    private void notifyStatsChanged() {
        if (statsChangedCallback == null) {
            return;
        }
        try {
            statsChangedCallback.run();
        } catch (RuntimeException e) {
            log.warn("发布猎聘运行统计失败: {}", e.getMessage());
        }
    }

    private void submit(String keyword) {
        // 清洗关键词：去掉前后引号与多余空白
        String cleanKeyword = keyword == null ? "" : keyword.replace("\"", "").trim();
        String cityCode = config.getCityCode() == null ? "" : config.getCityCode();
        String salaryCode = config.getSalary() == null ? "" : config.getSalary();
        int plannedStartPage = isPendingRetryMode()
                ? pendingRetryNextPageByKeyword.getOrDefault(cleanKeyword, 1)
                : LiepinPageProgress.nextStartPage(
                liepinService.getLastCompletedPage(cleanKeyword, cityCode, salaryCode)
        );

        boolean salaryFilterConfigured = salaryCode != null && !salaryCode.isBlank();
        if (salaryFilterConfigured) {
            LiepinService.WebSalaryRange webSalaryRange = LiepinService.toWebAnnualSalaryRange(salaryCode);
            if (webSalaryRange == null) {
                throw new IllegalArgumentException("猎聘薪资范围无法换算为网页自定义年薪");
            }
            if (!applyWebSalaryFilter(cleanKeyword, webSalaryRange)) return;
        } else if (!navigateAndCaptureSearchResponse(getSearchUrl(plannedStartPage - 1) + "&key=" + cleanKeyword)) {
            return;
        }

        if (!waitForSearchResults()) {
            recordSkip("搜索结果未加载");
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
            if (isPendingRetryMode()) {
                pendingRetryNextPageByKeyword.put(cleanKeyword, 1);
            } else {
                liepinService.clearPageProgress(cleanKeyword, cityCode, salaryCode);
            }
            startPage = 1;
            if (!salaryFilterConfigured) {
                if (!navigateAndCaptureSearchResponse(getSearchUrl(0) + "&key=" + cleanKeyword)) return;
                if (!waitForSearchResults()) {
                    recordSkip("搜索结果未加载");
                    info(String.format("【%s】搜索结果未加载出岗位卡片，跳过本次关键词", cleanKeyword));
                    return;
                }
                maxPage = 1;
                paginationBox = findPaginationBox();
                if (paginationBox != null) {
                    setMaxPage(paginationBox.locator("li"));
                }
            }
        } else if (startPage > 1) {
            info(String.format("从第【%d】页继续投递【%s】", startPage, cleanKeyword));
            if (salaryFilterConfigured && !navigateToPageByPagination(startPage, cleanKeyword)) return;
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
        if (!waitForJobCardsAttached()) {
            return;
        }
            info(String.format("正在投递【%s】第【%d】页...", cleanKeyword, i + 1));
            PageScanResult scanResult = submitJob(cleanKeyword);
            if (scanResult != PageScanResult.COMPLETED) {
                // 半页中断不推进进度，下次仍从本页重试（已聊岗位靠“继续聊”跳过）
                return;
            }
            info(String.format("已投递第【%d】页所有的岗位...", i + 1));
            if (isPendingRetryMode()) {
                pendingRetryNextPageByKeyword.put(cleanKeyword, i + 2 <= maxPage ? i + 2 : 1);
            } else {
                liepinService.saveLastCompletedPage(cleanKeyword, cityCode, salaryCode, i + 1);
                info(String.format("进度已保存：【%s】完成到第【%d】页", cleanKeyword, i + 1));
            }
            
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
                        if (!clickNextPageAndCaptureResponse(
                                btn.first(), cleanKeyword, i + 1, currentHttpSalaryCode())) return;
                    } else {
                        if (!clickNextPageAndCaptureResponse(
                                nextLi.first(), cleanKeyword, i + 1, currentHttpSalaryCode())) return;
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
        if (isPendingRetryMode()) {
            pendingRetryNextPageByKeyword.put(cleanKeyword, 1);
        } else {
            liepinService.clearPageProgress(cleanKeyword, cityCode, salaryCode);
        }
        if (finishedAllPages || startPage <= maxPage) {
            info(String.format("【%s】全部页处理完成%s",
                    cleanKeyword, isPendingRetryMode() ? "，批量重试游标已回到第1页" : "，进度已重置"));
        }
        info(String.format("【%s】关键词投递完成！", cleanKeyword));
    }

    /**
     * 先落到第一页，再通过网页薪资筛选器发起一次确认后的搜索请求。
     * 平台的自定义输入按年薪万元展示；本地月薪闸门仍是最终放行条件。
     */
    private boolean applyWebSalaryFilter(String keyword, LiepinService.WebSalaryRange salaryRange) {
        if (!navigateAndCaptureSearchResponse(getSearchUrl(0) + "&key=" + keyword)) {
            return false;
        }

        Locator salaryLabel = waitForVisibleExactText("薪资", 15000L);
        if (salaryLabel == null) {
            throw new IllegalStateException("猎聘页面未找到可见的“薪资”筛选控件");
        }
        try {
            salaryLabel.click();
        } catch (RuntimeException e) {
            throw new IllegalStateException("猎聘薪资筛选控件点击失败", e);
        }

        Locator customOption = waitForVisibleExactText("自定义", 5000L);
        if (customOption == null) {
            throw new IllegalStateException("猎聘薪资筛选中未找到可见的“自定义”选项");
        }
        try {
            customOption.click();
        } catch (RuntimeException e) {
            throw new IllegalStateException("猎聘“自定义”薪资选项点击失败", e);
        }

        Locator inputs = waitForVisibleSalaryInputs(5000L);
        if (inputs == null) {
            throw new IllegalStateException("猎聘“自定义”薪资输入框数量异常，期望可见的最低/最高两个输入框");
        }
        try {
            // 网页控件只接受整数万元；边界向外取整，保证不会把本地薪资范围收窄。
            inputs.nth(0).fill(formatWebValue(Math.floor(salaryRange.minWan())));
            inputs.nth(1).fill(formatWebValue(Math.ceil(salaryRange.maxWan())));
            assertWebSalaryInput(inputs.nth(0), salaryRange.minWan(), "最低");
            assertWebSalaryInput(inputs.nth(1), salaryRange.maxWan(), "最高");
        } catch (RuntimeException e) {
            throw new IllegalStateException("猎聘自定义薪资输入值回读失败", e);
        }

        Locator confirm = waitForVisibleExactText("确定", 5000L);
        if (confirm == null) {
            throw new IllegalStateException("猎聘自定义薪资弹层未找到可见的“确定”按钮");
        }
        if (!rateGuard.before(LiepinRateGuard.Action.SEARCH)) {
            return false;
        }
        String salaryCode = salaryCodeForHttpFallback(salaryRange);
        if (!captureSearchAfterAction(
                "自定义薪资确认",
                LiepinRateGuard.Action.SEARCH,
                confirm::click,
                keyword,
                0,
                salaryCode
        )) {
            info("猎聘自定义薪资确认后的搜索响应未捕获，保留当前关键词等待重试");
            return false;
        }

        verifyAppliedWebSalaryFilter(salaryRange);
        return true;
    }

    private Locator waitForVisibleExactText(String text, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                Locator matches = page.getByText(text, new Page.GetByTextOptions().setExact(true));
                for (int i = 0; i < matches.count(); i++) {
                    Locator candidate = matches.nth(i);
                    if (candidate.isVisible()) {
                        return candidate;
                    }
                }
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) {
                    throw pageClosed(e, false);
                }
            }
            page.waitForTimeout(100);
        }
        return null;
    }

    private Locator waitForVisibleSalaryInputs(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                Locator inputs = page.locator("input.ant-input-number-input:visible");
                if (inputs.count() == 2 && inputs.nth(0).isVisible() && inputs.nth(1).isVisible()) {
                    return inputs;
                }
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) {
                    throw pageClosed(e, false);
                }
            }
            page.waitForTimeout(100);
        }
        return null;
    }

    private void assertWebSalaryInput(Locator input, double expected, String label) {
        String actualText = input.inputValue();
        double actual;
        try {
            actual = Double.parseDouble(actualText);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("猎聘自定义薪资" + label + "值不是数字: " + actualText, e);
        }
        if (!Double.isFinite(actual)) {
            throw new IllegalStateException("猎聘自定义薪资" + label + "值不是有限数字: " + actualText);
        }
        boolean exact = Math.abs(actual - expected) <= 0.0001d;
        // 页面输入框会把非整数年薪万元归一为整数：最低值向下取整，最高值向上取整。
        boolean normalized = Math.abs(actual - Math.rint(actual)) <= 0.0001d
                && (("最低".equals(label)
                && actual <= expected + 0.0001d
                && expected - actual <= 1.0001d)
                || ("最高".equals(label)
                && actual >= expected - 0.0001d
                && actual - expected <= 1.0001d));
        if (!exact && !normalized) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "猎聘自定义薪资%s值回读不一致，期望%s，实际%s", label, expected, actualText));
        }
        if (normalized) {
            info(String.format(Locale.ROOT,
                    "猎聘自定义薪资%s值被平台归一为%s，继续校验最终范围覆盖配置",
                    label, actualText));
        }
    }

    private void verifyAppliedWebSalaryFilter(LiepinService.WebSalaryRange requested) {
        Locator inputs = page.locator("input.ant-input-number-input:visible");
        if (inputs.count() == 2) {
            try {
                double min = Double.parseDouble(inputs.nth(0).inputValue());
                double max = Double.parseDouble(inputs.nth(1).inputValue());
                if (!Double.isFinite(min) || !Double.isFinite(max)
                        || max < min
                        || min > requested.minWan() + 0.0001d
                        || max + 0.0001d < requested.maxWan()) {
                    throw new IllegalStateException(String.format(Locale.ROOT,
                            "猎聘网页薪资筛选确认后范围变窄，期望覆盖%s-%s万，实际%s-%s万",
                            requested.minText(), requested.maxText(), min, max));
                }
                if (Math.abs(min - requested.minWan()) > 0.0001d
                        || Math.abs(max - requested.maxWan()) > 0.0001d) {
                    info(String.format(Locale.ROOT,
                            "网页已确认自定义薪资，平台归一为%s-%s万；本地仍按月薪范围校验",
                            formatWebValue(min), formatWebValue(max)));
                }
                return;
            } catch (NumberFormatException e) {
                throw new IllegalStateException("猎聘自定义薪资确认后数值回读失败", e);
            }
        }
        if (!page.url().contains("salaryCode=")) {
            throw new IllegalStateException("猎聘自定义薪资确认后未找到数值回读或薪资查询参数");
        }
    }

    private String formatWebValue(double value) {
        return value == Math.rint(value)
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value);
    }

    /** 网页筛选后从第一页通过分页控件回到断点页，保留当前薪资筛选状态。 */
    private boolean navigateToPageByPagination(int targetPage, String keyword) {
        for (int pageNumber = 2; pageNumber <= targetPage; pageNumber++) {
            if (shouldStop()) {
                return false;
            }
            Locator paginationBox = findPaginationBox();
            if (paginationBox == null) {
                throw new IllegalStateException("网页薪资筛选后未找到分页控件，无法回到断点页");
            }
            Locator nextLi = paginationBox.locator(NEXT_PAGE);
            if (nextLi.count() == 0) {
                throw new IllegalStateException(String.format(
                        "网页薪资筛选后无法回到第%d页：下一页控件缺失", targetPage));
            }
            String classes = nextLi.first().getAttribute("class");
            if (classes != null && classes.contains("ant-pagination-disabled")) {
                throw new IllegalStateException(String.format(
                        "网页薪资筛选后无法回到第%d页：下一页控件已禁用", targetPage));
            }
            Locator button = nextLi.first().locator("button.ant-pagination-item-link");
            if (button.count() == 0) {
                button = nextLi.first();
            }
            if (!clickNextPageAndCaptureResponse(
                    button.first(), keyword, pageNumber - 1, currentHttpSalaryCode())) {
                return false;
            }
            if (!waitForSearchResults()) {
                recordSkip("搜索结果未加载");
                info(String.format("网页薪资筛选后回到第%d页时结果未加载", pageNumber));
                return false;
            }
        }
        return true;
    }

    /** 等待搜索结果卡片或任一分页变体出现；单页结果可以没有分页控件。 */
    private boolean waitForSearchResults() {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop()) {
                return false;
            }
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

    /** 岗位卡片等待必须响应用户停止，不能用一次性15秒的Playwright等待。 */
    private boolean waitForJobCardsAttached() {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop()) {
                return false;
            }
            try {
                if (page.locator(JOB_CARDS).count() > 0) {
                    return true;
                }
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) {
                    throw pageClosed(e, false);
                }
                log.debug("检查岗位卡片挂载状态失败: {}", e.getMessage());
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
     * hybrid 模式先用 HTTP 读取岗位 JSON，再用页面完成 DOM 加载和投递动作。
     * HTTP 读取失败时回到原有 Playwright 响应捕获链路。
     */
    private boolean navigateAndCaptureSearchResponse(String searchUrl) {
        lastApiEntities.clear();
        if (!rateGuard.before(LiepinRateGuard.Action.SEARCH)) return false;

        boolean httpAttempted = false;
        String httpFailureDetail = "";
        if (playwrightManager != null
                && liepinHttpSearchClient != null
                && playwrightManager.isHybridTransport()) {
            httpAttempted = true;
            LiepinHttpSearchClient.SearchResult httpResult = requestSearchOverHttp(searchUrl);
            if (httpResult != null && httpResult.success() && parseAndPersistLiepinData(httpResult.body())) {
                try {
                    page.navigate(searchUrl, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(SEARCH_NAVIGATION_TIMEOUT_MS));
                    rateGuard.completed(LiepinRateGuard.Action.SEARCH);
                    readRequestStatus = ReadRequestStatus.http("猎聘搜索HTTP读取成功");
                    return true;
                } catch (RuntimeException e) {
                    if (isTargetClosedFailure(e)) {
                        throw pageClosed(e, false);
                    }
                    httpFailureDetail = "HTTP读取成功但页面加载失败: " + messageOf(e);
                    log.warn("猎聘HTTP搜索结果已解析，但页面加载失败，回退浏览器响应链路: {}",
                            e.getMessage());
                }
            } else if (httpResult != null && httpResult.success()) {
                httpFailureDetail = "HTTP搜索响应未解析到岗位数据结构";
                log.warn("猎聘HTTP搜索响应结构异常，回退浏览器响应链路");
            } else {
                httpFailureDetail = httpResult == null
                        ? "HTTP搜索客户端未返回结果"
                        : httpResult.detail();
                log.warn("猎聘HTTP搜索失败，回退浏览器响应链路: {}", httpFailureDetail);
            }
        }

        try {
            Response response = page.waitForResponse(
                    this::isSearchResponse,
                    new Page.WaitForResponseOptions().setTimeout(SEARCH_RESPONSE_TIMEOUT_MS),
                    () -> page.navigate(searchUrl, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(SEARCH_NAVIGATION_TIMEOUT_MS))
            );
            validateSearchResponse(response);
            if (!parseSearchResponse(response)) {
                String browserDetail = "猎聘浏览器搜索响应未解析到岗位数据结构";
                readRequestStatus = ReadRequestStatus.browser(
                        httpAttempted, firstNonBlank(httpFailureDetail, browserDetail));
                log.warn("猎聘搜索响应结构异常，当前关键词保留等待重试: {}", browserDetail);
                return false;
            }
            readRequestStatus = ReadRequestStatus.browser(httpAttempted,
                    httpAttempted && !httpFailureDetail.isBlank()
                            ? httpFailureDetail
                            : "猎聘浏览器搜索响应读取成功");
            rateGuard.completed(LiepinRateGuard.Action.SEARCH);
            return true;
        } catch (RateLimitSignalException signal) {
            throw signal;
        } catch (RuntimeException e) {
            if (isTargetClosedFailure(e)) {
                throw pageClosed(e, false);
            }
            log.warn("猎聘搜索导航失败: {}", e.getMessage());
            throw e;
        }
    }

    private LiepinHttpSearchClient.SearchResult requestSearchOverHttp(String searchUrl) {
        return requestSearchOverHttp(searchUrl, "");
    }

    private LiepinHttpSearchClient.SearchResult requestSearchOverHttp(
            String searchUrl,
            String salaryCode
    ) {
        String keyword = queryParameter(searchUrl, "key");
        int currentPage = parsePageParameter(queryParameter(searchUrl, "currentPage"));
        String cityCode = config == null ? "" : config.getCityCode();
        BrowserSessionSnapshot snapshot = playwrightManager.getSessionSnapshot();
        return liepinHttpSearchClient.search(keyword, cityCode, currentPage, snapshot, salaryCode);
    }

    private LiepinHttpSearchClient.SearchResult requestSearchOverHttp(
            String keyword,
            int zeroBasedPage,
            String salaryCode
    ) {
        String cityCode = config == null ? "" : config.getCityCode();
        BrowserSessionSnapshot snapshot = playwrightManager.getSessionSnapshot();
        return liepinHttpSearchClient.search(keyword, cityCode, zeroBasedPage, snapshot, salaryCode);
    }

    private int parsePageParameter(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String queryParameter(String url, String name) {
        if (url == null || name == null) return "";
        String marker = name + "=";
        int start = url.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = url.indexOf('&', start);
        String raw = end < 0 ? url.substring(start) : url.substring(start, end);
        try {
            return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String currentHttpSalaryCode() {
        String pageSalaryCode = "";
        try {
            pageSalaryCode = page == null ? "" : queryParameter(page.url(), "salaryCode");
        } catch (Exception ignored) {
        }
        return firstNonBlank(pageSalaryCode, config == null ? "" : config.getSalary());
    }

    private String salaryCodeForHttpFallback(LiepinService.WebSalaryRange salaryRange) {
        String current = currentHttpSalaryCode();
        if (!current.isBlank()) {
            return current;
        }
        if (salaryRange == null) {
            return "";
        }
        long minK = Math.round(salaryRange.minWan() / 1.2d);
        long maxK = Math.round(salaryRange.maxWan() / 1.2d);
        return minK + "$" + maxK;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private void throwIfHttpRisk(LiepinHttpSearchClient.SearchResult result) {
        if (result == null || result.success()) {
            return;
        }
        boolean riskStatus = result.statusCode() == 403 || result.statusCode() == 429;
        boolean riskKind = result.failureKind() == LiepinHttpSearchClient.FailureKind.RISK_SIGNAL;
        if (!riskStatus && !riskKind) {
            return;
        }
        String detail = firstNonBlank(result.detail(), "HTTP搜索响应出现风控信号");
        if (rateGuard != null) {
            rateGuard.stopForSignal(detail);
        }
        throw new RateLimitSignalException(detail + "，本次任务已停止");
    }

    private boolean clickNextPageAndCaptureResponse(
            Locator nextButton,
            String keyword,
            int zeroBasedPage,
            String salaryCode
    ) {
        if (!rateGuard.before(LiepinRateGuard.Action.PAGE)) return false;
        return captureSearchAfterAction(
                "翻页",
                LiepinRateGuard.Action.PAGE,
                nextButton::click,
                keyword,
                zeroBasedPage,
                salaryCode
        );
    }

    private boolean isSearchResponse(Response response) {
        try {
            return isSearchResponseUrl(response.url());
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isSearchResponseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return false;
        String url = rawUrl.toLowerCase(Locale.ROOT);
        if (url.contains(SEARCH_API_FRAGMENT + "-cond-init") || url.contains("searchfront4c-cond-init")) {
            return false;
        }
        return url.contains("searchfront4c") && url.contains("search");
    }

    private boolean captureSearchAfterAction(
            String operation,
            LiepinRateGuard.Action action,
            Runnable trigger,
            String keyword,
            int zeroBasedPage,
            String salaryCode
    ) {
        try {
            Response response = page.waitForResponse(
                    this::isSearchResponse,
                    new Page.WaitForResponseOptions().setTimeout(SEARCH_RESPONSE_TIMEOUT_MS),
                    trigger
            );
            validateSearchResponse(response);
            if (!parseSearchResponse(response)) {
                throw new IllegalStateException(operation + "响应未解析到岗位数据结构");
            }
            readRequestStatus = ReadRequestStatus.browser(false, "猎聘" + operation + "浏览器搜索响应");
            rateGuard.completed(action);
            return true;
        } catch (RateLimitSignalException signal) {
            throw signal;
        } catch (RuntimeException e) {
            if (isTargetClosedFailure(e)) {
                throw pageClosed(e, false);
            }
            String browserDetail = operation + "浏览器响应未捕获: " + messageOf(e);
            log.warn("{}，尝试HTTP回退", browserDetail);
            LiepinHttpSearchClient.SearchResult fallback =
                    requestSearchOverHttp(keyword, zeroBasedPage, firstNonBlank(
                            queryParameter(page.url(), "salaryCode"), salaryCode));
            throwIfHttpRisk(fallback);
            if (fallback != null && fallback.success() && parseAndPersistLiepinData(fallback.body())) {
                readRequestStatus = new ReadRequestStatus(
                        "http", true, browserDetail + "；HTTP回退成功");
                rateGuard.completed(action);
                return true;
            }
            String fallbackDetail;
            if (fallback == null) {
                fallbackDetail = "HTTP回退无结果";
            } else if (fallback.success()) {
                fallbackDetail = "HTTP回退响应未解析到岗位数据结构";
            } else {
                fallbackDetail = firstNonBlank(fallback.detail(), "HTTP回退未返回可用结果");
            }
            readRequestStatus = ReadRequestStatus.browser(true, browserDetail + "；" + fallbackDetail);
            log.warn("猎聘{}失败，当前页保留等待重试: {}", operation, fallbackDetail);
            return false;
        }
    }

    private boolean parseSearchResponse(Response response) {
        if (response == null) {
            throw new IllegalStateException("猎聘搜索响应为空");
        }
        try {
            String contentType = response.headers().get("content-type");
            if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("json")) {
                throw new IllegalStateException("猎聘搜索响应不是JSON: " + contentType);
            }
            String text = response.text();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("猎聘搜索响应正文为空");
            }
            return parseAndPersistLiepinData(text);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取猎聘搜索响应失败: " + e.getMessage(), e);
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

    private String messageOf(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank()
                ? error == null ? "未知错误" : error.getClass().getSimpleName()
                : message;
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
    String getSearchUrl(int zeroBasedPage) {
        String baseUrl = "https://www.liepin.com/zhaopin/?";
        StringBuilder sb = new StringBuilder(baseUrl);
        // 初始导航不拼网页年薪档位；配置了薪资时由 applyWebSalaryFilter 通过页面控件确认。
        if (config.getCityCode() != null && !config.getCityCode().isEmpty()) {
            sb.append("city=").append(config.getCityCode()).append("&");
            sb.append("dq=").append(config.getCityCode()).append("&");
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
            scannedJobCount++;
            notifyStatsChanged();
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
                salarySkippedCount++;
                notifyStatsChanged();
                recordSkip("薪资数据缺失");
                info(String.format("已跳过第%d个岗位：薪资数据缺失", i + 1));
                continue;
            }
            if (!shouldRetryPendingJob(pendingRetryJobIds, apiEntity.getJobId())) {
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
                salarySkippedCount++;
                notifyStatsChanged();
                recordSkip(salarySkipReason(salaryCheck.reason));
                log.info("跳过猎聘岗位：jobId={}, 岗位={}, 公司={}, 薪资={}, 原因={}",
                        apiEntity.getJobId(), jobName, companyName, salary, salaryCheck.reason);
                info(String.format("已跳过【%s】【%s】【%s】：%s",
                        companyName, jobName, salary, salaryCheck.reason));
                continue;
            }
            salaryEligibleCount++;
            notifyStatsChanged();

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
                recordSkip("岗位处理异常");
                log.error("处理岗位卡片失败: {}", e.getMessage());
                continue;
            }
            
            // 查找聊一聊按钮
            Locator button = null;
            String buttonText = "";
            try {
                LocatedChatButton locatedButton = findChatButtonInCard(currentJobCard);
                if (locatedButton != null) {
                    button = locatedButton.locator();
                    buttonText = locatedButton.text();
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
                    recordSkip("已有会话");
                    info(String.format("【%s】【%s】已有会话，跳过本次发送", companyName, jobName));
                } else if (button != null) {
                    recordSkip("没有可用聊一聊按钮");
                    log.debug("跳过岗位（按钮文本不匹配）: 【{}】的【{}·{}】岗位，按钮文本: '{}'", companyName, jobName, salary, buttonText);
                } else {
                    recordSkip("没有可用聊一聊按钮");
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
                    recordSkip("缺少JD");
                    info(String.format("【%s】【%s】未读取到JD，直接跳过，未发送平台预设语", companyName, jobName));
                    continue;
                }
            }
            if (batchMode) {
                batchJobs.add(new PreparedJob(
                        jobIdForUpdate, i, apiEntity, companyName, jobName, salary, recruiterName, jd));
                aiStats.candidateCount++;
                notifyStatsChanged();
                continue;
            }

            AiGreetingResult aiResult = new AiGreetingResult(null, false);
            if (config.isAiEnabled()) {
                if (config.isAutoAiDeliveryEnabled()) {
                    // 自动模式交给结构化评分，避免旧的硬编码关键词或 FALSE 招呼语漏掉可尝试岗位。
                    aiStats.candidateCount++;
                    notifyStatsChanged();
                    LiepinAiAssessment assessment = generateAiAssessment(keyword, jobIdForUpdate, jobName, jd, "");
                    if (assessment == null) {
                        aiStats.invalid++;
                        notifyStatsChanged();
                        recordSkip("AI评分无效");
                        info(String.format("【%s】【%s】AI评分结果无效，直接跳过", companyName, jobName));
                        continue;
                    }
                    info(String.format("【%s】【%s】AI评分%d/100：%s",
                            companyName, jobName, assessment.score(), assessment.reason()));
                    if (!passesAutoAiScreening(config, assessment)) {
                        liepinService.clearSuspendedRetry(jobIdForUpdate);
                        aiStats.skipped++;
                        notifyStatsChanged();
                        recordSkip("AI评分未达阈值");
                        info(String.format("【%s】【%s】AI评分低于%d分，跳过自动投递",
                                companyName, jobName, config.effectiveAiMinScore()));
                        continue;
                    }
                    aiStats.passed++;
                    notifyStatsChanged();
                    aiResult = new AiGreetingResult(assessment.message(), false);
                } else {
                    if (!isRelevantJob(jobName, jd)) {
                        aiStats.skipped++;
                        notifyStatsChanged();
                        recordSkip("岗位方向不相关");
                        info(String.format("【%s】【%s】标题或JD与目标医疗设备方向不相关，直接跳过", companyName, jobName));
                        continue;
                    }
                    aiStats.candidateCount++;
                    notifyStatsChanged();
                    aiResult = generateAiGreeting(keyword, jobIdForUpdate, jobName, jd);
                    if (!isUsableAiGreeting(aiResult)) {
                        if (aiResult != null && aiResult.rejected()) {
                            aiStats.skipped++;
                        } else {
                            aiStats.invalid++;
                        }
                        notifyStatsChanged();
                        String reason = aiResult != null && aiResult.rejected() ? "AI判定不匹配" : "AI未生成有效话术";
                        if (aiResult != null && aiResult.rejected()) {
                            liepinService.clearSuspendedRetry(jobIdForUpdate);
                        }
                        recordSkip(reason);
                        info(String.format("【%s】【%s】%s，直接跳过，未发送平台预设语", companyName, jobName, reason));
                        continue;
                    }
                    aiStats.passed++;
                    notifyStatsChanged();
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
                recordSkip(action == GreetingAction.SKIP ? "人工确认跳过" : "任务暂停");
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
                        notifyStatsChanged();
                        if (jobIdForUpdate != null) {
                            liepinService.markDelivered(jobIdForUpdate);
                            if (pendingRetryJobIds != null) {
                                pendingRetryJobIds.remove(jobIdForUpdate);
                            }
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
                        aiStats.confirmationFailures++;
                        notifyStatsChanged();
                        liepinService.markSuspendedRetry(jobIdForUpdate, RETRY_REASON_NETWORK_UNCONFIRMED);
                        recordSkip("发送状态不确定");
                        info(String.format("【%s】【%s】发送状态不确定，未更新已投递状态", companyName, jobName));
                        info(String.format("【%s】【%s】本页发送未确认，保留当前页等待重试", companyName, jobName));
                        return pageResultAfterSend(false);
                    }
                    
                } catch (Exception e) {
                    if (isTargetClosedFailure(e)) {
                        liepinService.markSuspendedRetry(jobIdForUpdate, RETRY_REASON_NETWORK_INTERRUPTED);
                        throw pageClosed(e, true);
                    }
                    aiStats.buttonFailures++;
                    notifyStatsChanged();
                    liepinService.markSuspendedRetry(jobIdForUpdate, RETRY_REASON_NETWORK_SEND_FAILED);
                    recordSkip("按钮或点击失败");
                    log.error("点击按钮失败: {}", e.getMessage());
                    info(String.format("【%s】【%s】按钮或点击异常，保留当前页等待重试", companyName, jobName));
                    return PageScanResult.RETRY;
                }
        }
        if (batchMode) {
            PageScanResult batchResult = processBatchJobs(keyword, batchJobs);
            if (batchResult == PageScanResult.COMPLETED && pendingAiRetryJobs > 0) {
                info(String.format("本页有%d个岗位的AI请求超时，暂不保存页码，等待自动或手动重试",
                        pendingAiRetryJobs));
                return PageScanResult.RETRY;
            }
            return batchResult;
        }
        if (pendingAiRetryJobs > 0) {
            info(String.format("本页有%d个岗位的AI请求超时，暂不保存页码，等待自动或手动重试",
                    pendingAiRetryJobs));
            return PageScanResult.RETRY;
        }
        return PageScanResult.COMPLETED;
    }

    private PageScanResult processBatchJobs(String keyword, List<PreparedJob> jobs) {
        if (jobs.isEmpty()) {
            return PageScanResult.COMPLETED;
        }
        String candidateIntroduce = currentCandidateIntroduce();

        Map<String, LiepinAiBatchAssessment.Item> assessments = generateBatchAssessments(keyword, jobs);
        List<PreparedJob> accepted = new ArrayList<>();
        for (PreparedJob job : jobs) {
            String jobId = String.valueOf(job.jobId());
            LiepinAiBatchAssessment.Item assessment = assessments.get(jobId);
            if (assessment == null) {
                aiStats.invalid++;
                notifyStatsChanged();
                recordSkip("AI评分缺少岗位结果");
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
                notifyStatsChanged();
                accepted.add(job);
            } else if (!hardMismatch && assessment.score() >= config.effectiveAiReviewMinScore()
                    && ("REVIEW".equals(assessment.decision())
                    || ("PASS".equals(assessment.decision())
                    && assessment.score() < config.effectiveAiMinScore()))) {
                aiStats.review++;
                notifyStatsChanged();
                info(String.format("【%s】【%s】进入AI复核记录，不自动发送", job.companyName(), job.jobName()));
            } else {
                aiStats.skipped++;
                notifyStatsChanged();
                liepinService.clearSuspendedRetry(job.jobId());
                recordSkip("AI评分未达阈值");
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
            if (!isValidAiMessage(message, candidateIntroduce)) {
                aiStats.invalid++;
                notifyStatsChanged();
                liepinService.clearSuspendedRetry(job.jobId());
                recordSkip("AI话术校验失败");
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
                notifyStatsChanged();
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
                        notifyStatsChanged();
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
                notifyStatsChanged();
                LiepinAiAssessment fallback = generateAiAssessment(keyword, job.jobId(), job.jobName(), job.jd(), "");
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
        lastAiRequestTimedOut = false;
        int maxAttempts = aiAttemptLimit();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long started = System.currentTimeMillis();
            try {
                aiStats.screenCalls++;
                notifyStatsChanged();
                String raw = aiService.sendStructuredRequest(prompt, 0.1, 384);
                recordAiLatency(started);
                lastAiRequestTimedOut = false;
                if (attempt > 1) recordAiRetrySuccess();
                return LiepinAiBatchAssessment.parse(raw);
            } catch (Exception e) {
                recordAiLatency(started);
                if (isAiTimeoutFailure(e)) {
                    aiStats.aiTimeouts++;
                    lastAiRequestTimedOut = true;
                    notifyStatsChanged();
                    if (waitForAiRetry(attempt, maxAttempts, "评分")) {
                        continue;
                    }
                }
                log.warn("批量 AI 评分请求失败，岗位数={}, model={}: {}", jobs.size(), model, e.getMessage());
                return null;
            }
        }
        return null;
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
            if (cached != null && isValidAiMessage(cached.message(), candidate)) {
                results.put(String.valueOf(job.jobId()), cached.message());
                aiStats.cacheHits++;
                notifyStatsChanged();
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
                            || !isValidAiMessage(item.message(), candidate)) {
                        aiStats.invalid++;
                        notifyStatsChanged();
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
                notifyStatsChanged();
                LiepinAiBatchMessage.Result single = requestBatchMessages(
                        keyword, candidate, model, aiConfig, List.of(job), true);
                if (single == null) {
                    if (lastAiRequestTimedOut) {
                        markAiRetryable();
                    }
                    liepinService.markSuspendedRetry(job.jobId(), lastAiRequestTimedOut
                            ? RETRY_REASON_AI_TIMEOUT : RETRY_REASON_AI_REQUEST_FAILED);
                }
                if (single == null || single.items().size() != 1
                        || !jobId.equals(single.items().get(0).jobId())
                        || !isValidAiMessage(single.items().get(0).message(), candidate)) continue;
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
        Map<String, String> values = new HashMap<>();
        values.put("candidate", candidate);
        values.put("style_rules", "称呼自然，突出岗位相关技能，表达愿意沟通，不虚构事实");
        values.put("accepted_jobs", jobsJson(jobs));
        String template = aiConfig == null || aiConfig.getMessagePrompt() == null
                || aiConfig.getMessagePrompt().isBlank()
                ? AiService.DEFAULT_MESSAGE_PROMPT : aiConfig.getMessagePrompt();
        String prompt = aiService.renderNamedTemplate(template, values);
        if (repair) prompt += "\n只返回符合上述格式的JSON对象，message必须是单行且不超过60字。";
        lastAiRequestTimedOut = false;
        int maxAttempts = aiAttemptLimit();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long started = System.currentTimeMillis();
            try {
                aiStats.messageCalls++;
                notifyStatsChanged();
                String raw = aiService.sendStructuredRequest(prompt, 0.25, 512);
                recordAiLatency(started);
                lastAiRequestTimedOut = false;
                if (attempt > 1) recordAiRetrySuccess();
                return LiepinAiBatchMessage.parse(raw);
            } catch (Exception e) {
                recordAiLatency(started);
                if (isAiTimeoutFailure(e)) {
                    aiStats.aiTimeouts++;
                    lastAiRequestTimedOut = true;
                    notifyStatsChanged();
                    if (waitForAiRetry(attempt, maxAttempts, "话术")) {
                        continue;
                    }
                }
                log.warn("批量 AI 话术请求失败，岗位数={}, model={}: {}", jobs.size(), model, e.getMessage());
                return null;
            }
        }
        return null;
    }

    private PageScanResult sendPreparedJob(PreparedJob job, String message) {
        Locator button;
        try {
            button = findPreparedJobButton(job);
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) throw pageClosed(e, false);
            aiStats.buttonFailures++;
            notifyStatsChanged();
            recordSkip("页面重定位失败");
            log.warn("批量模式页面重定位失败，jobId={}, index={}: {}",
                    job.jobId(), job.cardIndex(), e.getMessage());
            return PageScanResult.RETRY;
        }
        if (button == null) {
            aiStats.buttonFailures++;
            notifyStatsChanged();
            recordSkip("按钮失效");
            info(String.format("【%s】【%s】发送前未找到有效的聊一聊按钮，保留当前页等待重试",
                    job.companyName(), job.jobName()));
            return PageScanResult.RETRY;
        }

        try {
            var boundingBox = button.boundingBox();
            if (boundingBox != null) {
                double centerX = boundingBox.x + boundingBox.width / 2;
                double centerY = boundingBox.y + boundingBox.height / 2;
                page.mouse().move(centerX, centerY);
            }
            if (!rateGuard.before(LiepinRateGuard.Action.SEND)) return PageScanResult.STOPPED;
            SendResult sendResult = clickAndObserveMessage(button, message);
            if (!sendResult.sent()) {
                aiStats.confirmationFailures++;
                notifyStatsChanged();
                liepinService.markSuspendedRetry(job.jobId(), RETRY_REASON_NETWORK_UNCONFIRMED);
                recordSkip("发送状态不确定");
                info(String.format("【%s】【%s】发送状态不确定，未更新已投递状态", job.companyName(), job.jobName()));
                info(String.format("【%s】【%s】本页发送未确认，保留当前页等待重试", job.companyName(), job.jobName()));
                return pageResultAfterSend(false);
            }
            resultList.add(String.format("【%s %s %s %s】", job.companyName(), job.jobName(),
                    job.salary(), job.recruiterName()));
            notifyStatsChanged();
            if (job.jobId() != null) {
                liepinService.markDelivered(job.jobId());
                if (pendingRetryJobIds != null) {
                    pendingRetryJobIds.remove(job.jobId());
                }
            }
            info(String.format("【%s】【%s】已发送AI话术%s", job.companyName(), job.jobName(),
                    sendResult.resumeApplied() ? "和简历" : ""));
            if (!rateGuard.afterSuccessfulSend(
                    resultList.size() < config.effectiveMaxPerRun())) return PageScanResult.STOPPED;
            if (resultList.size() >= config.effectiveMaxPerRun()) {
                info(String.format("已达到本次成功聊天上限%d个", config.effectiveMaxPerRun()));
                return PageScanResult.LIMIT_REACHED;
            }
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) {
                liepinService.markSuspendedRetry(job.jobId(), RETRY_REASON_NETWORK_INTERRUPTED);
                throw pageClosed(e, true);
            }
            aiStats.buttonFailures++;
            notifyStatsChanged();
            liepinService.markSuspendedRetry(job.jobId(), RETRY_REASON_NETWORK_SEND_FAILED);
            recordSkip("按钮或点击失败");
            log.error("批量模式点击按钮失败，保留当前页等待重试: {}", e.getMessage());
            return PageScanResult.RETRY;
        }
        return PageScanResult.COMPLETED;
    }

    private Locator findPreparedJobButton(PreparedJob job) {
        Locator card = findCurrentJobCard(job);
        if (card == null) return null;
        try {
            page.evaluate("(element) => element.scrollIntoView({behavior: 'instant', block: 'center'})",
                    card.elementHandle());
            card.hover(new Locator.HoverOptions().setTimeout(5000));
            LocatedChatButton located = findChatButtonInCard(card);
            if (located == null || !located.text().contains("聊一聊")) return null;
            return located.locator();
        } catch (Exception e) {
            if (isTargetClosedFailure(e)) throw pageClosed(e, false);
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("读取当前岗位卡片失败", e);
        }
    }

    private Locator findCurrentJobCard(PreparedJob job) {
        Locator cards = page.locator(JOB_CARDS);
        int count = cards.count();
        if (job.jobId() != null) {
            for (int i = 0; i < count; i++) {
                Locator candidate = cards.nth(i);
                try {
                    if (job.jobId().equals(extractJobIdFromCard(candidate))) return candidate;
                } catch (Exception e) {
                    if (isTargetClosedFailure(e)) throw pageClosed(e, false);
                }
            }
        }
        return job.cardIndex() >= 0 && job.cardIndex() < count ? cards.nth(job.cardIndex()) : null;
    }

    private LocatedChatButton findChatButtonInCard(Locator card) {
        String[] buttonSelectors = {
                "button.ant-btn.ant-btn-primary.ant-btn-round",
                "button.ant-btn.ant-btn-round.ant-btn-primary",
                "button[class*='ant-btn'][class*='primary']",
                "button[class*='ant-btn'][class*='round']",
                "button[class*='chat'], button[class*='talk']",
                ".chat-btn, .talk-btn, .contact-btn",
                "button:has-text('聊一聊')",
                "button"
        };
        LocatedChatButton fallback = null;
        for (String selector : buttonSelectors) {
            try {
                Locator buttons = card.locator(selector);
                for (int i = 0; i < buttons.count(); i++) {
                    Locator candidate = buttons.nth(i);
                    if (!candidate.isVisible()) continue;
                    String text = safeText(candidate.textContent());
                    if (text == null || text.isBlank()) continue;
                    LocatedChatButton located = new LocatedChatButton(candidate, text.trim());
                    if (located.text().contains("聊一聊")) return located;
                    if (fallback == null) fallback = located;
                }
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) throw pageClosed(e, false);
                log.debug("选择器 '{}' 查找失败: {}", selector, e.getMessage());
            }
        }
        return fallback;
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

    private boolean isValidAiMessage(String message, String introduce) {
        if (message == null || message.isBlank() || message.contains("```") || message.contains("{{")) {
            return false;
        }
        return AiService.validateGreeting(message, introduce).usable();
    }

    private String currentCandidateIntroduce() {
        AiEntity aiConfig = aiService.getAiConfig();
        return safeValue(aiConfig == null ? null : aiConfig.getIntroduce());
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

    private AiGreetingResult generateAiGreeting(String keyword, Long jobId, String jobName, String jd) {
        try {
            AiEntity aiConfig = aiService.getAiConfig();
            String introduce = aiConfig == null || aiConfig.getIntroduce() == null ? "" : aiConfig.getIntroduce();
            String prompt = aiConfig == null ? null : aiConfig.getPrompt();
            if (prompt == null || prompt.isBlank()) return new AiGreetingResult(null, false);

            String request = aiService.renderPrompt(prompt, introduce, keyword, jobName, jd, "");
            int maxAttempts = aiAttemptLimit();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long started = System.currentTimeMillis();
                try {
                    aiStats.messageCalls++;
                    notifyStatsChanged();
                    String result = aiService.sendRequest(request);
                    recordAiLatency(started);
                    if (attempt > 1) recordAiRetrySuccess();
                    AiGreetingResult classified = classifyAiResponse(result);
                    if (classified.rejected()) return classified;
                    AiService.GreetingValidation validation = AiService.validateGreeting(classified.message(), introduce);
                    return validation.usable()
                            ? new AiGreetingResult(validation.message(), false)
                            : new AiGreetingResult(null, false);
                } catch (Exception e) {
                    recordAiLatency(started);
                    if (isAiTimeoutFailure(e)) {
                        aiStats.aiTimeouts++;
                        notifyStatsChanged();
                        if (waitForAiRetry(attempt, maxAttempts, "话术")) {
                            continue;
                        }
                        markAiRetryable();
                        liepinService.markSuspendedRetry(jobId, RETRY_REASON_AI_TIMEOUT);
                    } else {
                        liepinService.markSuspendedRetry(jobId, RETRY_REASON_AI_REQUEST_FAILED);
                    }
                    log.warn("猎聘AI请求失败，将跳过当前岗位，不发送平台预设语: {}", e.getMessage());
                    return new AiGreetingResult(null, false);
                }
            }
        } catch (Exception e) {
            log.warn("猎聘AI请求准备失败，将跳过当前岗位，不发送平台预设语: {}", e.getMessage());
            return new AiGreetingResult(null, false);
        }
        return new AiGreetingResult(null, false);
    }

    private LiepinAiAssessment generateAiAssessment(String keyword, Long jobId, String jobName, String jd, String greeting) {
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
            int maxAttempts = aiAttemptLimit();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long started = System.currentTimeMillis();
                try {
                    aiStats.screenCalls++;
                    notifyStatsChanged();
                    String result = aiService.sendRequest(request);
                    recordAiLatency(started);
                    if (attempt > 1) recordAiRetrySuccess();
                    return LiepinAiAssessment.parse(result);
                } catch (Exception e) {
                    recordAiLatency(started);
                    if (isAiTimeoutFailure(e)) {
                        aiStats.aiTimeouts++;
                        notifyStatsChanged();
                        if (waitForAiRetry(attempt, maxAttempts, "评分")) {
                            continue;
                        }
                        markAiRetryable();
                        liepinService.markSuspendedRetry(jobId, RETRY_REASON_AI_TIMEOUT);
                    } else {
                        liepinService.markSuspendedRetry(jobId, RETRY_REASON_AI_REQUEST_FAILED);
                    }
                    log.warn("猎聘AI评分请求失败，将跳过当前岗位: {}", e.getMessage());
                    return null;
                }
            }
        } catch (Exception e) {
            log.warn("猎聘AI评分请求准备失败，将跳过当前岗位: {}", e.getMessage());
            return null;
        }
        return null;
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

    static PageScanResult pageResultAfterSend(boolean confirmed) {
        return confirmed ? PageScanResult.COMPLETED : PageScanResult.RETRY;
    }

    private int aiAttemptLimit() {
        if (!config.isAiTimeoutRetryEnabled()) {
            return 1;
        }
        return 1 + config.effectiveAiTimeoutMaxRetries();
    }

    private void recordAiLatency(long started) {
        aiStats.totalLatencyMs += Math.max(0L, System.currentTimeMillis() - started);
        aiStats.latencySamples++;
        notifyStatsChanged();
    }

    private void recordAiRetrySuccess() {
        aiStats.aiRetrySuccesses++;
        notifyStatsChanged();
    }

    private void markAiRetryable() {
        pendingAiRetryJobs++;
        aiStats.aiRetryable = pendingAiRetryJobs;
        notifyStatsChanged();
        info("AI超时自动重试已耗尽，当前岗位保留，任务结束后可手动重试");
    }

    private boolean waitForAiRetry(int attempt, int maxAttempts, String action) {
        if (attempt >= maxAttempts || shouldStop()) {
            return false;
        }
        int retryNumber = attempt;
        aiStats.aiRetryAttempts++;
        notifyStatsChanged();
        int delaySeconds = config.effectiveAiTimeoutRetryDelaySeconds() * attempt;
        info(String.format("AI%s响应超时，准备第%d/%d次自动重试，等待%d秒",
                action, retryNumber, maxAttempts - 1, delaySeconds));
        long deadline = System.currentTimeMillis() + delaySeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop()) {
                return false;
            }
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(250L, Math.max(1L, remaining)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    static boolean isAiTimeoutFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
                if (shouldStop()) {
                    closeChatWindow();
                    return new SendResult(false, false, false);
                }
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
            if (shouldStop()) {
                return false;
            }
            page.waitForTimeout(200);
        }
        return false;
    }

    private boolean sendAiMessageInChat(String message) {
        Locator input = page.locator("textarea[placeholder*='请输入文字']").first();
        if (!waitForVisible(input, 5000)) {
            return false;
        }
        int before = visibleOutgoingMessageCount();
        input.fill(message);
        input.press("Enter");
        return waitForNewOutgoingMessage(before, 10000);
    }

    private boolean waitForVisible(Locator locator, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop()) {
                return false;
            }
            try {
                if (locator.count() > 0 && locator.isVisible()) {
                    return true;
                }
            } catch (Exception e) {
                if (isTargetClosedFailure(e)) {
                    throw pageClosed(e, true);
                }
            }
            page.waitForTimeout(200);
        }
        return false;
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
                if (shouldStop()) {
                    closeVisibleResumeModal();
                    return false;
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
