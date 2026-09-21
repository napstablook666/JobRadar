package com.jobradar.worker.liepin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.worker.manager.BrowserSessionSnapshot;
import com.microsoft.playwright.options.Cookie;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinHttpSearchClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsExpectedSearchBodyHeadersAndSessionCookies() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();
        server = startServer(200, "{\"data\":{\"data\":{\"jobCardList\":[]}}}",
                requestBody, requestHeaders);

        Cookie session = new Cookie("session", "abc");
        session.domain = "api.liepin.com";
        Cookie xsrf = new Cookie("XSRF-TOKEN", "token");
        xsrf.domain = "api-c.liepin.com";
        BrowserSessionSnapshot snapshot = new BrowserSessionSnapshot(List.of(session, xsrf));
        LiepinHttpSearchClient client = client();

        LiepinHttpSearchClient.SearchResult result =
                client.search("医疗设备", "CITY_CODE", 2, snapshot);

        assertTrue(result.success());
        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        JsonNode form = body.path("data").path("mainSearchPcConditionForm");
        assertEquals("医疗设备", form.path("key").asText());
        assertEquals("CITY_CODE", form.path("city").asText());
        assertEquals(2, form.path("currentPage").asInt());
        assertEquals("", form.path("salaryCode").asText());
        assertTrue(requestHeaders.get().getFirst("Cookie").contains("session=abc"));
        assertEquals("token", requestHeaders.get().getFirst("X-XSRF-TOKEN"));
        assertEquals("web", requestHeaders.get().getFirst("X-Client-Type"));
        assertEquals("1.1", requestHeaders.get().getFirst("X-Fscp-Version"));
        assertEquals("{\"client_id\": \"40108\"}", requestHeaders.get().getFirst("X-Fscp-Std-Info"));
        assertEquals("https://www.liepin.com/zhaopin/", requestHeaders.get().getFirst("Referer"));
        assertNotNull(requestHeaders.get().getFirst("X-Fscp-Trace-Id"));
        assertEquals("init", body.path("data").path("passThroughForm").path("scene").asText());
    }

    @Test
    void encodesCustomSalaryAsConditionRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();
        server = startServer(200, "{\"data\":{\"jobCardList\":[]}}",
                requestBody, requestHeaders);

        LiepinHttpSearchClient.SearchResult result =
                client().search("医疗设备", "CITY_CODE", 0, null, "6$10");

        assertTrue(result.success());
        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        JsonNode data = body.path("data");
        JsonNode form = data.path("mainSearchPcConditionForm");
        JsonNode passThrough = data.path("passThroughForm");
        assertEquals("", form.path("salaryCode").asText());
        assertEquals("6", form.path("salaryLow").asText());
        assertEquals("10", form.path("salaryHigh").asText());
        assertEquals("condition", passThrough.path("scene").asText());
        assertEquals("search_job_pc", passThrough.path("sfrom").asText());
        assertTrue(passThrough.path("ckId").asText().matches("[a-z0-9]{40}"));
        assertTrue(requestHeaders.get().getFirst("X-Fscp-Bi-Stat").contains("salaryCode"));
    }

    @Test
    void matchesSearchResponsesButExcludesConditionInitialization() {
        assertTrue(Liepin.isSearchResponseUrl(
                "https://api-c.liepin.com/api/com.liepin.searchfront4c.pc-search-job"));
        assertTrue(Liepin.isSearchResponseUrl(
                "HTTPS://API-C.LIEPIN.COM/API/COM.LIEPIN.SEARCHFRONT4C.PC-SEARCH-JOB"));
        assertFalse(Liepin.isSearchResponseUrl(
                "https://api-c.liepin.com/api/com.liepin.searchfront4c.pc-search-job-cond-init"));
        assertFalse(Liepin.isSearchResponseUrl("https://api-c.liepin.com/api/other-endpoint"));
    }

    @Test
    void rejectsHttpFailuresEmptyBodiesAndRiskSignals() throws Exception {
        server = startServer(429, "too many requests", new AtomicReference<>(), new AtomicReference<>());
        LiepinHttpSearchClient.SearchResult httpFailure = client().search("x", "", 0, null);
        assertFalse(httpFailure.success());
        assertEquals(LiepinHttpSearchClient.FailureKind.HTTP_STATUS, httpFailure.failureKind());

        server.stop(0);
        server = startServer(200, "", new AtomicReference<>(), new AtomicReference<>());
        LiepinHttpSearchClient.SearchResult empty = client().search("x", "", 0, null);
        assertEquals(LiepinHttpSearchClient.FailureKind.EMPTY_BODY, empty.failureKind());

        server.stop(0);
        server = startServer(200, "captcha required", new AtomicReference<>(), new AtomicReference<>());
        LiepinHttpSearchClient.SearchResult risk = client().search("x", "", 0, null);
        assertEquals(LiepinHttpSearchClient.FailureKind.RISK_SIGNAL, risk.failureKind());
    }

    @Test
    void usesOnlyLiepinCookiesForSessionHeaders() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();
        server = startServer(200, "{\"data\":{\"jobCardList\":[]}}",
                requestBody, requestHeaders);

        Cookie otherXsrf = new Cookie("XSRF-TOKEN", "wrong-token");
        otherXsrf.domain = "zhipin.com";
        Cookie liepinXsrf = new Cookie("XSRF-TOKEN", "liepin-token");
        liepinXsrf.domain = "www.liepin.com";
        Cookie otherSession = new Cookie("ckId", "wrong-session");
        otherSession.domain = "51job.com";
        Cookie liepinSession = new Cookie("ckId", "liepin-session");
        liepinSession.domain = "api-c.liepin.com";

        LiepinHttpSearchClient.SearchResult result = client().search("x", "", 0,
                new BrowserSessionSnapshot(List.of(
                        otherXsrf, liepinXsrf, otherSession, liepinSession)));

        assertTrue(result.success());
        assertEquals("liepin-token", requestHeaders.get().getFirst("X-XSRF-TOKEN"));
        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        assertNotEquals("liepin-session", body.path("data").path("passThroughForm").path("ckId").asText());
        assertTrue(body.path("data").path("passThroughForm").path("ckId").asText().matches("[a-z0-9]{40}"));
    }

    private LiepinHttpSearchClient client() {
        return new LiepinHttpSearchClient(
                java.net.http.HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"),
                Duration.ofSeconds(2));
    }

    private HttpServer startServer(
            int status,
            String responseBody,
            AtomicReference<String> requestBody,
            AtomicReference<Headers> requestHeaders
    ) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        created.createContext("/search", exchange -> handle(exchange, status, responseBody,
                requestBody, requestHeaders));
        created.start();
        return created;
    }

    private void handle(
            HttpExchange exchange,
            int status,
            String responseBody,
            AtomicReference<String> requestBody,
            AtomicReference<Headers> requestHeaders
    ) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requestHeaders.set(exchange.getRequestHeaders());
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
