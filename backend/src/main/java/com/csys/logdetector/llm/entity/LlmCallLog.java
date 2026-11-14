package com.csys.logdetector.llm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 大模型调用留痕表 llm_call：记录成功调用的 model / costMs / promptTokens / completionTokens。
 *
 * <p>调用失败或落库失败时可能没有记录，不能作为完整调用统计；
 * 已保存的记录可通过表或 {@code GET /api/llm/calls} 查询。</p>
 */
@TableName("llm_call")
public class LlmCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String model;

    private Long costMs;

    private Integer promptTokens;

    private Integer completionTokens;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getCostMs() {
        return costMs;
    }

    public void setCostMs(Long costMs) {
        this.costMs = costMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
