package com.csys.logdetector.llm.dto;

import com.csys.logdetector.llm.entity.LlmCallLog;

import java.time.LocalDateTime;

/**
 * 调用留痕的一条记录（用于导出与前端展示）。
 */
public record LlmCallView(Long id, String model, long costMs, int promptTokens, int completionTokens,
                          LocalDateTime createdAt) {

    public static LlmCallView from(LlmCallLog log) {
        return new LlmCallView(
                log.getId(),
                log.getModel(),
                log.getCostMs() == null ? 0L : log.getCostMs(),
                log.getPromptTokens() == null ? 0 : log.getPromptTokens(),
                log.getCompletionTokens() == null ? 0 : log.getCompletionTokens(),
                log.getCreatedAt());
    }
}
