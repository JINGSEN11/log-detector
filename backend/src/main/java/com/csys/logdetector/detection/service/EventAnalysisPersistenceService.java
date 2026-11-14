package com.csys.logdetector.detection.service;


import com.csys.logdetector.common.BizException;
import com.csys.logdetector.detection.mapper.EventAnalysisJdbcRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.csys.logdetector.detection.mapper.EventAnalysisMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 保存事件分析及证据关联，并提供分析结果和历史查询。 */
@Service
public class EventAnalysisPersistenceService {
    private final EventAnalysisJdbcRepository repository;
    private final ObjectMapper json;

    public EventAnalysisPersistenceService(EventAnalysisMapper mapper, ObjectMapper json) {
        this.repository = new EventAnalysisJdbcRepository(mapper, json);
        this.json = json;
    }

    @Transactional
    public Map<String, Object> save(long eventId, Long actor, Map<String, Object> result) {
        String source = (String) result.get("source");
        if (!"MODEL".equals(source) && !"RULE_FALLBACK".equals(source)) throw new IllegalArgumentException("无效的研判来源");
        String status = "MODEL".equals(source) ? "SUCCESS" : "RULE_FALLBACK";
        String chain = writeJson(result.get("attackChain"));
        String recommendations = writeJson(result.get("recommendations"));
        long analysisId = repository.insert(eventId, actor, status, result, chain, recommendations);
        // 按攻击链顺序保存去重后的事件证据。
        Set<Long> seen = new LinkedHashSet<>();
        List<?> steps = (List<?>) result.get("attackChain");
        for (int stage = 0; stage < steps.size(); stage++) {
            Map<?, ?> step = (Map<?, ?>) steps.get(stage);
            for (Object value : (List<?>) step.get("logIds")) {
                long logId = ((Number) value).longValue();
                if (seen.add(logId)) {
                    Long matches = repository.evidenceCount(eventId, logId);
                    if (matches == null || matches == 0) throw new IllegalArgumentException("研判证据不属于该事件");
                    repository.insertEvidence(analysisId, logId, seen.size(), stage + 1);
                }
            }
        }
        return repository.get(analysisId);
    }

    @Transactional
    public Map<String, Object> saveWithCache(long eventId, Long actor, Map<String, Object> result, String fingerprint, Long sourceId, int savedTokens) {
        Map<String, Object> saved = save(eventId, actor, result);
        repository.insertCache(saved.get("id"), fingerprint, sourceId, savedTokens);
        saved.put("cacheStatus", sourceId == null ? "MISS" : "CACHED");
        saved.put("cacheSourceId", sourceId);
        return saved;
    }

    public Map<String, Object> get(long analysisId) {
        return repository.get(analysisId);
    }

    public Map<String, Object> latest(long eventId) {
        requireEvent(eventId);
        List<Map<String, Object>> rows = repository.latest(eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> list(long eventId, long page, long size) {
        requireEvent(eventId);
        long current = Math.max(1, page);
        long limit = Math.max(1, Math.min(100, size));
        Long count = repository.count(eventId);
        long total = Objects.requireNonNull(count);
        List<Map<String, Object>> records = current - 1 > total / limit ? List.of() : repository.list(eventId, limit, (current - 1) * limit);
        Map<String, Object> pageResult = new LinkedHashMap<>();
        pageResult.put("records", records); pageResult.put("total", total); pageResult.put("page", current); pageResult.put("size", limit);
        return pageResult;
    }

    private void requireEvent(long eventId) {
        Long count = repository.eventCount(eventId);
        if (count == null || count == 0) throw new BizException(404, "事件不存在");
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("无法序列化研判结果", ex); }
    }
}
