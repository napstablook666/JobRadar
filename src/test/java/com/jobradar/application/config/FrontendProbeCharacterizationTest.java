package com.jobradar.application.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the duplicated frontend-port probe constants before extracting a shared helper.
 * Uses reflection so we do not need a live server on 6866.
 */
class FrontendProbeCharacterizationTest {

    @Test
    void bothConfigsShareFrontendPort6866() throws Exception {
        assertEquals(6866, readPrivateInt(StaticResourceConfiguration.class, "FRONTEND_PORT"));
        assertEquals(6866, readPrivateInt(StaticServerConfiguration.class, "FRONTEND_PORT"));
    }

    @Test
    void detectMethodsExistAndReturnBooleanWithoutThrowing() throws Exception {
        StaticResourceConfiguration resource = new StaticResourceConfiguration();
        Method detectResource = StaticResourceConfiguration.class
                .getDeclaredMethod("detectFrontendService");
        detectResource.setAccessible(true);
        Object a = detectResource.invoke(resource);
        assertInstanceOf(Boolean.class, a);

        StaticServerConfiguration server = new StaticServerConfiguration();
        Method detectServer = StaticServerConfiguration.class
                .getDeclaredMethod("detectFrontendDevServer");
        detectServer.setAccessible(true);
        Object b = detectServer.invoke(server);
        assertInstanceOf(Boolean.class, b);
    }

    private static int readPrivateInt(Class<?> type, String field) throws Exception {
        var f = type.getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(null);
    }
}
