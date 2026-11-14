package com.csys.logdetector.llm.client;

import com.csys.logdetector.llm.dto.LlmResult;
import com.csys.logdetector.llm.service.LlmCallRecorder;

import com.csys.logdetector.common.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 两个实现的公共部分：JSON 反序列化与调用留痕。
 */
public abstract class AbstractLlmClient implements LlmClient {

    protected final ObjectMapper objectMapper;
    protected final LlmCallRecorder callRecorder;

    protected AbstractLlmClient(ObjectMapper objectMapper, LlmCallRecorder callRecorder) {
        this.objectMapper = objectMapper;
        this.callRecorder = callRecorder;
    }

    @Override
    public <T> T chatAsJson(String prompt, Class<T> responseType) {
        LlmResult result = chat(prompt);
        return parseJson(result.getContent(), responseType);
    }

    protected <T> T parseJson(String content, Class<T> responseType) {
        String json = stripCodeFence(content);
        try {
            return objectMapper.readValue(json, responseType);
        } catch (JsonProcessingException ex) {
            throw new BizException(502, "大模型返回内容不是合法 JSON，无法按结构化结果解析：" + ex.getOriginalMessage());
        }
    }

    /**
     * 去掉模型偶尔加上的 ```json 代码围栏。
     */
    protected String stripCodeFence(String content) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text.replace("```", "").trim();
        }
        String body = text.substring(firstLineEnd + 1);
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.trim();
    }
}
