package com.getjobs.application.service;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.mapper.AiMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 服务（Spring 管理）
 * 从数据库配置获取 BASE_URL、API_KEY、MODEL 并发起 AI 请求。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiService {
    private static final Duration AI_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    public static final String DEFAULT_SCREEN_PROMPT = """
            你是严格的岗位匹配评分器。候选人资料只用于判断岗位匹配度，不得编造候选人经历。
            对输入的岗位逐项评分，输出唯一 JSON 对象，不要 Markdown、解释或额外字段。
            score 为 0-100 的整数；PASS 表示达到通过分数且没有硬性冲突；REVIEW 表示信息不足或接近阈值；SKIP 表示明显不匹配。
            reasonCodes 只能使用 DIRECTION_MATCH、SKILL_MATCH、EXPERIENCE_MATCH、SALARY_RISK、HARD_MISMATCH、INSUFFICIENT_INFO。
            输出格式：{"items":[{"jobId":"岗位ID","score":0,"decision":"PASS|REVIEW|SKIP","reasonCodes":[],"reason":"不超过40字"}]}

            候选人资料：{{candidate}}
            搜索关键词：{{keyword}}
            通过分数：{{min_score}}
            评估规则：{{rules}}
            待评估岗位 JSON：{{jobs}}
            """;

    public static final String DEFAULT_MESSAGE_PROMPT = """
            你是求职沟通文案生成器。只为已通过评分的岗位生成一条可直接发送的中文招呼语。
            只能使用候选人资料和岗位 JSON 中出现的事实，不得虚构经历、证书、项目或工作年限。
            每条话术只写一句，单行、简短、自然，控制在40个汉字以内，不使用 Markdown，不输出解释。
            话术必须以“您好，我是应用物理学应届毕业生，”开头，只补充一个与岗位最相关的匹配点并以“期待沟通”收尾；正文排除候选人姓名和“我叫/姓名/名字是”等自我命名表达。
            输出格式：{"items":[{"jobId":"岗位ID","message":"招呼语"}]}

            候选人资料：{{candidate}}
            文案风格规则：{{style_rules}}
            已通过岗位 JSON：{{accepted_jobs}}
            """;

    private static final Pattern NAMED_PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final ConfigService configService;
    private final AiMapper aiMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record RequestOptions(double temperature, int maxOutputTokens, boolean structured) {
        public static RequestOptions standard() {
            return new RequestOptions(0.5, 0, false);
        }

        public static RequestOptions structured(double temperature, int maxOutputTokens) {
            return new RequestOptions(temperature, maxOutputTokens, true);
        }
    }

    /**
     * 发送 AI 请求（非流式）并返回回复内容。
     * @param content 用户消息内容
     * @return AI 回复文本
     */
    public String sendRequest(String content) {
        return sendRequest(content, RequestOptions.standard());
    }

    /** 发送低温度、有限输出的 JSON 请求，供批量评分和话术生成使用。 */
    public String sendStructuredRequest(String content, double temperature, int maxOutputTokens) {
        return sendRequest(content, RequestOptions.structured(temperature, maxOutputTokens));
    }

    public String sendRequest(String content, RequestOptions options) {
        RequestOptions resolved = options == null ? RequestOptions.standard() : options;
        return sendRequestInternal(content, resolved, true);
    }

    private String sendRequestInternal(String content, RequestOptions options, boolean allowStructuredFallback) {
        var cfg = configService.getAiConfigs();
        String baseUrl = cfg.get("BASE_URL");
        String apiKey = cfg.get("API_KEY");
        String model = cfg.get("MODEL");
        String endpoint = isResponsesModel(model)
                ? buildResponsesEndpoint(baseUrl)
                : buildChatCompletionsEndpoint(baseUrl);

        JSONObject requestData = new JSONObject();
        requestData.put("model", model);
        requestData.put("temperature", options.temperature());
        if (options.maxOutputTokens() > 0) {
            requestData.put(endpoint.endsWith("/responses") ? "max_output_tokens" : "max_tokens",
                    options.maxOutputTokens());
        }
        if (endpoint.endsWith("/responses")) {
            requestData.put("input", content);
            if (options.structured()) {
                requestData.put("text", new JSONObject().put("format", new JSONObject().put("type", "json_object")));
            }
        } else {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", content));
            requestData.put("messages", messages);
            if (options.structured()) {
                requestData.put("response_format", new JSONObject().put("type", "json_object"));
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("api-key", apiKey)
                .timeout(AI_REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestData.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return extractResponseContent(response.body(), endpoint);
            }

            log.error("AI请求失败: status={}, endpoint={}, body={}", response.statusCode(), endpoint, response.body());
            if (options.structured() && allowStructuredFallback && isStructuredOptionError(response.body())) {
                log.warn("当前 AI 兼容层不接受结构化参数，降级为严格 JSON 文本请求");
                return sendRequestInternal(content,
                        new RequestOptions(options.temperature(), options.maxOutputTokens(), false), false);
            }
            if (!endpoint.endsWith("/responses") && containsReasoningParamError(response.body())) {
                String fallbackEndpoint = buildResponsesEndpoint(baseUrl);
                log.warn("检测到 reasoning 相关参数错误，自动切换到 Responses API 重试: {}", fallbackEndpoint);
                return sendRequestViaResponses(content, apiKey, model, fallbackEndpoint);
            }
            throw new RuntimeException("AI请求失败，状态码: " + response.statusCode() + ", 详情: " + response.body());
        } catch (Exception e) {
            log.error("调用AI服务异常", e);
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    private String extractResponseContent(String body, String endpoint) {
        JSONObject responseObject = new JSONObject(body);
        String requestId = responseObject.optString("id");
        String usedModel = responseObject.optString("model");
        JSONObject usageObject = responseObject.optJSONObject("usage");
        log.info("AI响应: id={}, model={}, promptTokens={}, completionTokens={}, totalTokens={}",
                requestId, usedModel,
                usageObject == null ? -1 : usageObject.optInt("prompt_tokens", -1),
                usageObject == null ? -1 : usageObject.optInt("completion_tokens", -1),
                usageObject == null ? -1 : usageObject.optInt("total_tokens", -1));

        if (endpoint.endsWith("/responses")) {
            String output = responseObject.optString("output_text", null);
            if (output != null && !output.isBlank()) return output;
            try {
                return responseObject.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content");
            } catch (Exception ignored) {
                return body;
            }
        }
        return responseObject.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");
    }

    private boolean isStructuredOptionError(String body) {
        if (body == null) return false;
        String normalized = body.toLowerCase();
        return normalized.contains("response_format")
                || normalized.contains("json_object")
                || normalized.contains("max_tokens")
                || normalized.contains("max_output_tokens")
                || normalized.contains("unsupported");
    }

    /**
     * 按生产投递使用的参数顺序组装 AI 提示词。
     * 提示词模板必须包含五个 %s：个人介绍、关键词、岗位名称、岗位要求、参考语。
     */
    public String renderPrompt(String prompt, String introduce, String keyword, String jobName,
                               String jd, String sayHi) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("AI提示词不能为空");
        }

        int placeholderCount = prompt.split("%s", -1).length - 1;
        if (placeholderCount != 5) {
            throw new IllegalArgumentException("AI提示词必须包含5个%s占位符，当前为" + placeholderCount + "个");
        }

        try {
            return String.format(prompt, introduce, keyword, jobName, jd, sayHi);
        } catch (IllegalFormatException e) {
            throw new IllegalArgumentException("AI提示词格式不正确，请检查%s占位符", e);
        }
    }

    /** 渲染批量 AI 使用的命名模板，避免位置占位符错位。 */
    public String renderNamedTemplate(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("AI模板不能为空");
        }
        Map<String, String> safeValues = values == null ? Map.of() : values;
        Matcher matcher = NAMED_PLACEHOLDER.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!safeValues.containsKey(name)) {
                throw new IllegalArgumentException("AI模板包含未提供的变量: " + name);
            }
            String value = safeValues.get(name);
            if (value == null) {
                throw new IllegalArgumentException("AI模板变量不能为空: " + name);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    public String getCurrentModel() {
        return configService.getConfigValue("MODEL");
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "";
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * 获取可用模型列表（OpenAI 兼容 /v1/models）。
     * 优先使用入参 baseUrl/apiKey；缺省时回退到数据库已保存配置。
     *
     * @param baseUrl 可选 API Base URL
     * @param apiKey  可选 API Key
     * @return 模型 ID 列表（去重、排序）
     */
    public List<String> listModels(String baseUrl, String apiKey) {
        String resolvedBaseUrl = baseUrl;
        String resolvedApiKey = apiKey;

        // 回退到数据库单项配置；不走 getAiConfigs()，避免 MODEL 未配置时误失败
        if (resolvedBaseUrl == null || resolvedBaseUrl.isBlank()) {
            resolvedBaseUrl = configService.getConfigValue("BASE_URL");
        }
        if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
            resolvedApiKey = configService.getConfigValue("API_KEY");
        }

        if (resolvedBaseUrl == null || resolvedBaseUrl.isBlank()) {
            throw new IllegalStateException("缺少 API Base URL，请先填写或保存 BASE_URL");
        }
        if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
            throw new IllegalStateException("缺少 API Key，请先填写或保存 API_KEY");
        }

        String endpoint = buildModelsEndpoint(resolvedBaseUrl);
        int timeoutInSeconds = 30;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutInSeconds))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutInSeconds))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + resolvedApiKey)
                .header("api-key", resolvedApiKey)
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("获取模型列表失败: status={}, endpoint={}, body={}",
                        response.statusCode(), endpoint, response.body());
                throw new RuntimeException("获取模型列表失败，状态码: " + response.statusCode()
                        + ", 详情: " + response.body());
            }

            List<String> models = parseModelIds(response.body());
            log.info("获取模型列表成功: endpoint={}, count={}", endpoint, models.size());
            return models;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取模型列表异常: endpoint={}", endpoint, e);
            throw new RuntimeException("获取模型列表异常: " + e.getMessage(), e);
        }
    }

    /**
     * 构造 OpenAI 兼容的 /models 端点，避免重复拼接 /v1
     */
    private String buildModelsEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (normalized.endsWith("/models")) {
            return normalized;
        }
        if (normalized.endsWith("/v1") || normalized.contains("/v1/")) {
            return normalized + "/models";
        }
        return normalized + "/v1/models";
    }

    /**
     * 解析 /models 响应，兼容常见 OpenAI 及代理返回结构。
     */
    private List<String> parseModelIds(String body) {
        Set<String> ids = new LinkedHashSet<>();
        if (body == null || body.isBlank()) {
            return List.of();
        }

        Object root = new org.json.JSONTokener(body).nextValue();
        if (root instanceof JSONObject obj) {
            // 标准: { data: [ { id: "..." }, ... ] }
            if (obj.has("data")) {
                Object data = obj.get("data");
                if (data instanceof JSONArray arr) {
                    collectModelIdsFromArray(arr, ids);
                }
            }
            // 部分代理: { models: [...] }
            if (obj.has("models")) {
                Object models = obj.get("models");
                if (models instanceof JSONArray arr) {
                    collectModelIdsFromArray(arr, ids);
                }
            }
            // 极少数: { id: "single-model" }
            if (ids.isEmpty() && obj.has("id")) {
                String id = obj.optString("id", "").trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        } else if (root instanceof JSONArray arr) {
            collectModelIdsFromArray(arr, ids);
        }

        List<String> result = new ArrayList<>(ids);
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private void collectModelIdsFromArray(JSONArray arr, Set<String> ids) {
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.get(i);
            if (item instanceof String s) {
                String id = s.trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            } else if (item instanceof JSONObject modelObj) {
                String id = modelObj.optString("id", "").trim();
                if (id.isEmpty()) {
                    id = modelObj.optString("name", "").trim();
                }
                if (id.isEmpty()) {
                    id = modelObj.optString("model", "").trim();
                }
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
    }

    /**
     * 根据配置构造 chat/completions 端点，避免重复拼接 /v1
     */
    private String buildChatCompletionsEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        // 如果 baseUrl 已经包含 /v1（常见配置为 https://api.openai.com/v1），则只拼接 /chat/completions
        if (normalized.endsWith("/v1") || normalized.contains("/v1/")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    /**
     * 构造 Responses API 端点
     */
    private String buildResponsesEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (normalized.endsWith("/v1") || normalized.contains("/v1/")) {
            return normalized + "/responses";
        }
        return normalized + "/v1/responses";
    }

    /**
     * 粗略识别需要使用 Responses API 的模型（o-系列、4.1、reasoner 等）
     */
    private boolean isResponsesModel(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.contains("o1") || m.contains("o3") || m.contains("o4")
                || m.contains("4.1") || m.contains("reasoner")
                || m.contains("4o-mini") || m.contains("gpt-4o-mini");
    }

    /**
     * 检查错误响应中是否包含 reasoning 相关参数错误（如 reasoning.summary unsupported_value）
     */
    private boolean containsReasoningParamError(String body) {
        if (body == null) return false;
        String s = body.toLowerCase();
        return (s.contains("reasoning") && s.contains("unsupported_value"))
                || s.contains("reasoning.summary");
    }

    /**
     * 使用 Responses API 发送一次请求（用于自动降级/重试）
     */
    private String sendRequestViaResponses(String content, String apiKey, String model, String endpoint) {
        int timeoutInSeconds = 10;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutInSeconds))
                .build();

        JSONObject requestData = new JSONObject();
        requestData.put("model", model);
        requestData.put("temperature", 0.5);
        requestData.put("input", content);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("api-key", apiKey)
                .timeout(AI_REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestData.toString()))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JSONObject resp = new JSONObject(response.body());
                String outputText = resp.optString("output_text", null);
                if (outputText != null && !outputText.isEmpty()) {
                    return outputText;
                }
                // 兜底解析：部分兼容层可能返回 choices/message 结构
                try {
                    JSONObject messageObject = resp.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message");
                    return messageObject.getString("content");
                } catch (Exception ignore) {
                }
                // 无法解析则直接返回原始体，避免空值中断流程
                return response.body();
            }
            log.error("Responses API 调用失败: status={}, endpoint={}, body={}", response.statusCode(), endpoint, response.body());
            throw new RuntimeException("AI请求失败，状态码: " + response.statusCode() + ", 详情: " + response.body());
        } catch (Exception e) {
            log.error("Responses API 调用异常", e);
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    // ================= 合并的 AI 配置管理方法 =================

    /**
     * 获取AI配置（获取最新一条，如果不存在则创建默认配置）
     */
    @Transactional(readOnly = true)
    public AiEntity getAiConfig() {
        var list = aiMapper.selectList(null);
        AiEntity aiEntity = (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);
        if (aiEntity == null) {
            aiEntity = createDefaultConfig();
        }
        if (aiEntity.getScreenPrompt() == null || aiEntity.getScreenPrompt().isBlank()) {
            aiEntity.setScreenPrompt(DEFAULT_SCREEN_PROMPT);
        }
        if (aiEntity.getMessagePrompt() == null || aiEntity.getMessagePrompt().isBlank()) {
            aiEntity.setMessagePrompt(DEFAULT_MESSAGE_PROMPT);
        }
        return aiEntity;
    }

    /**
     * 获取所有AI配置
     */
    @Transactional(readOnly = true)
    public java.util.List<AiEntity> getAllAiConfigs() {
        return aiMapper.selectList(null);
    }

    /**
     * 根据ID获取AI配置
     */
    @Transactional(readOnly = true)
    public AiEntity getAiConfigById(Long id) {
        return aiMapper.selectById(id);
    }

    /**
     * 保存或更新AI配置（introduce/prompt）
     */
    @Transactional
    public AiEntity saveOrUpdateAiConfig(String introduce, String prompt) {
        return saveOrUpdateAiConfig(introduce, prompt, null, null);
    }

    @Transactional
    public AiEntity saveOrUpdateAiConfig(String introduce, String prompt,
                                         String screenPrompt, String messagePrompt) {
        var list = aiMapper.selectList(null);
        AiEntity aiEntity = (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);

        if (aiEntity == null) {
            aiEntity = new AiEntity();
            aiEntity.setIntroduce(introduce);
            aiEntity.setPrompt(prompt);
            aiEntity.setScreenPrompt(screenPrompt == null || screenPrompt.isBlank()
                    ? DEFAULT_SCREEN_PROMPT : screenPrompt);
            aiEntity.setMessagePrompt(messagePrompt == null || messagePrompt.isBlank()
                    ? DEFAULT_MESSAGE_PROMPT : messagePrompt);
            aiEntity.setCreatedAt(java.time.LocalDateTime.now());
            aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
            aiMapper.insert(aiEntity);
            log.info("创建新的AI配置，ID: {}", aiEntity.getId());
        } else {
            aiEntity.setIntroduce(introduce);
            aiEntity.setPrompt(prompt);
            if (screenPrompt != null && !screenPrompt.isBlank()) {
                aiEntity.setScreenPrompt(screenPrompt);
            }
            if (messagePrompt != null && !messagePrompt.isBlank()) {
                aiEntity.setMessagePrompt(messagePrompt);
            }
            aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
            aiMapper.updateById(aiEntity);
            log.info("更新AI配置，ID: {}", aiEntity.getId());
        }

        return aiEntity;
    }

    /**
     * 删除AI配置
     */
    @Transactional
    public boolean deleteAiConfig(Long id) {
        int result = aiMapper.deleteById(id);
        if (result > 0) {
            log.info("删除AI配置成功，ID: {}", id);
            return true;
        }
        return false;
    }

    /**
     * 创建默认配置
     */
    @Transactional
    protected AiEntity createDefaultConfig() {
        AiEntity aiEntity = new AiEntity();
        aiEntity.setIntroduce("请在此填写您的技能介绍");
        aiEntity.setPrompt("请在此填写AI提示词模板");
        aiEntity.setScreenPrompt(DEFAULT_SCREEN_PROMPT);
        aiEntity.setMessagePrompt(DEFAULT_MESSAGE_PROMPT);
        aiEntity.setCreatedAt(java.time.LocalDateTime.now());
        aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
        aiMapper.insert(aiEntity);
        log.info("创建默认AI配置，ID: {}", aiEntity.getId());
        return aiEntity;
    }
}
