package com.jobradar.application.controller;

import com.jobradar.application.entity.AiEntity;
import com.jobradar.application.service.AiService;
import com.jobradar.worker.liepin.LiepinAiBatchAssessment;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * AI配置控制器
 * 提供AI配置管理的REST API接口
 */
@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "*")
@Slf4j
public class AiConfigController {


    @Autowired
    private AiService aiService;

    /**
     * 获取AI配置
     * @return AI配置信息
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getAiConfig() {
        Map<String, Object> response = new HashMap<>();

        try {
            AiEntity aiEntity = aiService.getAiConfig();

            response.put("success", true);
            response.put("data", aiEntity);
            response.put("message", "获取AI配置成功");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("获取AI配置失败", e);
            response.put("success", false);
            response.put("message", "获取AI配置失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 保存或更新AI配置
     * @param requestBody 请求体包含introduce和prompt
     * @return 保存结果
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveAiConfig(@RequestBody Map<String, String> requestBody) {
        Map<String, Object> response = new HashMap<>();

        try {
            String introduce = requestBody.get("introduce");
            String prompt = requestBody.get("prompt");
            String screenPrompt = requestBody.get("screenPrompt");
            String jdAnalysisPrompt = requestBody.get("jdAnalysisPrompt");
            String messagePrompt = requestBody.get("messagePrompt");

            if (introduce == null || prompt == null) {
                response.put("success", false);
                response.put("message", "参数不完整，introduce和prompt不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            AiEntity aiEntity = aiService.saveOrUpdateAiConfig(
                    introduce, prompt, screenPrompt, jdAnalysisPrompt, messagePrompt);

            response.put("success", true);
            response.put("data", aiEntity);
            response.put("message", "保存AI配置成功");

            log.info("保存AI配置成功，ID: {}", aiEntity.getId());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("保存AI配置失败", e);
            response.put("success", false);
            response.put("message", "保存AI配置失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 健康检查接口
     * @return 服务状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("service", "AiConfigController");
        response.put("status", "healthy");
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }

    /**
     * 获取可用 AI 模型列表（使用数据库中已保存的 BASE_URL / API_KEY）
     */
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> listModels() {
        Map<String, Object> response = new HashMap<>();
        try {
            var models = aiService.listModels(null, null);
            response.put("success", true);
            response.put("data", models);
            response.put("count", models.size());
            response.put("message", "获取模型列表成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取模型列表失败", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 获取可用 AI 模型列表。
     * 可传入当前表单中的 baseUrl / apiKey（无需先保存）；缺省时回退到数据库配置。
     * body 示例：{ "baseUrl": "http://127.0.0.1:3000/v1", "apiKey": "sk-xxx" }
     */
    @PostMapping("/models")
    public ResponseEntity<Map<String, Object>> listModelsWithConfig(
            @RequestBody(required = false) Map<String, String> requestBody) {
        Map<String, Object> response = new HashMap<>();
        try {
            String baseUrl = requestBody != null ? requestBody.get("baseUrl") : null;
            String apiKey = requestBody != null ? requestBody.get("apiKey") : null;
            // 兼容前端可能使用的别名
            if (baseUrl == null && requestBody != null) {
                baseUrl = requestBody.get("BASE_URL");
            }
            if (apiKey == null && requestBody != null) {
                apiKey = requestBody.get("API_KEY");
            }

            var models = aiService.listModels(baseUrl, apiKey);
            response.put("success", true);
            response.put("data", models);
            response.put("count", models.size());
            response.put("message", "获取模型列表成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取模型列表失败", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * AI 文本生成测试接口（GET）
     * 示例：/api/ai/chat?content=你好，帮我写一句简洁的问候语
     */
    @GetMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestParam(name = "content") String content) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (content == null || content.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "content 参数不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            String reply = aiService.sendRequest(content.trim());
            response.put("success", true);
            response.put("data", reply);
            response.put("message", "AI 请求成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("AI 请求失败", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 使用当前页面内容测试生成 HR 打招呼语，不保存配置，也不启动投递任务。
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody Map<String, String> requestBody) {
        Map<String, Object> response = new HashMap<>();

        if (requestBody == null) {
            response.put("success", false);
            response.put("message", "测试参数不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        String introduce = trimToEmpty(requestBody.get("introduce"));
        String prompt = trimToEmpty(requestBody.get("prompt"));
        String keyword = trimToEmpty(requestBody.get("keyword"));
        String jobName = trimToEmpty(requestBody.get("jobName"));
        String jd = trimToEmpty(requestBody.get("jd"));
        String sayHi = trimToEmpty(requestBody.get("sayHi"));

        if (introduce.isBlank() || prompt.isBlank() || jobName.isBlank() || jd.isBlank()) {
            response.put("success", false);
            response.put("message", "技能介绍、AI提示词、岗位名称和岗位要求不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            String requestMessage = aiService.renderPrompt(prompt, introduce, keyword, jobName, jd, sayHi);
            String reply = aiService.sendRequest(requestMessage);
            AiService.GreetingValidation validation = AiService.validateGreeting(reply, introduce);
            if (!validation.usable()) {
                response.put("success", false);
                response.put("message", "AI预览话术未通过格式校验: " + validation.reason());
                return ResponseEntity.unprocessableEntity().body(response);
            }

            response.put("success", true);
            response.put("data", validation.message());
            response.put("message", "AI预览生成成功");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("AI预览生成失败", e);
            response.put("success", false);
            response.put("message", "AI预览生成失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
    /**
     * 使用当前 JD 分析提示词测试岗位筛选，不保存配置，也不启动投递任务。
     */
    @PostMapping("/jd-analysis-preview")
    public ResponseEntity<Map<String, Object>> jdAnalysisPreview(@RequestBody Map<String, String> requestBody) {
        Map<String, Object> response = new HashMap<>();
        if (requestBody == null) {
            response.put("success", false);
            response.put("message", "测试参数不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        String candidate = trimToEmpty(requestBody.get("candidate"));
        String keyword = trimToEmpty(requestBody.get("keyword"));
        String jobName = trimToEmpty(requestBody.get("jobName"));
        String jd = trimToEmpty(requestBody.get("jd"));
        String jdAnalysisPrompt = trimToEmpty(requestBody.get("jdAnalysisPrompt"));
        String screenPrompt = trimToEmpty(requestBody.get("screenPrompt"));
        String rawMinScore = trimToEmpty(requestBody.get("minScore"));
        Integer minScore = parseScore(rawMinScore);
        if (candidate.isBlank() || jobName.isBlank() || jd.isBlank()) {
            response.put("success", false);
            response.put("message", "候选人资料、岗位名称和岗位要求不能为空");
            return ResponseEntity.badRequest().body(response);
        }
        if (minScore == null) {
            response.put("success", false);
            response.put("message", "通过分数必须是0到100之间的整数");
            return ResponseEntity.badRequest().body(response);
        }
        if (candidate.length() > 12000 || jobName.length() > 200 || jd.length() > 20000) {
            response.put("success", false);
            response.put("message", "候选人资料、岗位名称或JD超过长度限制");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            Map<String, String> values = new HashMap<>();
            values.put("candidate", candidate);
            values.put("keyword", keyword);
            values.put("min_score", String.valueOf(minScore));
            JSONObject job = new JSONObject();
            job.put("jobId", "preview");
            job.put("title", jobName);
            job.put("jd", jd);
            values.put("jobs", new JSONArray().put(job).toString());
            String rendered = aiService.renderScreeningPrompt(screenPrompt, jdAnalysisPrompt, values);
            String raw = aiService.sendStructuredRequest(rendered, 0.1, 384);
            if (LiepinAiBatchAssessment.parse(raw) == null) {
                response.put("success", false);
                response.put("message", "AI 返回格式无效，请检查批量评分模板");
                response.put("data", raw);
                return ResponseEntity.unprocessableEntity().body(response);
            }
            response.put("success", true);
            response.put("data", raw);
            response.put("message", "AI JD 分析完成");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("AI JD 分析预览失败", e);
            response.put("success", false);
            response.put("message", "AI JD 分析失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    private Integer parseScore(String value) {
        if (value == null || value.isBlank()) return 70;
        try {
            int score = Integer.parseInt(value);
            return score >= 0 && score <= 100 ? score : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
