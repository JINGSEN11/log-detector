package com.csys.logdetector.detection.controller;

import com.csys.logdetector.detection.service.EventWorkflowService;

import com.csys.logdetector.common.Result;
import com.csys.logdetector.security.SecurityUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 提供事件状态更新、时间线查询和报告导出接口。 */
@RestController
@RequestMapping("/api/events")
public class EventWorkflowController {
    private final EventWorkflowService service;
    public EventWorkflowController(EventWorkflowService service) { this.service = service; }
    public record StatusRequest(String status, String note) {}

    @PostMapping("/{id}/status")
    public Result<Map<String,Object>> status(@PathVariable long id, @RequestBody StatusRequest request) {
        return Result.ok(service.updateStatus(id, request.status(), request.note(), SecurityUtils.currentUser().id()));
    }
    @GetMapping("/{id}/timeline")
    public Result<List<Map<String,Object>>> timeline(@PathVariable long id) { return Result.ok(service.timeline(id)); }
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> csv(@RequestParam(value = "batchId", required = false) Long batchId) { return download(service.csv(SecurityUtils.currentUser().id(), batchId), "events.csv", "text/csv"); }
    @GetMapping("/{id}/report.md")
    public ResponseEntity<byte[]> report(@PathVariable long id) { return download(service.markdown(id, SecurityUtils.currentUser().id()), "event-" + id + ".md", "text/markdown"); }
    @GetMapping("/{id}/report.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("event-" + id + ".pdf").build().toString())
                .contentType(MediaType.APPLICATION_PDF).body(service.pdf(id, SecurityUtils.currentUser().id()));
    }
    private ResponseEntity<byte[]> download(String value, String name, String mediaType) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(new MediaType(MediaType.parseMediaType(mediaType), StandardCharsets.UTF_8))
                .body(value.getBytes(StandardCharsets.UTF_8));
    }
}
