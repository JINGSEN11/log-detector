package com.csys.logdetector.detection.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;

import java.util.*;

/** 读写事件分析结果、分析证据关联及缓存元数据。 */
public class EventAnalysisJdbcRepository {
    private final EventAnalysisMapper mapper;
    private final ObjectMapper json;

    public EventAnalysisJdbcRepository(EventAnalysisMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

    public long insert(long eventId, Long actor, String status, Map<String, Object> result, String chain, String recommendations) {
        Map<String, Object> values = new HashMap<>(result);
        values.put("eventId", eventId); values.put("actor", actor); values.put("status", status);
        values.put("model", Objects.toString(result.get("model"), ""));
        values.put("chain", chain); values.put("recommendations", recommendations);
        mapper.insert(values);
        Number generated = (Number) values.get("id");
        if (generated == null) throw new IllegalStateException("无法获取研判记录 ID");
        return generated.longValue();
    }
    public Long evidenceCount(long eventId, long logId) { return mapper.evidenceCount(eventId, logId); }
    public void insertEvidence(long analysisId, long logId, int order, int stage) { mapper.insertEvidence(analysisId, logId, order, stage); }
    public void insertCache(Object analysisId, String fingerprint, Long sourceId, int savedTokens) { mapper.insertCache(analysisId, fingerprint, sourceId, savedTokens); }
    public Map<String, Object> get(long analysisId) {
        Map<String, Object> row = mapper.get(analysisId);
        if (row == null) throw new EmptyResultDataAccessException(1);
        return analysis(row);
    }
    public List<Map<String, Object>> latest(long eventId) { return mapper.latest(eventId).stream().map(this::analysis).toList(); }
    public Long count(long eventId) { return mapper.count(eventId); }
    public List<Map<String, Object>> list(long eventId, long limit, long offset) { return mapper.list(eventId, limit, offset).stream().map(this::analysis).toList(); }
    public Long eventCount(long eventId) { return mapper.eventCount(eventId); }

    private Map<String, Object> analysis(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        String status = (String) row.get("status");
        result.put("id", row.get("id"));
        result.put("createdAt", row.get("createdAt"));
        result.put("requestedBy", row.get("requestedBy"));
        result.put("status", status);
        result.put("source", "SUCCESS".equals(status) ? "MODEL" : "RULE_FALLBACK");
        result.put("summary", row.get("summary"));
        result.put("attackChain", readJson((String) row.get("attackChain")));
        result.put("recommendations", readJson((String) row.get("recommendations")));
        result.put("confidence", row.get("confidence"));
        result.put("falsePositiveHint", row.get("falsePositiveHint"));
        String model = (String) row.get("model");
        result.put("model", model == null || model.isEmpty() ? null : model);
        result.put("costMs", row.getOrDefault("costMs", 0L));
        result.put("promptTokens", row.getOrDefault("promptTokens", 0));
        result.put("completionTokens", row.getOrDefault("completionTokens", 0));
        Object sourceId = row.get("cacheSourceId");
        result.put("cacheStatus", row.get("cacheRecordId") == null ? "LEGACY" : sourceId == null ? "MISS" : "CACHED");
        result.put("cacheSourceId", sourceId);
        return result;
    }
    private Object readJson(String value) {
        if (value == null) return null;
        try { return json.readValue(value, new TypeReference<Object>() {}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法读取研判结果", ex); }
    }
}
