package com.csys.logdetector.llm.service;

import com.csys.logdetector.llm.dto.LlmResult;
import com.csys.logdetector.llm.entity.LlmCallLog;

import com.csys.logdetector.llm.mapper.LlmCallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 调用留痕：写日志 + 落库。
 *
 * 作用: 记录模型调用信息
 * <p>落库失败只打警告，不影响业务主流程（数据库抖动不应该让大模型调用失败）。</p>
 */
@Component
public class LlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmCallRecorder.class);

    private final LlmCallMapper llmCallMapper;

    public LlmCallRecorder(LlmCallMapper llmCallMapper) {
        this.llmCallMapper = llmCallMapper;
    }

    public void record(LlmResult result) {
        log.info("llm call model={} costMs={} promptTokens={} completionTokens={}",
                result.getModel(), result.getCostMs(), result.getPromptTokens(), result.getCompletionTokens());
        try {
            LlmCallLog entry = new LlmCallLog();
            entry.setModel(result.getModel());
            entry.setCostMs(result.getCostMs());
            entry.setPromptTokens(result.getPromptTokens());
            entry.setCompletionTokens(result.getCompletionTokens());
            entry.setCreatedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
            llmCallMapper.insert(entry);
        } catch (RuntimeException ex) {
            log.warn("写入 llm_call 失败（不影响主流程）：{}", ex.getMessage());
        }
    }
}
