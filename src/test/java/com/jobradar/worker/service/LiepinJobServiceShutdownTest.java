package com.jobradar.worker.service;

import com.jobradar.application.service.ConfigService;
import com.jobradar.application.service.LiepinService;
import com.jobradar.worker.manager.PlaywrightManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LiepinJobServiceShutdownTest {

    @Test
    void stopAndAwaitWaitsForTheWorkerToFinish() throws Exception {
        LiepinJobService service = newService();
        setRunning(service, true);

        Thread finisher = new Thread(() -> {
            try {
                Thread.sleep(75L);
                invokeFinishRun(service);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        finisher.start();

        assertTrue(service.stopAndAwait(1_000L));
        finisher.join(1_000L);
        assertFalse((boolean) readField(service, "isRunning"));
    }

    @Test
    void stopAndAwaitForceClosesWhenTheWorkerDoesNotFinish() throws Exception {
        LiepinJobService service = newService();
        setRunning(service, true);

        assertTrue(service.stopAndAwait(20L));

        assertFalse(service.isRunning());
        assertEquals("CANCELLED", service.getStatus().get("taskState"));
        assertEquals(0, service.getStatus().get("activeTaskLeases"));
    }

    private LiepinJobService newService() {
        return new LiepinJobService(
                mock(PlaywrightManager.class),
                mock(ConfigService.class),
                mock(ObjectProvider.class),
                mock(LiepinService.class)
        );
    }

    private static void setRunning(LiepinJobService service, boolean running) throws Exception {
        Field field = LiepinJobService.class.getDeclaredField("isRunning");
        field.setAccessible(true);
        field.setBoolean(service, running);
    }

    private static Object readField(LiepinJobService service, String name) throws Exception {
        Field field = LiepinJobService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }

    private static void invokeFinishRun(LiepinJobService service) throws Exception {
        Method method = LiepinJobService.class.getDeclaredMethod("finishRun");
        method.setAccessible(true);
        method.invoke(service);
    }
}
