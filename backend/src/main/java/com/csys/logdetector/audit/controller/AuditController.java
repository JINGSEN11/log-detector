package com.csys.logdetector.audit.controller;

import com.csys.logdetector.audit.service.AuditService;
import com.csys.logdetector.common.PageResult;
import com.csys.logdetector.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 管理员查询已脱敏的审计记录。 */

/**
 * 记录: 登录成功与失败, 导入操作(数据, 批次, 跳过行数等), ai研判(成功/失败)
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(auditService.list(page, size));
    }
}
