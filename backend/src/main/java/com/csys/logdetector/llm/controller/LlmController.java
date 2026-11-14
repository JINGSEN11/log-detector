package com.csys.logdetector.llm.controller;

import com.csys.logdetector.common.Result;
import com.csys.logdetector.llm.dto.LlmCallView;
import com.csys.logdetector.llm.dto.LlmChatRequest;
import com.csys.logdetector.llm.dto.LlmChatResponse;
import com.csys.logdetector.llm.dto.LlmConfigView;
import com.csys.logdetector.llm.service.LlmService;
import com.csys.logdetector.security.RequestRateLimiter;
import com.csys.logdetector.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 大模型调试接口：验证接入是否可用，并导出调用留痕。 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmService llmService;
    private final RequestRateLimiter rateLimiter;

    public LlmController(LlmService llmService, RequestRateLimiter rateLimiter) {
        this.llmService = llmService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/chat")
    public Result<LlmChatResponse> chat(@Valid @RequestBody LlmChatRequest request) {
        rateLimiter.llmChat(SecurityUtils.currentUser().id());
        return Result.ok(llmService.chat(request.prompt()));
    }

    @GetMapping("/config")
    public Result<LlmConfigView> config() {
        return Result.ok(llmService.config());
    }

    @GetMapping("/calls")
    public Result<List<LlmCallView>> calls(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(llmService.calls(limit));
    }
}
