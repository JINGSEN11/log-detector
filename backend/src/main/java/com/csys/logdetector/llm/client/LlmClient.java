package com.csys.logdetector.llm.client;

import com.csys.logdetector.llm.dto.LlmResult;

import com.csys.logdetector.common.BizException;

/**
 * 大模型客户端抽象：业务代码只依赖本接口，便于切换厂商、替换 Mock、做缓存与降级。
 *  作用: 定义调用接口
 *
 * <pre>
 * LlmResult result = llmClient.chat("请总结这段日志：...");
 * MyDto dto = llmClient.chatAsJson(prompt, MyDto.class);
 * </pre>
 */
public interface LlmClient {

    /**
     * 纯文本对话。
     *
     * @param prompt 用户输入（业务侧应拼好系统提示与约束）
     * @return 正文与用量
     */
    LlmResult chat(String prompt);

    /**
     * 结构化输出：要求模型返回 JSON，并直接反序列化为给定类型。
     *
     * <p>返回内容不是合法 JSON 时抛 {@link BizException}（502），
     * 调用方可据此重试或降级到规则结果。</p>
     */
    <T> T chatAsJson(String prompt, Class<T> responseType);
}
