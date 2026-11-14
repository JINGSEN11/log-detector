package com.csys.logdetector.llm.client;

import com.csys.logdetector.llm.dto.LlmResult;
import com.csys.logdetector.llm.service.LlmCallRecorder;

import com.csys.logdetector.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 断网调试用的假实现：{@code LLM_MOCK=true} 时生效，返回固定样例。
 *  作用: 规则降级
 *
 * <p><b>只能用于本地调试</b>。验收时必须切回真实调用：改错 Key / 断网要能看到降级提示，
 * 而不是继续看到这里的固定文案（写死假结果冒充模型输出属于一票否决项）。</p>
 */
@Service
@ConditionalOnProperty(name = "llm.mock", havingValue = "true", matchIfMissing = true)
public class MockLlmClient extends AbstractLlmClient {

    private static final String MODEL = "mock-model";

    public MockLlmClient(ObjectMapper objectMapper, LlmCallRecorder callRecorder) {
        super(objectMapper, callRecorder);
    }

    @Override
    public LlmResult chat(String prompt) {
        long startedAt = System.currentTimeMillis();
        String content = "【Mock 模式】这是脚手架内置的固定样例返回，仅用于断网调试。"
                + "收到输入长度：" + (prompt == null ? 0 : prompt.length()) + " 字符。"
                + "请把 LLM_MOCK 设为 false 并配置 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL 后使用真实模型。";
        LlmResult result = new LlmResult(content, MODEL, 0, 0, System.currentTimeMillis() - startedAt);
        callRecorder.record(result);
        return result;
    }

    @Override
    public <T> T chatAsJson(String prompt, Class<T> responseType) {
        chat(prompt);
        throw new BizException(503, "当前为 LLM_MOCK 模式，结构化输出需要真实模型：请设置 LLM_MOCK=false 并配置 LLM_*");
    }
}
