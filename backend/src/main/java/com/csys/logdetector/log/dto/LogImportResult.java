package com.csys.logdetector.log.dto;

import java.util.List;

public record LogImportResult(
        Long batchId,
        String contentSha256,
        String status,
        int totalLines,
        int parsedLines,
        int skippedLines,
        List<SanitizedLogPreview> preview
) {
    public record Page(long batchId, int page, int pageSize, long total, List<SanitizedLogPreview> items) {
    }

    public record SanitizedLogPreview(int lineNo, String pseudonymousIp, String maskedIp,
                                      String timestamp, String method, String path,
                                      int status, String referer, String userAgent, String rawLine) {
    }
}
