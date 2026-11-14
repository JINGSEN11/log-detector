package com.csys.logdetector.detection.service;


import com.csys.logdetector.detection.mapper.AnalysisCostMapper;
import com.csys.logdetector.llm.config.LlmProperties;
import com.csys.logdetector.llm.dto.LlmResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.*;

/** Cache records are separate from legacy analyses; failed model attempts remain independently visible. 检查事件分析缓存，协调模型调用和结果保存，并汇总调用用量。 */
@Service
public class AnalysisCostService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AnalysisCostService.class);
    private static final Object[] LOCKS = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    // Increment when prompt, validation, or upstream generation parameters change.
    private static final String VERSION = "event-analysis-v1";
    private final AnalysisCostMapper repository;
    private final DetectionService detection;
    private final EventAnalysisService analysis;
    private final EventAnalysisPersistenceService persistence;
    private final LlmProperties config;
    private final ObjectMapper json;

    public AnalysisCostService(AnalysisCostMapper repository, DetectionService detection, EventAnalysisService analysis,
                               EventAnalysisPersistenceService persistence, LlmProperties config, ObjectMapper json) {
        this.repository = repository; this.detection = detection; this.analysis = analysis; this.persistence = persistence; this.config = config; this.json = json;
    }

    public Map<String, Object> analyze(long eventId, Long actor) {
        Map<String, Object> event = detection.event(eventId);
        String fingerprint = fingerprint(eventId, event);
        synchronized (LOCKS[(fingerprint.hashCode() & 0x7fffffff) % LOCKS.length]) {
            return analyzeSnapshot(eventId, actor, event, fingerprint);
        }
    }

    /** 复用缓存或执行模型研判，并保存研判记录。 */
    private Map<String, Object> analyzeSnapshot(long eventId, Long actor, Map<String, Object> event, String fingerprint) {
        // Only a successful MODEL result is reusable, and never in mock mode.
        List<Long> sources = config.mock() ? List.of() : repository.findSources(
            fingerprint, eventId, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1)));
        Map<String, Object> result;
        Long sourceId = sources.isEmpty() ? null : sources.get(0);
        int savedTokens = 0;
        if (sourceId != null) {
            result = new LinkedHashMap<>(persistence.get(sourceId));
            Integer estimated = repository.savedTokens(sourceId);
            savedTokens = estimated == null ? 0 : estimated;
            result.put("promptTokens", 0); result.put("completionTokens", 0); result.put("costMs", 0L);
        } else {
            result = analysis.analyzeEvent(eventId, event, (outcome, elapsed, response) -> {
                try { recordAttempt(eventId, outcome, elapsed, response); }
                catch (RuntimeException ex) { LOG.warn("事件研判调用统计写入失败，事件 ID={}", eventId); }
            });
        }
        if (sourceId == null && "MODEL".equals(result.get("source")) && Boolean.TRUE.equals(result.get("usageKnown"))) {
            savedTokens = ((Number) result.get("promptTokens")).intValue() + ((Number) result.get("completionTokens")).intValue();
        }
        if (!fingerprint.equals(fingerprint(eventId, detection.event(eventId)))) throw new IllegalStateException("事件证据已变化，请重新发起研判");
        return persistence.saveWithCache(eventId, actor, result, fingerprint, sourceId, savedTokens);
    }

    private void recordAttempt(long eventId, String outcome, long elapsed, LlmResult response) {
        repository.recordAttempt(
            eventId, Objects.toString(response == null ? config.model() : response.getModel(), ""), outcome, elapsed,
            response == null || !response.isUsageKnown() ? null : response.getPromptTokens(), response == null || !response.isUsageKnown() ? null : response.getCompletionTokens());
    }

    public String fingerprint(long eventId, Map<String, Object> event) {
        try {
            @SuppressWarnings("unchecked") List<Map<String, Object>> evidence = (List<Map<String, Object>>) event.get("evidence");
            List<String> facts = evidence.stream().map(e -> e.get("logId") + ":" + e.get("ruleCode")).sorted().toList();
            byte[] payload = json.writeValueAsBytes(List.of(VERSION, eventId, Objects.toString(config.baseUrl(), ""), Objects.toString(config.model(), ""), facts));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception ex) { throw new IllegalStateException("无法计算事件证据指纹", ex); }
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> requests = repository.requests();
        Map<String, Object> calls = repository.calls();
        long count = ((Number) requests.get("request_count")).longValue(), hits = ((Number) requests.get("cache_hits")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestCount", count); out.put("cacheHits", hits); out.put("cacheHitRate", count == 0 ? null : (double) hits / count);
        out.put("savedTokensEstimate", requests.get("saved_tokens")); out.put("callCount", calls.get("call_count"));
        out.put("promptTokens", calls.get("prompt_tokens")); out.put("completionTokens", calls.get("completion_tokens"));
        out.put("averageLatencyMs", calls.get("avg_ms")); out.put("unknownTokenCalls", calls.get("unknown_tokens"));
        List<Map<String, Object>> recent = repository.recentCalls();
        recent.forEach(row -> {
            for (String key : List.of("id", "eventId", "model", "outcome", "elapsedMs", "promptTokens", "completionTokens", "createdAt")) row.putIfAbsent(key, null);
            if (row.get("elapsedMs") == null) row.put("elapsedMs", 0L);
        });
        out.put("recentCalls", recent);
        return out;
    }
}
