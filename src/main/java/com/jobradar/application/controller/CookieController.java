package com.jobradar.application.controller;

import com.jobradar.application.entity.CookieEntity;
import com.jobradar.application.service.CookieService;
import com.jobradar.worker.manager.PlaywrightManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.Objects;

/**
 * 统一的 Cookie 读/写控制器
 * 提供：
 * - GET /api/cookie?platform=... 读取指定平台的 Cookie 元数据（不返回内容）
 * - POST /api/cookie/save?platform=...&remark=... 保存当前上下文 Cookie 到数据库
 * - POST /api/cookie/browser/capture 按钮触发可见登录浏览器 Cookie 获取
 */
@Slf4j
@RestController
@RequestMapping("/api/cookie")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class CookieController {

    private final CookieService cookieService;
    private final PlaywrightManager playwrightManager;

    private static final Set<String> ALLOWED_PLATFORMS = Set.of("boss", "liepin", "51job", "zhilian");

    @GetMapping
    public ResponseEntity<Map<String, Object>> getCookie(@RequestParam("platform") String platform) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!ALLOWED_PLATFORMS.contains(platform)) {
                response.put("success", false);
                response.put("message", "不支持的平台: " + platform);
                return ResponseEntity.badRequest().body(response);
            }

            CookieEntity cookie = cookieService.getCookieByPlatform(platform);
            Map<String, Object> data = new HashMap<>();
            if (cookie != null) {
                data.put("id", cookie.getId());
                data.put("platform", cookie.getPlatform());
                data.put("stored", cookie.getCookieValue() != null && !cookie.getCookieValue().isBlank());
                data.put("remark", cookie.getRemark());
                data.put("created_at", cookie.getCreatedAt());
                data.put("updated_at", cookie.getUpdatedAt());
            } else {
                data.put("platform", platform);
                data.put("stored", false);
                data.put("message", "未找到Cookie记录");
            }
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("读取Cookie记录失败", e);
            response.put("success", false);
            response.put("message", "读取Cookie记录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 返回指定平台的本地 Cookie 保存状态和当前登录检测结果，不返回 Cookie 内容。
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getCookieStatus(@RequestParam("platform") String platform) {
        Map<String, Object> response = new HashMap<>();
        if (!ALLOWED_PLATFORMS.contains(platform)) {
            response.put("success", false);
            response.put("message", "不支持的平台: " + platform);
            return ResponseEntity.badRequest().body(response);
        }

        try {
            CookieEntity cookie = cookieService.getCookieByPlatform(platform);
            boolean stored = cookie != null && cookie.getCookieValue() != null && !cookie.getCookieValue().isBlank();
            boolean loggedIn = playwrightManager.isLoggedIn(platform);
            boolean loginStatusKnown = playwrightManager.isLoginStatusKnown(platform);

            Map<String, Object> data = new HashMap<>();
            data.put("platform", platform);
            data.put("stored", stored);
            data.put("loggedIn", loggedIn);
            data.put("needsReimport", stored && loginStatusKnown && !loggedIn);
            if (stored) {
                data.put("updatedAt", cookie.getUpdatedAt());
            }
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("读取Cookie状态失败", e);
            response.put("success", false);
            response.put("message", "读取Cookie状态失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** 打开指定平台的可见登录浏览器，登录完成后等待用户点击获取 Cookie。 */
    @PostMapping("/browser/open")
    public ResponseEntity<Map<String, Object>> openBrowserLogin(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String platform = body == null ? null : Objects.toString(body.get("platform"), "").trim();
            if (!ALLOWED_PLATFORMS.contains(platform)) {
                response.put("success", false);
                response.put("message", "不支持的平台: " + platform);
                return ResponseEntity.badRequest().body(response);
            }
            response.put("success", true);
            response.put("data", playwrightManager.openBrowserLogin(platform));
            response.put("message", "已打开登录浏览器，完成登录后请点击“获取并保存 Cookie”");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("打开浏览器登录失败", e);
            response.put("success", false);
            response.put("message", "打开浏览器登录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** 按钮触发：读取可见登录浏览器 Cookie，保存到本机并注入投递运行时。 */
    @PostMapping("/browser/capture")
    public ResponseEntity<Map<String, Object>> captureBrowserLogin(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String platform = body == null ? null : Objects.toString(body.get("platform"), "").trim();
            if (!ALLOWED_PLATFORMS.contains(platform)) {
                response.put("success", false);
                response.put("message", "不支持的平台: " + platform);
                return ResponseEntity.badRequest().body(response);
            }

            Map<String, Object> data = playwrightManager.captureBrowserLogin(platform);
            boolean saved = Boolean.TRUE.equals(data.get("saved"));
            boolean loggedIn = Boolean.TRUE.equals(data.get("loggedIn"));
            response.put("success", saved);
            response.put("data", data);
            if (saved && loggedIn) {
                response.put("message", String.format("已获取并保存 %s Cookie，当前检测到已登录", platform));
            } else if (saved) {
                response.put("message", String.format("已获取并保存 %s Cookie，但登录状态尚未确认", platform));
            } else {
                response.put("message", Objects.toString(data.get("error"), "未获取到可保存的 Cookie"));
            }
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("获取浏览器 Cookie 失败", e);
            response.put("success", false);
            response.put("message", "获取浏览器 Cookie 失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** 查询可见登录浏览器状态，不返回 Cookie 内容。 */
    @GetMapping("/browser/status")
    public ResponseEntity<Map<String, Object>> getBrowserLoginStatus(@RequestParam("platform") String platform) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!ALLOWED_PLATFORMS.contains(platform)) {
                response.put("success", false);
                response.put("message", "不支持的平台: " + platform);
                return ResponseEntity.badRequest().body(response);
            }
            response.put("success", true);
            response.put("data", playwrightManager.getBrowserLoginStatus(platform));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("读取浏览器登录状态失败", e);
            response.put("success", false);
            response.put("message", "读取浏览器登录状态失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveCookie(
            @RequestParam("platform") String platform,
            @RequestParam(value = "remark", defaultValue = "manual save") String remark
    ) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!ALLOWED_PLATFORMS.contains(platform)) {
                response.put("success", false);
                response.put("message", "不支持的平台: " + platform);
                return ResponseEntity.badRequest().body(response);
            }

            playwrightManager.saveCookiesToDb(platform, remark);
            response.put("success", true);
            response.put("message", String.format("已主动保存 %s Cookie 到数据库", platform));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("保存Cookie失败", e);
            response.put("success", false);
            response.put("message", "保存Cookie失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 手动导入 Cookie 字符串并应用到浏览器（Boss 主登录方式）。
     * body: { "platform": "boss", "cookie_value": "...", "remark": "optional" }
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importCookie(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String platform = body == null ? null : Objects.toString(body.get("platform"), "").trim();
            String cookieValue = body == null ? null : Objects.toString(body.get("cookie_value"), "");
            String remark = body == null || body.get("remark") == null
                    ? "manual import"
                    : Objects.toString(body.get("remark"), "manual import");

            if (!ALLOWED_PLATFORMS.contains(platform)) {
                response.put("success", false);
                response.put("message", "不支持的平台: " + platform);
                return ResponseEntity.badRequest().body(response);
            }
            if (cookieValue == null || cookieValue.isBlank()) {
                response.put("success", false);
                response.put("message", "cookie_value 不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            Map<String, Object> data = playwrightManager.importCookies(platform, cookieValue, remark);
            boolean loggedIn = Boolean.TRUE.equals(data.get("loggedIn"));
            response.put("success", true);
            response.put("data", data);
            response.put("message", loggedIn
                    ? String.format("已导入 %s Cookie 并检测到已登录", platform)
                    : String.format("已将 %s Cookie 保存到本机，但当前未通过登录检测；请重新获取完整 Cookie 后导入", platform));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (IllegalStateException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(503).body(response);
        } catch (Exception e) {
            log.error("导入Cookie失败", e);
            response.put("success", false);
            response.put("message", "导入Cookie失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
