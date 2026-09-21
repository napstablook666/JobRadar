package com.jobradar.application.controller;

import com.jobradar.worker.manager.PlaywrightManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Playwright管理控制器
 * 用于测试和管理Playwright实例
 */
@RestController
@RequestMapping("/api/playwright")
public class PlaywrightController {

    private final PlaywrightManager playwrightManager;

    public PlaywrightController(PlaywrightManager playwrightManager) {
        this.playwrightManager = playwrightManager;
    }

    /**
     * 获取Playwright状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("initialized", playwrightManager.isInitialized());
        status.put("cdpPort", playwrightManager.getCdpPort());
        status.put("hasBossPage", playwrightManager.getBossPage() != null);
        status.put("hasBrowser", playwrightManager.getBrowser() != null);
        status.put("bossLoggedIn", playwrightManager.isLoggedIn("boss"));
        status.put("browserMode", playwrightManager.getBrowserModeName());
        status.put("transport", playwrightManager.getTransportModeName());
        status.put("engine", playwrightManager.getBrowserEngineName());
        status.put("pagePolicy", playwrightManager.getPagePolicyName());
        status.put("headless", playwrightManager.isHeadlessMode());
        status.put("deliveryRuntime", playwrightManager.getDeliveryRuntimeName());
        status.put("platformRuntimes", playwrightManager.getPlatformRuntimeStatus());

        return ResponseEntity.ok(status);
    }

    /**
     * 测试Boss导航功能
     */
    @GetMapping("/test-navigate")
    public ResponseEntity<Map<String, String>> testNavigate() {
        try {
            var page = playwrightManager.ensureBossPageReady();
            page.navigate("https://www.zhipin.com");
            String title = page.title();

            Map<String, String> result = new HashMap<>();
            result.put("success", "true");
            result.put("title", title);
            result.put("url", page.url());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("success", "false");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
