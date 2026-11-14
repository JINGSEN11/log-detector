package com.csys.logdetector.log.mapper;

import com.csys.logdetector.log.dto.LogImportResult.SanitizedLogPreview;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.slf4j.MDC;

@Mapper
public interface LogImportMapper {
    Integer countBySha256(@Param("sha256") String sha256);
    boolean batchExists(@Param("batchId") long batchId);
    long countSuccessfulEntries(@Param("batchId") long batchId);

    record PreviewRow(int lineNo, String pseudonymousIp, String maskedIp, LocalDateTime timestamp,
                      String method, String path, int status, String referer, String userAgent, String rawLine) {}

    List<PreviewRow> selectSuccessfulEntries(@Param("batchId") long batchId, @Param("limit") int limit,
                                             @Param("offset") long offset);

    default List<SanitizedLogPreview> pageSuccessfulEntries(long batchId, int limit, long offset) {
        return selectSuccessfulEntries(batchId, limit, offset).stream().map(row -> new SanitizedLogPreview(
                row.lineNo(), row.pseudonymousIp(), row.maskedIp(),
                DateTimeFormatter.ISO_INSTANT.format(row.timestamp().toInstant(ZoneOffset.ofHours(8))),
                row.method(), row.path(), row.status(), row.referer(), row.userAgent(), row.rawLine())).toList();
    }

    int insertBatchRow(Map<String, Object> batch);

    default Long insertBatch(Long actorId, String sourceType, String filename, String sha256,
                             String scenarioCode, String status, int total, int parsed, int skipped) {
        Map<String, Object> batch = new HashMap<>();
        batch.put("actorId", actorId); batch.put("sourceType", sourceType); batch.put("filename", filename);
        batch.put("sha256", sha256); batch.put("scenarioCode", scenarioCode); batch.put("status", status);
        batch.put("total", total); batch.put("parsed", parsed); batch.put("skipped", skipped);
        insertBatchRow(batch);
        Number id = (Number) batch.get("id");
        if (id == null) throw new IllegalStateException("无法获取导入批次 ID");
        return id.longValue();
    }

    int insertAuditRow(@Param("actorId") Long actorId, @Param("batchId") Long batchId,
                       @Param("sourceType") String sourceType, @Param("parsed") int parsed,
                       @Param("skipped") int skipped, @Param("traceId") String traceId);

    default void insertAudit(Long actorId, Long batchId, String sourceType, int parsed, int skipped) {
        insertAuditRow(actorId, batchId, sourceType, parsed, skipped, MDC.get("traceId"));
    }

    int insertEntriesChunk(@Param("rows") List<Object[]> rows);

    default void insertEntries(List<Object[]> rows) {
        // Bound the statement size for large imports while retaining the surrounding transaction.
        for (int start = 0; start < rows.size(); start += 500) {
            insertEntriesChunk(rows.subList(start, Math.min(start + 500, rows.size())));
        }
    }
}
