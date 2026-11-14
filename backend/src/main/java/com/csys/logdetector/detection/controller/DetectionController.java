package com.csys.logdetector.detection.controller;

import com.csys.logdetector.detection.service.DetectionService;

import com.csys.logdetector.common.Result;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 接收批次检测和事件查询请求，交给检测服务处理并返回结果。 */
@RestController
@RequestMapping("/api")
public class DetectionController {
    private final DetectionService service;
    public DetectionController(DetectionService service) { this.service = service; }
    // 执行指定批次的检测。
    @PostMapping("/detection/batches/{batchId}")
    public Result<Map<String, Object>> detect(@PathVariable long batchId) { return Result.ok(service.detect(batchId)); }
    // 查询事件列表。
    @GetMapping("/events")
    public Result<List<Map<String, Object>>> events(@RequestParam(value = "batchId", required = false) Long batchId) { return Result.ok(service.events(batchId)); }
    // 查询事件详情及证据。
    @GetMapping("/events/{id}")
    public Result<Map<String, Object>> event(@PathVariable long id) { return Result.ok(service.event(id)); }
}
