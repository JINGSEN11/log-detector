package com.csys.logdetector.detection.controller;

import com.csys.logdetector.detection.service.DashboardService;

import com.csys.logdetector.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 提供仪表盘统计和日志上下文接口，将查询交给仪表盘服务。 */
@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardService service;
    public DashboardController(DashboardService service) { this.service = service; }
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() { return Result.ok(service.dashboard()); }
    @GetMapping("/logs/{logId}/context")
    public Result<Map<String, Object>> context(@PathVariable long logId,
                                               @RequestParam(defaultValue = "5") int before,
                                               @RequestParam(defaultValue = "5") int after) {
        return Result.ok(service.context(logId, before, after));
    }
}
