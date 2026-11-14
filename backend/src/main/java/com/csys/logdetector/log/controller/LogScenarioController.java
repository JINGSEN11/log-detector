package com.csys.logdetector.log.controller;

import com.csys.logdetector.common.Result;
import com.csys.logdetector.log.dto.LogImportResult;
import com.csys.logdetector.log.service.LogScenarioService;
import com.csys.logdetector.log.service.LogScenarioService.ScenarioInfo;
import com.csys.logdetector.log.service.LogScenarioService.ScenarioPreview;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/logs/scenarios")
public class LogScenarioController {

    private final LogScenarioService logScenarioService;

    public LogScenarioController(LogScenarioService logScenarioService) {
        this.logScenarioService = logScenarioService;
    }

    @GetMapping
    public Result<List<ScenarioInfo>> list() {
        return Result.ok(logScenarioService.list());
    }

    @GetMapping("/{scenario}/preview")
    public Result<ScenarioPreview> preview(@PathVariable String scenario,
            @RequestParam(required = false) Long seed, @RequestParam(required = false) Long baseTime,
            @RequestParam(required = false) Integer count) {
        return Result.ok(logScenarioService.preview(scenario, seed, baseTime, count));
    }

    @GetMapping("/batches/{batchId}/replay")
    public Result<ScenarioPreview> replay(@PathVariable long batchId) {
        return Result.ok(logScenarioService.replay(batchId));
    }

    @PostMapping("/{scenario}/import")
    public Result<LogImportResult> importScenario(@PathVariable String scenario,
            @RequestParam(required = false) Long seed, @RequestParam(required = false) Long baseTime,
            @RequestParam(required = false) Integer count) {
        return Result.ok(logScenarioService.importScenario(scenario, seed, baseTime, count));
    }
}
