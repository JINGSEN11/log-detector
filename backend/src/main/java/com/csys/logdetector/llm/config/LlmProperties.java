package com.csys.logdetector.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 大模型配置：全部可由环境变量覆盖，代码里不出现任何密钥。
 *
 * 作用: 读取模型相关配置
 * <pre>
 * LLM_MOCK=true|false
 * LLM_BASE_URL=https://api.deepseek.com/v1
 * LLM_API_KEY=sk-xxxx
 * LLM_MODEL=deepseek-chat
 * LLM_TIMEOUT_SECONDS=60
 * </pre>
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        boolean mock) {

    public String chatEndpoint() {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
