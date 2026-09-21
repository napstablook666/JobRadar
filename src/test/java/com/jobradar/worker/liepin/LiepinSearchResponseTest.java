package com.jobradar.worker.liepin;

import com.jobradar.application.entity.LiepinEntity;
import com.jobradar.application.service.LiepinService;
import com.jobradar.worker.manager.BrowserSessionSnapshot;
import com.jobradar.worker.manager.PlaywrightManager;
import com.jobradar.worker.liepin.LiepinRateGuard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiepinSearchResponseTest {

    @Test
    void parsesExistingNestedJobCardShape() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities("""
                {"data":{"data":{"jobCardList":[
                  {"job":{"jobId":"1001","title":"Application Engineer","link":"/job/1001",
                    "salary":"8-12K","jobDesc":"Device support"},
                   "comp":{"compId":"88","compName":"Example Co"},
                   "recruiter":{"recruiterId":"r1","recruiterName":"Recruiter"}}
                ]}}}
                """);

        assertTrue(result.recognized());
        assertEquals("data.data.jobCardList", result.detail().substring("岗位列表路径=".length()));
        LiepinEntity entity = result.entities().get(0);
        assertEquals(1001L, entity.getJobId());
        assertEquals("Application Engineer", entity.getJobTitle());
        assertEquals("Device support", entity.getJobDescription());
        assertEquals(88L, entity.getCompId());
        assertEquals("Recruiter", entity.getHrName());
    }

    @Test
    void parsesAlternateEnvelopeAndDirectFields() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities("""
                {"result":{"data":{"items":[
                  {"job_id":"1002","jobTitle":"Device Support","salaryText":"9-13K",
                   "company":{"companyId":"89","companyName":"Example Labs"},
                   "hrName":"Hiring Manager"}
                ]}}}
                """);

        assertTrue(result.recognized());
        LiepinEntity entity = result.entities().get(0);
        assertEquals(1002L, entity.getJobId());
        assertEquals("Device Support", entity.getJobTitle());
        assertEquals("9-13K", entity.getJobSalaryText());
        assertEquals(89L, entity.getCompId());
        assertEquals("Hiring Manager", entity.getHrName());
    }

    @Test
    void mapsSearchSnapshotJobDescribeToJobDescription() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities("""
                {"data":{"jobCardList":[
                  {"job":{"jobId":"1004","title":"设备应用工程师",
                    "jobDescribe":"负责设备安装、培训与客户技术支持"}}
                ]}}
                """);

        assertTrue(result.recognized());
        assertEquals("负责设备安装、培训与客户技术支持",
                result.entities().get(0).getJobDescription());
    }

    @Test
    void findsNestedRecordsWithPositionId() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities("""
                {"wrapper":{"payload":{"records":[
                  {"job":{"positionId":1003,"jobName":"Field Specialist"}}
                ]}}}
                """);

        assertTrue(result.recognized());
        assertEquals(1003L, result.entities().get(0).getJobId());
        assertEquals("Field Specialist", result.entities().get(0).getJobTitle());
    }

    @Test
    void acceptsKnownEmptyJobList() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities(
                "{\"data\":{\"jobCardList\":[]}}");

        assertTrue(result.recognized());
        assertTrue(result.entities().isEmpty());
    }

    @Test
    void acceptsSuccessfulEmptyPaginationWhenJobListIsOmitted() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities("""
                {"flag":1,"data":{
                  "data":{"jobSubscribeInfo":{"keyword":"放疗临床应用"}},
                  "pagination":{"currentPage":0,"pageSize":40,"totalCounts":0,
                    "totalPage":0,"hasNext":false}
                }}
                """);

        assertTrue(result.recognized());
        assertTrue(result.entities().isEmpty());
        assertEquals("空岗位结果=data.pagination.totalCounts=0", result.detail());
    }

    @Test
    void doesNotTreatFailedZeroPaginationAsSuccessfulEmptyResult() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities(
                "{\"flag\":0,\"data\":{\"pagination\":{\"totalCounts\":0}}}");

        assertFalse(result.recognized());
        assertTrue(result.detail().contains("flag=0"));
    }

    @Test
    void rejectsJsonWithoutAJobRecordList() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities(
                "{\"data\":{\"items\":[{\"recommendationId\":1}]}}");

        assertFalse(result.recognized());
        assertTrue(result.detail().contains("岗位列表未找到"));
        assertTrue(result.detail().contains("顶层字段=data"));
    }

    @Test
    void reportsTopLevelFailureDiagnostics() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities(
                "{\"flag\":0,\"code\":401,\"msg\":\"session expired\"}");

        assertFalse(result.recognized());
        assertTrue(result.detail().contains("flag=0"));
        assertTrue(result.detail().contains("code=401"));
        assertTrue(result.detail().contains("msg=session expired"));
    }

    @Test
    void rejectsBlankHtmlAndMalformedJson() {
        assertFalse(Liepin.parseSearchEntities(" ").recognized());
        assertFalse(Liepin.parseSearchEntities("<html>challenge</html>").recognized());
        assertFalse(Liepin.parseSearchEntities("{broken").recognized());
    }

    @Test
    void browserSearchTimeoutFallsBackToHttpData() {
        Liepin worker = newSearchWorker(LiepinHttpSearchClient.SearchResult.success(200,
                "{\"data\":{\"jobCardList\":[]}}"));

        Liepin.ExecutionResult result = worker.execute();

        assertEquals(Liepin.ExecutionOutcome.COMPLETED, result.outcome(),
                worker.getReadRequestStatus().detail());
        assertEquals("http", worker.getReadRequestStatus().route());
        assertTrue(worker.getReadRequestStatus().fallback());
        assertTrue(worker.getReadRequestStatus().detail().contains("Timeout 12000ms exceeded"));
    }

    @Test
    void browserAndHttpSearchFailuresPauseAfterTwoRetries() {
        LiepinHttpSearchClient.SearchResult failure = LiepinHttpSearchClient.SearchResult.failure(0,
                LiepinHttpSearchClient.FailureKind.TRANSPORT, "HTTP搜索请求失败: connection reset");
        int[] attempts = {0};
        Liepin worker = newSearchWorker(ignored -> {
            attempts[0]++;
            return failure;
        }, List.of("first", "second"));

        Liepin.ExecutionResult result = worker.execute();

        assertEquals(Liepin.ExecutionOutcome.RETRY_REQUIRED, result.outcome());
        assertEquals(3, attempts[0]);
        assertTrue(worker.getReadRequestStatus().fallback());
        assertTrue(worker.getReadRequestStatus().detail().contains("connection reset"),
                worker.getReadRequestStatus().detail());
    }

    @Test
    void retriesFlagZeroResponseInsteadOfSkippingKeyword() {
        LiepinHttpSearchClient.SearchResult flagZero = LiepinHttpSearchClient.SearchResult.success(200,
                "{\"flag\":0}");
        int[] attempts = {0};
        Liepin worker = newSearchWorker(keyword -> {
            attempts[0]++;
            return flagZero;
        },
                List.of("first", "second"));

        Liepin.ExecutionResult result = worker.execute();

        assertEquals(Liepin.ExecutionOutcome.RETRY_REQUIRED, result.outcome());
        assertFalse(worker.getSkipReasons().containsKey("搜索响应 flag=0"));
        // flag=0 is an invalid search response and must follow the same retry path.
        assertEquals(3, attempts[0]);
    }

    @Test
    void succeedsOnThirdSearchAttemptAndDoesNotAdvancePageOnFailures() {
        LiepinHttpSearchClient.SearchResult failure = LiepinHttpSearchClient.SearchResult.failure(0,
                LiepinHttpSearchClient.FailureKind.TRANSPORT, "HTTP搜索请求失败: transient");
        LiepinHttpSearchClient.SearchResult success = LiepinHttpSearchClient.SearchResult.success(200,
                "{\"data\":{\"jobCardList\":[]}}");
        int[] attempts = {0};
        Liepin worker = newSearchWorker(keyword -> attempts[0]++ < 2 ? failure : success,
                List.of("first"));

        Liepin.ExecutionResult result = worker.execute();

        assertEquals(Liepin.ExecutionOutcome.COMPLETED, result.outcome(),
                worker.getReadRequestStatus().detail());
        assertEquals(3, attempts[0]);
        verifyNoPageProgressSaved(worker);
    }

    @Test
    void cancellationAfterBrowserWaitSkipsHttpFallback() {
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicInteger httpCalls = new AtomicInteger();
        Page page = mock(Page.class);
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        LiepinHttpSearchClient httpClient = new LiepinHttpSearchClient(
                HttpClient.newHttpClient(),
                URI.create("https://example.test/search"),
                Duration.ofSeconds(1)
        ) {
            @Override
            SearchResult search(String keyword, String cityCode, int zeroBasedPage,
                                BrowserSessionSnapshot session, String salaryCode) {
                httpCalls.incrementAndGet();
                return SearchResult.failure(0,
                        FailureKind.TRANSPORT, "fallback must not run after cancellation");
            }
        };

        doAnswer(invocation -> {
            stopped.set(true);
            throw new RuntimeException("Timeout 12000ms exceeded");
        }).when(page).waitForResponse((Predicate<Response>) any(Predicate.class),
                any(Page.WaitForResponseOptions.class), any(Runnable.class));
        when(playwrightManager.isHybridTransport()).thenReturn(false);

        Liepin worker = new Liepin();
        worker.setPage(page);
        worker.setShouldStopCallback(stopped::get);
        ReflectionTestUtils.setField(worker, "playwrightManager", playwrightManager);
        ReflectionTestUtils.setField(worker, "liepinHttpSearchClient", httpClient);
        ReflectionTestUtils.setField(worker, "rateGuard", zeroDelayRateGuard());

        Boolean result = ReflectionTestUtils.invokeMethod(
                worker,
                "navigateAndCaptureSearchResponse",
                "https://www.liepin.com/zhaopin/?key=Java"
        );

        assertFalse(result);
        assertEquals(0, httpCalls.get());
        verify(page).waitForResponse((Predicate<Response>) any(Predicate.class),
                any(Page.WaitForResponseOptions.class), any(Runnable.class));
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
    }
    @Test
    void customSalaryConfirmationHttpFallbackAllowsMissingPageEvidence() {
        Liepin worker = newSearchWorker(LiepinHttpSearchClient.SearchResult.success(200,
                "{\"data\":{\"jobCardList\":[]}}"));
        Page page = (Page) ReflectionTestUtils.getField(worker, "page");
        when(page.url()).thenReturn("https://www.liepin.com/zhaopin/?key=Java");
        ReflectionTestUtils.setField(worker, "rateGuard", zeroDelayRateGuard());

        Boolean captured = ReflectionTestUtils.invokeMethod(
                worker,
                "captureSearchAfterAction",
                "自定义薪资确认",
                LiepinRateGuard.Action.SEARCH,
                (Runnable) () -> { },
                "Java",
                0,
                "6$10");

        assertTrue(captured);
        assertTrue((Boolean) ReflectionTestUtils.getField(worker, "webSalaryFilterConfirmed"));
        ReflectionTestUtils.invokeMethod(worker, "verifyAppliedWebSalaryFilter",
                new LiepinService.WebSalaryRange(7.2d, 12.0d));
    }

    @Test
    void customSalaryConfirmationHttpFailureDoesNotMarkState() {
        LiepinHttpSearchClient.SearchResult failure = LiepinHttpSearchClient.SearchResult.failure(
                503, LiepinHttpSearchClient.FailureKind.TRANSPORT, "HTTP搜索请求失败: unavailable");
        Liepin worker = newSearchWorker(failure);
        ReflectionTestUtils.setField(worker, "rateGuard", zeroDelayRateGuard());

        assertThrows(Liepin.SearchReadUnavailableException.class, () ->
                ReflectionTestUtils.invokeMethod(
                        worker,
                        "captureSearchAfterAction",
                        "自定义薪资确认",
                        LiepinRateGuard.Action.SEARCH,
                        (Runnable) () -> { },
                        "Java",
                        0,
                        "6$10"));
        assertFalse((Boolean) ReflectionTestUtils.getField(worker, "webSalaryFilterConfirmed"));
    }

    @Test
    void customSalaryConfirmationUnparseableHttpResponseDoesNotMarkState() {
        Liepin worker = newSearchWorker(LiepinHttpSearchClient.SearchResult.success(200,
                "{\"flag\":0}"));
        ReflectionTestUtils.setField(worker, "rateGuard", zeroDelayRateGuard());

        assertThrows(Liepin.SearchReadUnavailableException.class, () ->
                ReflectionTestUtils.invokeMethod(
                        worker,
                        "captureSearchAfterAction",
                        "自定义薪资确认",
                        LiepinRateGuard.Action.SEARCH,
                        (Runnable) () -> { },
                        "Java",
                        0,
                        "6$10"));
        assertFalse((Boolean) ReflectionTestUtils.getField(worker, "webSalaryFilterConfirmed"));
    }

    @Test
    void customSalaryConfirmationObservedResponseMarksState() {
        Liepin worker = newSearchWorker(LiepinHttpSearchClient.SearchResult.failure(
                503, LiepinHttpSearchClient.FailureKind.TRANSPORT, "HTTP fallback should not be used"));
        Page page = (Page) ReflectionTestUtils.getField(worker, "page");
        Response observed = mock(Response.class);
        when(observed.status()).thenReturn(200);
        when(observed.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(observed.text()).thenReturn("{\"data\":{\"jobCardList\":[]}}");
        when(page.url()).thenReturn("https://www.liepin.com/zhaopin/?key=Java");
        java.util.concurrent.atomic.AtomicReference<Response> observedRef = new java.util.concurrent.atomic.AtomicReference<>();
        ReflectionTestUtils.setField(worker, "observedSearchResponse", observedRef);
        ReflectionTestUtils.setField(worker, "rateGuard", zeroDelayRateGuard());
        doAnswer(invocation -> {
            observedRef.set(observed);
            throw new RuntimeException("Timeout 12000ms exceeded");
        }).when(page).waitForResponse((Predicate<Response>) any(Predicate.class),
                any(Page.WaitForResponseOptions.class), any(Runnable.class));

        Boolean captured = ReflectionTestUtils.invokeMethod(
                worker,
                "captureSearchAfterAction",
                "自定义薪资确认",
                LiepinRateGuard.Action.SEARCH,
                (Runnable) () -> { },
                "Java",
                0,
                "6$10");

        assertTrue(captured);
        assertTrue((Boolean) ReflectionTestUtils.getField(worker, "webSalaryFilterConfirmed"));
    }

    @Test
    void ordinaryObservedNavigationDoesNotMarkSalaryConfirmation() {
        Liepin worker = newSearchWorker(LiepinHttpSearchClient.SearchResult.failure(
                503, LiepinHttpSearchClient.FailureKind.TRANSPORT, "HTTP fallback should not be used"));
        Page page = (Page) ReflectionTestUtils.getField(worker, "page");
        Response observed = mock(Response.class);
        when(observed.status()).thenReturn(200);
        when(observed.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(observed.text()).thenReturn("{\"data\":{\"jobCardList\":[]}}");
        java.util.concurrent.atomic.AtomicReference<Response> observedRef = new java.util.concurrent.atomic.AtomicReference<>();
        ReflectionTestUtils.setField(worker, "observedSearchResponse", observedRef);
        ReflectionTestUtils.setField(worker, "rateGuard", zeroDelayRateGuard());
        doAnswer(invocation -> {
            observedRef.set(observed);
            throw new RuntimeException("Timeout 12000ms exceeded");
        }).when(page).waitForResponse((Predicate<Response>) any(Predicate.class),
                any(Page.WaitForResponseOptions.class), any(Runnable.class));

        Boolean captured = ReflectionTestUtils.invokeMethod(
                worker,
                "captureSearchAfterAction",
                "翻页",
                LiepinRateGuard.Action.PAGE,
                (Runnable) () -> { },
                "Java",
                0,
                "6$10");

        assertTrue(captured);
        assertFalse((Boolean) ReflectionTestUtils.getField(worker, "webSalaryFilterConfirmed"));
    }


    @Test
    void webSalaryConfirmationMarkerIgnoresOrdinaryNavigation() {
        Liepin worker = new Liepin();

        ReflectionTestUtils.invokeMethod(worker, "markWebSalaryFilterConfirmed", "翻页", "6$10");
        assertFalse((Boolean) ReflectionTestUtils.getField(worker, "webSalaryFilterConfirmed"));
        assertEquals("", ReflectionTestUtils.getField(worker, "appliedWebSalaryCode"));
        ReflectionTestUtils.invokeMethod(worker, "markWebSalaryFilterConfirmed", "自定义薪资确认", "7$12");
        assertTrue((Boolean) ReflectionTestUtils.getField(worker, "webSalaryFilterConfirmed"));
    }

    @Test
    void webSalaryConfirmationStateResetsForTaskAndKeyword() {
        Liepin worker = newSearchWorker(LiepinHttpSearchClient.SearchResult.success(200,
                "{\"data\":{\"jobCardList\":[]}}"));
        ReflectionTestUtils.setField(worker, "webSalaryFilterConfirmed", true);
        worker.prepare();
        assertFalse((Boolean) ReflectionTestUtils.getField(worker, "webSalaryFilterConfirmed"));

        ReflectionTestUtils.setField(worker, "webSalaryFilterConfirmed", true);
        worker.setShouldStopCallback(() -> true);
        ReflectionTestUtils.invokeMethod(worker, "submit", "放射技师");
        assertFalse((Boolean) ReflectionTestUtils.getField(worker, "webSalaryFilterConfirmed"));
    }



    private Liepin newSearchWorker(LiepinHttpSearchClient.SearchResult fallbackResult) {
        return newSearchWorker(ignored -> fallbackResult, List.of("放射技师"));
    }

    private Liepin newSearchWorker(
            Function<String, LiepinHttpSearchClient.SearchResult> fallbackResults,
            List<String> keywords
    ) {
        Page page = mock(Page.class);
        Locator cards = mock(Locator.class);
        Locator empty = mock(Locator.class);
        PlaywrightManager playwrightManager = mock(PlaywrightManager.class);
        LiepinHttpSearchClient httpClient = new LiepinHttpSearchClient(
                HttpClient.newHttpClient(),
                URI.create("https://example.test/search"),
                Duration.ofSeconds(1)
        ) {
            @Override
            SearchResult search(String keyword, String cityCode, int zeroBasedPage,
                                BrowserSessionSnapshot session, String salaryCode) {
                return fallbackResults.apply(keyword);
            }
        };
        LiepinService liepinService = mock(LiepinService.class);
        LiepinConfig config = new LiepinConfig();
        config.setKeywords(keywords);

        doThrow(new RuntimeException("Timeout 12000ms exceeded"))
                .when(page).waitForResponse((Predicate<Response>) any(Predicate.class),
                        any(Page.WaitForResponseOptions.class), any(Runnable.class));
        when(page.locator(any(String.class))).thenReturn(cards);
        when(cards.count()).thenReturn(1);
        when(cards.locator(any(String.class))).thenReturn(empty);
        when(empty.count()).thenReturn(0);
        when(playwrightManager.isHybridTransport()).thenReturn(false);
        when(playwrightManager.getSessionSnapshot()).thenReturn(new BrowserSessionSnapshot(List.of()));

        Liepin worker = new Liepin() {
            @Override
            public void prepare() {
                super.prepare();
                ReflectionTestUtils.setField(this, "rateGuard",
                        LiepinSearchResponseTest.this.zeroDelayRateGuard());
            }
        };
        worker.setPage(page);
        worker.setConfig(config);
        ReflectionTestUtils.setField(worker, "playwrightManager", playwrightManager);
        ReflectionTestUtils.setField(worker, "liepinHttpSearchClient", httpClient);
        ReflectionTestUtils.setField(worker, "liepinService", liepinService);
        return worker;
    }

    private LiepinRateGuard zeroDelayRateGuard() {
        LiepinRateGuard.DelayRange zero = new LiepinRateGuard.DelayRange(0, 0);
        return new LiepinRateGuard(
                () -> false,
                ignored -> {
                },
                System::currentTimeMillis,
                ignored -> {
                },
                (low, high) -> 0,
                zero,
                zero,
                zero,
                zero,
                15,
                zero
        );
    }

    private void verifyNoPageProgressSaved(Liepin worker) {
        LiepinService liepinService = (LiepinService) ReflectionTestUtils.getField(worker, "liepinService");
        org.mockito.Mockito.verify(liepinService,
                never()).saveLastCompletedPage(any(), any(), any(), anyInt());
    }

}
