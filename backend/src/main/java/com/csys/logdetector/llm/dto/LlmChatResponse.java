package com.csys.logdetector.llm.dto;

/**
 * 大模型调试响应体：正文 + 本次调用的用量（前端可直接展示"模型 / 耗时 / token"）。
 */
public record LlmChatResponse(String content, String model, long costMs, int promptTokens, int completionTokens) {
}
