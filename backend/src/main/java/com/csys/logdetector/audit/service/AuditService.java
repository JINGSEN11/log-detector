package com.csys.logdetector.audit.service;

import com.csys.logdetector.audit.mapper.AuditMapper;
import com.csys.logdetector.common.PageResult;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 处理分页查询, 再通过AuditMapper访问数据库
 */
@Service
public class AuditService {
    private final AuditMapper auditMapper;

    public AuditService(AuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    public PageResult<Map<String, Object>> list(long page, long size) {
        long current = Math.max(1, page);
        long limit = Math.max(1, Math.min(100, size));
        long total = auditMapper.count();
        // Avoid arithmetic overflow and unnecessary database queries for out-of-range pages.
        if (current - 1 > total / limit) {
            return PageResult.of(java.util.List.of(), total, current, limit);
        }
        return PageResult.of(auditMapper.list(limit, (current - 1) * limit), total, current, limit);
    }
}
