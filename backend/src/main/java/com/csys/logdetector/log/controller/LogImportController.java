package com.csys.logdetector.log.controller;

import com.csys.logdetector.log.service.LogImportService;

import com.csys.logdetector.common.Result;
import com.csys.logdetector.log.dto.LogImportResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/logs")
public class LogImportController {

    private final LogImportService logImportService;

    public LogImportController(LogImportService logImportService) {
        this.logImportService = logImportService;
    }

    @GetMapping("/batches/{batchId}/entries")
    public Result<LogImportResult.Page> entries(@PathVariable long batchId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "100") int pageSize) {
        return Result.ok(logImportService.getBatchEntries(batchId, page, pageSize));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<LogImportResult> importFile(@RequestPart("file") MultipartFile file,
                                               @RequestParam(defaultValue = "auto") String format) {
        return Result.ok(logImportService.importFile(file, format));
    }
}
