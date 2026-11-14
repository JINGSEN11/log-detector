package com.csys.logdetector.llm.client;

import com.csys.logdetector.llm.config.LlmProperties;
import com.csys.logdetector.llm.dto.LlmResult;
import com.csys.logdetector.llm.service.LlmCallRecorder;

import com.csys.logdetector.common.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的实现（DeepSeek、通义、Kimi、Ollama、vLLM 等均可用）。
 *  作用: 对接模型服务
 * <p>只依赖 {@code base_url / api_key / model} 三项配置，换厂商无需改代码。
 * HTTP 使用 JDK 内置 {@link HttpClient}，不额外引入 WebClient / OkHttp 依赖，
 * 镜像更小、也不会与 Spring 的响应式栈冲突。</p>
 */
@Service
@ConditionalOnProperty(name = "llm.mock", havingValue = "false")
public class OpenAiCompatLlmClient extends AbstractLlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatLlmClient.class);

    private final LlmProperties properties;
    private final HttpClient httpClient;

    public OpenAiCompatLlmClient(ObjectMapper objectMapper,
                                 LlmCallRecorder callRecorder,
                                 LlmProperties properties) {
        super(objectMapper, callRecorder);
        this.properties = properties;
        int timeoutSeconds = Math.max(properties.timeoutSeconds(), 5);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 30)))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        log.info("大模型客户端初始化：baseUrl={} model={} apiKey={}",
                properties.baseUrl(), properties.model(), properties.hasApiKey() ? "已配置" : "未配置");
    }

    @Override
    public LlmResult chat(String prompt) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(buildPayload(prompt));
        } catch (JsonProcessingException ex) {
            throw new BizException(500, "构造大模型请求失败：" + ex.getOriginalMessage());
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.chatEndpoint()))
                .timeout(Duration.ofSeconds(Math.max(properties.timeoutSeconds(), 5)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (properties.hasApiKey()) {
            builder.header("Authorization", "Bearer " + properties.apiKey().trim());
        }

        long startedAt = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BizException(502, "大模型调用失败（网络不可达）：请检查 LLM_BASE_URL、网络与超时设置");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(502, "大模型调用被中断，请稍后重试");
        }
        long costMs = System.currentTimeMillis() - startedAt;

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException(502, "大模型调用失败（HTTP " + response.statusCode() + "）");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body() == null ? "" : response.body());
        } catch (Exception ex) {
            throw new BizException(502, "大模型返回内容无法解析为 JSON");
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new BizException(502, "大模型返回中没有 choices 字段，请检查 base_url 是否为 OpenAI 兼容接口");
        }
        JsonNode message = choices.get(0).path("message").path("content");
        String content = message.isMissingNode() ? "" : message.asText("");

        LlmResult result = new LlmResult(
                content,
                root.path("model").asText(properties.model()),
                root.path("usage").path("prompt_tokens").asInt(0),
                root.path("usage").path("completion_tokens").asInt(0),
                costMs,
                root.path("usage").path("prompt_tokens").isIntegralNumber() && root.path("usage").path("completion_tokens").isIntegralNumber());
        callRecorder.record(result);
        return result;
    }

    private Map<String, Object> buildPayload(String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.model());
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt == null ? "" : prompt)));
        payload.put("temperature", 0.2);
        payload.put("stream", false);
        return payload;
    }

    private String brief(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replaceAll("\\s+", " ").trim();
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
