package com.csys.logdetector.llm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.csys.logdetector.llm.client.LlmClient;
import com.csys.logdetector.llm.config.LlmProperties;
import com.csys.logdetector.llm.dto.LlmCallView;
import com.csys.logdetector.llm.dto.LlmChatResponse;
import com.csys.logdetector.llm.dto.LlmConfigView;
import com.csys.logdetector.llm.dto.LlmResult;
import com.csys.logdetector.llm.entity.LlmCallLog;
import com.csys.logdetector.llm.mapper.LlmCallMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LlmService {

    private final LlmClient llmClient;
    private final LlmProperties properties;
    private final LlmCallMapper llmCallMapper;

    public LlmService(LlmClient llmClient, LlmProperties properties, LlmCallMapper llmCallMapper) {
        this.llmClient = llmClient;
        this.properties = properties;
        this.llmCallMapper = llmCallMapper;
    }

    public LlmChatResponse chat(String prompt) {
        LlmResult result = llmClient.chat(prompt);
        return new LlmChatResponse(result.getContent(), result.getModel(), result.getCostMs(),
                result.getPromptTokens(), result.getCompletionTokens());
    }

    public LlmConfigView config() {
        return new LlmConfigView(properties.model(), properties.mock(), properties.baseUrl(),
                properties.hasApiKey(), properties.timeoutSeconds());
    }

    public List<LlmCallView> calls(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<LlmCallLog> logs = llmCallMapper.selectList(Wrappers.<LlmCallLog>lambdaQuery()
                .orderByDesc(LlmCallLog::getId)
                .last("LIMIT " + safeLimit));
        return logs.stream().map(LlmCallView::from).toList();
    }
}
