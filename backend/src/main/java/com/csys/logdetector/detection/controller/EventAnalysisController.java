package com.csys.logdetector.detection.controller;

import com.csys.logdetector.detection.service.EventAnalysisPersistenceService;
import com.csys.logdetector.detection.service.AnalysisCostService;

import com.csys.logdetector.common.Result;
import com.csys.logdetector.audit.service.AuditWriter;
import com.csys.logdetector.security.RequestRateLimiter;
import com.csys.logdetector.security.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** 接收事件分析及历史、成本查询请求，协调限流和审计。 */
@RestController
public class EventAnalysisController {
    private final AnalysisCostService service;
    private final RequestRateLimiter rateLimiter;
    private final AuditWriter auditWriter;
    private final EventAnalysisPersistenceService persistence;
    public EventAnalysisController(AnalysisCostService service, EventAnalysisPersistenceService persistence, RequestRateLimiter rateLimiter, AuditWriter auditWriter) {
        this.service = service;
        this.persistence = persistence;
        this.rateLimiter = rateLimiter;
        this.auditWriter = auditWriter;
    }
    /** 发起事件研判并记录审计结果。 */
    @PostMapping("/api/events/{id}/analysis")
    public Result<Map<String, Object>> analyze(@PathVariable long id) {
        Long actor = SecurityUtils.currentUser().id();
        rateLimiter.analysis(actor);
        Map<String, Object> result;
        try {
            result = service.analyze(id, actor);
        } catch (RuntimeException ex) {
            auditWriter.record(actor, "ANALYSIS_FAILURE", "EVENT", String.valueOf(id));
            throw ex;
        }
        auditWriter.record(actor, "ANALYSIS_SUCCESS", "EVENT", String.valueOf(id));
        return Result.ok(result);
    }

    @GetMapping("/api/analysis-cost")
    public Result<Map<String, Object>> cost() { return Result.ok(service.dashboard()); }

    @GetMapping("/api/events/{id}/analyses/latest")
    public Result<Map<String, Object>> latest(@PathVariable long id) {
        return Result.ok(persistence.latest(id));
    }

    @GetMapping("/api/events/{id}/analyses")
    public Result<Map<String, Object>> list(@PathVariable long id,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size) {
        return Result.ok(persistence.list(id, page, size));
    }
}
