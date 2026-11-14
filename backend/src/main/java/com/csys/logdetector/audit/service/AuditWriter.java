package com.csys.logdetector.audit.service;

import org.slf4j.MDC;
import com.csys.logdetector.audit.mapper.AuditMapper;
import org.springframework.stereotype.Service;

/** Records only bounded metadata, never credentials or log contents. */

/**
 * 写入审计记录, 接受操作者, 操作类型, 目标等信息
 */
@Service
public class AuditWriter {
    private final AuditMapper mapper;

    public AuditWriter(AuditMapper mapper) { this.mapper = mapper; }

    public void record(Long actorId, String action, String targetType, String targetId) {
        mapper.insert(actorId, action, targetType, targetId, MDC.get("traceId"));// 操作者, 操作类型, 目标类型, 目标 ID, trace ID
    }
}
