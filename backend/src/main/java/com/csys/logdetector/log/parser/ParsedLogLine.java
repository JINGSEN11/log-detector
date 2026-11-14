package com.csys.logdetector.log.parser;

import java.time.Instant;

/** 已解析的访问日志行；rawLine 仅用于入库前脱敏流程，不得直接返回或持久化。 */
public record ParsedLogLine(
        int lineNumber,
        String sourceIp,
        Instant timestamp,
        String method,
        String path,
        int status,
        long bytes,
        String referer,
        String userAgent,
        String rawLine
) {
}
