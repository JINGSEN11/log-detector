package com.csys.logdetector.llm.dto;

/**
 * 一次大模型调用的结果：正文 + 调用元数据。
 *
 * <p>成功调用后会尝试将元数据写入 llm_call 表；写入失败不影响调用结果，
 * 因此该表不保证覆盖所有调用。</p>
 */
public class LlmResult {

    private final String content;
    private final String model;
    private final int promptTokens;
    private final int completionTokens;
    private final long costMs;
    private final boolean usageKnown;

    public LlmResult(String content, String model, int promptTokens, int completionTokens, long costMs) {
        this(content, model, promptTokens, completionTokens, costMs, true);
    }

    public LlmResult(String content, String model, int promptTokens, int completionTokens, long costMs, boolean usageKnown) {
        this.usageKnown = usageKnown;
        this.content = content;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.costMs = costMs;
    }

    public String getContent() {
        return content;
    }

    public String getModel() {
        return model;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public long getCostMs() {
        return costMs;
    }

    public boolean isUsageKnown() { return usageKnown; }

    public int getTotalTokens() {
        return promptTokens + completionTokens;
    }
}
