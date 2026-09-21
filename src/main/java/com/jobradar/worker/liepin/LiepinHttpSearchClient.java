package com.jobradar.worker.liepin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.worker.manager.BrowserSessionSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/** Direct read client for the Liepin search JSON endpoint. */
@Component
public class LiepinHttpSearchClient {
    public static final String DEFAULT_ENDPOINT =
            "https://api-c.liepin.com/api/com.liepin.searchfront4c.pc-search-job";
    private static final String SEARCH_REFERER = "https://www.liepin.com/zhaopin/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";
    private static final String SEARCH_PAGE_URL = "https://www.liepin.com/zhaopin/";
    private static final String CLIENT_TYPE = "web";
    private static final String STD_INFO = "{\"client_id\": \"40108\"}";
    private static final int DEFAULT_TIMEOUT_MS = 10_000;

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;

    public enum FailureKind {
        NONE,
        HTTP_STATUS,
        EMPTY_BODY,
        RISK_SIGNAL,
        INVALID_JSON,
        TRANSPORT
    }

    public record SearchResult(
            boolean success,
            int statusCode,
            String body,
            FailureKind failureKind,
            String detail
    ) {
        public static SearchResult success(int statusCode, String body) {
            return new SearchResult(true, statusCode, body, FailureKind.NONE, "");
        }

        public static SearchResult failure(int statusCode, FailureKind kind, String detail) {
            return new SearchResult(false, statusCode, "", kind, detail);
        }
    }

