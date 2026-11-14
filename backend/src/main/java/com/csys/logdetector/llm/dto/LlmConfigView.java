package com.csys.logdetector.llm.dto;

/**
 * 当前大模型配置（不含密钥本身，只说明是否已配置），便于现场核对"是否真的在调外部模型"。
 */
public record LlmConfigView(String model, boolean mock, String baseUrl, boolean apiKeyConfigured,
                            int timeoutSeconds) {
}
