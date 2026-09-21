package com.jobradar.worker.zhilian;

import com.jobradar.application.entity.AiEntity;
import com.jobradar.application.entity.ZhilianJobDataEntity;
import com.jobradar.application.service.AiService;
import com.jobradar.application.service.JobFunnelService;
import com.jobradar.application.service.ZhilianService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhilianFunnelEventTest {
    private Connection keepAlive;
    private JobFunnelService funnelService;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:zhilian_worker_funnel_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        funnelService = new JobFunnelService(dataSource);
        funnelService.ensureTable();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (keepAlive != null) keepAlive.close();
    }

    @Test
    void cachedJdPathMarksJdOnThePersistedCanonicalJobId() throws Exception {
        ZhilianService zhilianService = Mockito.mock(ZhilianService.class);
        AiService aiService = Mockito.mock(AiService.class);
        AiEntity aiConfig = new AiEntity();
        aiConfig.setIntroduce("应用物理学应届毕业生，放疗设备临床应用方向");
        Mockito.when(aiService.getAiConfig()).thenReturn(aiConfig);
        Mockito.when(aiService.renderScreeningPrompt(Mockito.any(), Mockito.any(), Mockito.anyMap()))
                .thenReturn("screening prompt");
        Mockito.when(aiService.sendStructuredRequest(Mockito.anyString(), Mockito.anyDouble(), Mockito.anyInt()))
                .thenReturn("{\"items\":[{\"jobId\":\"cached-1\",\"score\":90,\"decision\":\"PASS\",\"reasonCodes\":[\"DIRECTION_MATCH\"],\"reason\":\"方向匹配\"}]}");

        ZhiLian worker = new ZhiLian(zhilianService, aiService);
        ReflectionTestUtils.setField(worker, "jobFunnelService", funnelService);
        ZhilianConfig config = new ZhilianConfig();
        config.setEnableAiScreening(true);
        config.setAiMinScore(70);
        ReflectionTestUtils.setField(worker, "config", config);

        ZhilianJobDataEntity persisted = new ZhilianJobDataEntity();
        persisted.setJobId("cached-1");
        persisted.setJobDescription("职位描述\n负责放疗设备临床应用培训和现场支持。\n任职要求\n本科及以上。");
        Object pageJob = pageJob("cached-1");

        Boolean accepted = ReflectionTestUtils.invokeMethod(
                worker, "passesAiScreening", "放疗临床应用", pageJob, persisted);

        assertTrue(Boolean.TRUE.equals(accepted));
        Map<String, Object> funnel = funnelService.getFunnel("zhilian");
        Map<?, ?> stages = (Map<?, ?>) funnel.get("stages");
        assertEquals(1L, stages.get("jd"));
        assertEquals(1L, stages.get("ai_valid"));
        assertEquals(1L, stages.get("ai_pass"));
    }

    private Object pageJob(String jobId) throws Exception {
        Class<?> type = Class.forName("com.jobradar.worker.zhilian.ZhiLian$PageJob");
        Constructor<?> constructor = type.getDeclaredConstructor(
                int.class, String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                0, jobId, "放疗临床应用工程师", "测试医疗公司", "https://www.zhaopin.com/jobdetail/" + jobId + ".htm",
                "8K", "杭州", "经验不限", "本科");
    }
}
