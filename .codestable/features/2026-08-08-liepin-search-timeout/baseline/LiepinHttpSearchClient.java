package com.getjobs.worker.liepin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.manager.BrowserSessionSnapshot;
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
import java.util.UUID;

/** Direct read client for the Liepin search JSON endpoint. */
@Component
public class LiepinHttpSearchClient {
    public static final String DEFAULT_ENDPOINT =
            "https://api-c.liepin.com/api/com.liepin.searchfront4c.pc-search-job";
    private static final String SEARCH_REFERER = "https://www.liepin.com/zhaopin/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
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
            @Value("${getjobs.liepin.search-endpoint:" + DEFAULT_ENDPOINT + "}") String endpoint,
            @Value("${getjobs.liepin.search-timeout-ms:" + DEFAULT_TIMEOUT_MS + "}") int timeoutMs
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
        try {
            HttpRequest request = buildRequest(keyword, cityCode, zeroBasedPage, session);
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
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String normalizedCity = cityCode == null ? "" : cityCode.trim();
        String referer = SEARCH_REFERER + "?city="
                + encode(normalizedCity) + "&currentPage=" + Math.max(0, zeroBasedPage)
                + "&key=" + encode(normalizedKeyword);
        String cookieHeader = session == null ? "" : session.cookieHeader("liepin.com");
        String xsrf = firstNonBlank(
                session == null ? null : session.value("XSRF-TOKEN"),
                session == null ? null : session.value("xsrf-token"));
        String sessionId = firstNonBlank(
                session == null ? null : session.value("ckId"),
                session == null ? null : session.value("ckid"),
                runtimeId());

        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Origin", "https://www.liepin.com")
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-site")
                .header("X-Fscp-Trace-Id", runtimeId())
                .header("X-Fscp-Std-Info", "{\"client\":\"pc\",\"source\":\"web\"}")
                .header("X-Fscp-Token", "")
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildRequestBody(normalizedKeyword, normalizedCity, zeroBasedPage, sessionId),
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
        Map<String, Object> main = new LinkedHashMap<>();
        main.put("city", cityCode == null ? "" : cityCode);
        main.put("dq", cityCode == null ? "" : cityCode);
        main.put("pubTime", "");
        main.put("currentPage", Math.max(0, zeroBasedPage));
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
        main.put("salaryLow", "");
        main.put("salaryHigh", "");

        Map<String, Object> passThrough = new LinkedHashMap<>();
        passThrough.put("scene", "init");
        passThrough.put("skId", "");
        passThrough.put("fkId", "");
        passThrough.put("ckId", firstNonBlank(sessionId, runtimeId()));
        passThrough.put("suggest", null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mainSearchPcConditionForm", main);
        data.put("passThroughForm", passThrough);
        return writeJson(Map.of("data", data));
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
