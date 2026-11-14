package com.csys.logdetector.web;

import com.csys.logdetector.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：{@code GET /api/health} 不需要登录，供 compose healthcheck 与联调自查使用。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("app", "csys-template");
        data.put("time", LocalDateTime.now().toString());
        return Result.ok(data);
    }
}
