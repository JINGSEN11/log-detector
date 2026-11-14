package com.csys.logdetector.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 大模型调试请求体。
 */
public record LlmChatRequest(
        @NotBlank(message = "prompt 不能为空")
        @Size(max = 4000, message = "prompt 长度不能超过 4000 字符")
        String prompt) {
}