    @Autowired
    public LiepinHttpSearchClient(
            @Value("${jobradar.liepin.search-endpoint:" + DEFAULT_ENDPOINT + "}") String endpoint,
            @Value("${jobradar.liepin.search-timeout-ms:" + DEFAULT_TIMEOUT_MS + "}") int timeoutMs
    ) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                URI.create(normalizeEndpoint(endpoint)),
                Duration.ofMillis(Math.max(1, timeoutMs)),
                new ObjectMapper());
    }

    public LiepinHttpSearchClient(HttpClient httpClient, URI endpoint, Duration requestTimeout) {
        this(httpClient, endpoint, requestTimeout, new ObjectMapper());
    }

    LiepinHttpSearchClient(HttpClient httpClient, URI endpoint, Duration requestTimeout, ObjectMapper objectMapper) {
        if (httpClient == null) throw new IllegalArgumentException("HttpClient 不能为空");
        if (endpoint == null) throw new IllegalArgumentException("猎聘搜索端点不能为空");
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("猎聘搜索请求超时必须大于0");
        }
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public SearchResult search(
            String keyword,
            String cityCode,
            int zeroBasedPage,
            BrowserSessionSnapshot session
    ) {
        return search(keyword, cityCode, zeroBasedPage, session, "");
    }

    SearchResult search(
            String keyword,
            String cityCode,
            int zeroBasedPage,
            BrowserSessionSnapshot session,
            String salaryCode
    ) {
        try {
            HttpRequest request = buildRequest(keyword, cityCode, zeroBasedPage, session, salaryCode);
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body();
            if (status < 200 || status >= 300) {
                return SearchResult.failure(status, FailureKind.HTTP_STATUS,
                        "HTTP搜索响应状态异常: " + status);
            }
            if (body == null || body.isBlank()) {
                return SearchResult.failure(status, FailureKind.EMPTY_BODY, "HTTP搜索响应正文为空");
            }
            String signal = Liepin.detectRiskSignal(body);
            if (signal != null) {
                return SearchResult.failure(status, FailureKind.RISK_SIGNAL,
                        "HTTP搜索响应出现风控信号: " + signal);
            }
            if (!looksLikeJson(body)) {
                return SearchResult.failure(status, FailureKind.INVALID_JSON,
                        "HTTP搜索响应不是有效JSON");
            }
            return SearchResult.success(status, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SearchResult.failure(0, FailureKind.TRANSPORT, "HTTP搜索请求被中断");
        } catch (IOException | RuntimeException e) {
            return SearchResult.failure(0, FailureKind.TRANSPORT,
                    "HTTP搜索请求失败: " + messageOf(e));
        }
    }

    HttpRequest buildRequest(
            String keyword,
            String cityCode,
            int zeroBasedPage,
            BrowserSessionSnapshot session
    ) {
        return buildRequest(keyword, cityCode, zeroBasedPage, session, "");
    }

    HttpRequest buildRequest(
            String keyword,
            String cityCode,
            int zeroBasedPage,
            BrowserSessionSnapshot session,
            String salaryCode
    ) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String normalizedCity = cityCode == null ? "" : cityCode.trim();
        String requestId = randomClientId();
        String cookieHeader = session == null ? "" : session.cookieHeader("liepin.com");
        String xsrf = firstNonBlank(
                session == null ? null : session.value("XSRF-TOKEN", "liepin.com"),
                session == null ? null : session.value("xsrf-token", "liepin.com"));

        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Origin", "https://www.liepin.com")
                .header("Referer", SEARCH_PAGE_URL)
                .header("X-Client-Type", CLIENT_TYPE)
                .header("X-Fscp-Bi-Stat", writeJson(Map.of(
                        "location", searchLocation(normalizedKeyword, normalizedCity, zeroBasedPage, salaryCode))))
                .header("X-Fscp-Fe-Version", "")
                .header("X-Fscp-Std-Info", STD_INFO)
                .header("X-Fscp-Version", "1.1")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-Fscp-Trace-Id", runtimeId())
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildRequestBody(normalizedKeyword, normalizedCity, zeroBasedPage, requestId, salaryCode),
                        StandardCharsets.UTF_8));
        if (!cookieHeader.isBlank()) {
            request.header("Cookie", cookieHeader);
        }
        if (xsrf != null && !xsrf.isBlank()) {
            request.header("X-XSRF-TOKEN", xsrf);
        }
        return request.build();
    }

    String buildRequestBody(String keyword, String cityCode, int zeroBasedPage, String sessionId) {
        return buildRequestBody(keyword, cityCode, zeroBasedPage, sessionId, "");
    }

    String buildRequestBody(
            String keyword,
            String cityCode,
            int zeroBasedPage,
            String requestId,
            String salaryCode
    ) {
        String normalizedSalary = salaryCode == null ? "" : salaryCode.trim();
        String[] salaryBounds = customSalaryBounds(normalizedSalary);
        Map<String, Object> main = new LinkedHashMap<>();
        main.put("city", cityCode == null ? "" : cityCode);
        main.put("dq", cityCode == null ? "" : cityCode);
        if (salaryBounds == null) {
            main.put("pubTime", "");
        }
        main.put("currentPage", salaryBounds == null
                ? String.valueOf(Math.max(0, zeroBasedPage))
                : Math.max(0, zeroBasedPage));
        main.put("pageSize", 40);
        main.put("key", keyword == null ? "" : keyword);
        main.put("suggestTag", "");
        main.put("workYearCode", "");
        main.put("compId", "");
        main.put("compName", "");
        main.put("compTag", "");
        main.put("industry", "");
        main.put("salaryCode", "");
        main.put("jobKind", "");
        main.put("compScale", "");
        main.put("compKind", "");
        main.put("compStage", "");
        main.put("eduLevel", "");
        main.put("salaryLow", salaryBounds == null ? "" : salaryBounds[0]);
        main.put("salaryHigh", salaryBounds == null ? "" : salaryBounds[1]);
        if (salaryBounds != null) {
            main.put("otherCity", "");
            main.put("hrActiveTimeCode", "");
        }

        Map<String, Object> passThrough = new LinkedHashMap<>();
        if (salaryBounds == null) {
            passThrough.put("scene", "init");
            passThrough.put("skId", "");
            passThrough.put("fkId", "");
            passThrough.put("ckId", requestId);
        } else {
            passThrough.put("sfrom", "search_job_pc");
            passThrough.put("ckId", requestId);
            passThrough.put("skId", requestId);
            passThrough.put("fkId", requestId);
            passThrough.put("scene", "condition");
        }
        if (salaryBounds == null) {
            passThrough.put("suggest", null);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mainSearchPcConditionForm", main);
        data.put("passThroughForm", passThrough);
        return writeJson(Map.of("data", data));
    }

    private String searchLocation(String keyword, String cityCode, int zeroBasedPage, String salaryCode) {
        StringBuilder location = new StringBuilder(SEARCH_PAGE_URL)
                .append("?key=").append(encode(keyword))
                .append("&city=").append(encode(cityCode))
                .append("&currentPage=").append(Math.max(0, zeroBasedPage));
        if (salaryCode != null && !salaryCode.isBlank()) {
            location.append("&salaryCode=").append(encode(salaryCode.trim()));
        }
        return location.toString();
    }

    private static String[] customSalaryBounds(String salaryCode) {
        if (salaryCode == null || salaryCode.isBlank()) {
            return null;
        }
        String[] bounds = salaryCode.trim().split("\\$", -1);
        if (bounds.length != 2 || bounds[0].isBlank() || bounds[1].isBlank()) {
            return null;
        }
        return new String[]{bounds[0].trim(), bounds[1].trim()};
    }

    URI endpoint() {
        return endpoint;
    }

    private boolean looksLikeJson(String body) {
        try {
            objectMapper.readTree(body);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("构造猎聘搜索请求体失败", e);
        }
    }

    private static String normalizeEndpoint(String raw) {
        return raw == null || raw.isBlank() ? DEFAULT_ENDPOINT : raw.trim();
    }

    private static String runtimeId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String randomClientId() {
        final char[] alphabet = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        StringBuilder value = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            value.append(alphabet[ThreadLocalRandom.current().nextInt(alphabet.length)]);
        }
        return value.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String messageOf(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
