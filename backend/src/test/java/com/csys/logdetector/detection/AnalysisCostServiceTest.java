package com.csys.logdetector.detection;

import com.csys.logdetector.detection.service.DetectionService;
import com.csys.logdetector.detection.service.EventAnalysisService;
import com.csys.logdetector.detection.service.EventAnalysisPersistenceService;
import com.csys.logdetector.detection.service.AnalysisCostService;

import com.csys.logdetector.llm.client.LlmClient;
import com.csys.logdetector.llm.config.LlmProperties;
import com.csys.logdetector.llm.dto.LlmResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AnalysisCostServiceTest {
    JdbcTemplate db;
    DetectionService detection;
    LlmClient client;
    AnalysisCostService service;
    static final String VALID = "{\"summary\":\"s\",\"falsePositiveHint\":\"f\",\"confidence\":0.5,\"recommendations\":[\"r\"],\"attackChain\":[{\"stage\":\"s\",\"description\":\"d\",\"logIds\":[10]}]}";
    @BeforeEach void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE event(id BIGINT PRIMARY KEY)");
        db.execute("CREATE TABLE rule_hit(id BIGINT PRIMARY KEY, log_entry_id BIGINT)");
        db.execute("CREATE TABLE event_evidence(event_id BIGINT, rule_hit_id BIGINT)");
        db.execute("CREATE TABLE ai_analysis(id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id BIGINT, requested_by BIGINT, status VARCHAR(24), model VARCHAR(128), summary TEXT, attack_chain TEXT, recommendations TEXT, confidence DECIMAL(4,3), false_positive_notes TEXT, prompt_tokens INT, completion_tokens INT, cost_ms BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE ai_analysis_evidence(analysis_id BIGINT, log_entry_id BIGINT, citation_order INT, stage_no INT)");
        db.execute("CREATE TABLE analysis_cache_record(analysis_id BIGINT PRIMARY KEY, fingerprint VARCHAR(64), cache_source_id BIGINT, saved_tokens INT)");
        db.execute("CREATE TABLE analysis_model_attempt(id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id BIGINT, model VARCHAR(128), outcome VARCHAR(24), elapsed_ms BIGINT, prompt_tokens INT, completion_tokens INT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        db.update("INSERT INTO event VALUES (1)"); db.update("INSERT INTO rule_hit VALUES (1,10)"); db.update("INSERT INTO event_evidence VALUES (1,1)");
        detection = mock(DetectionService.class); client = mock(LlmClient.class);
        when(detection.event(1)).thenReturn(Map.of("evidence", List.of(Map.of("logId", 10L, "ruleCode", "R1"))));
        var config = new LlmProperties("https://example.invalid", "", "model", 5, false);
        var json = new ObjectMapper();
        service = new AnalysisCostService(EventAnalysisTestMapperFactory.create(db.getDataSource(), com.csys.logdetector.detection.mapper.AnalysisCostMapper.class, "AnalysisCostMapper.xml"), detection, new EventAnalysisService(detection, client, config, json), new EventAnalysisPersistenceService(EventAnalysisTestMapperFactory.create(db.getDataSource()), json), config, json);
    }
    @Test void cachesSuccessfulModelAndInvalidatesChangedEvidence() {
        when(client.chat(anyString())).thenReturn(new LlmResult(VALID, "model", 3, 4, 10));
        var first = service.analyze(1, 7L);
        var second = service.analyze(1, 8L);
        assertEquals("MISS", first.get("cacheStatus")); assertEquals("CACHED", second.get("cacheStatus"));
        assertEquals(first.get("id"), second.get("cacheSourceId")); assertEquals(0, second.get("promptTokens")); assertEquals(0, second.get("completionTokens"));
        var overview = service.dashboard();
        assertEquals(2L, overview.get("requestCount")); assertEquals(1L, overview.get("cacheHits"));
        assertEquals(7L, ((Number) overview.get("savedTokensEstimate")).longValue()); assertEquals(1L, ((Number) overview.get("callCount")).longValue());
        when(detection.event(1)).thenReturn(Map.of("evidence", List.of(Map.of("logId", 10L, "ruleCode", "R2"))));
        assertEquals("MISS", service.analyze(1, 7L).get("cacheStatus"));
        verify(client, times(2)).chat(anyString());
    }
    @Test void concurrentRequestsShareSingleModelCallInOneInstance() throws Exception {
        when(client.chat(anyString())).thenAnswer(invocation -> { Thread.sleep(100); return new LlmResult(VALID, "model", 3, 4, 10); });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Map<String,Object>> task = () -> { start.await(); return service.analyze(1, 7L); };
            var one = pool.submit(task); var two = pool.submit(task); start.countDown();
            assertEquals(Set.of("MISS", "CACHED"), Set.of(one.get().get("cacheStatus"), two.get().get("cacheStatus")));
            verify(client, times(1)).chat(anyString());
        } finally { pool.shutdownNow(); }
    }
    @Test void unknownUsageIsNotCountedAsZeroOrEstimated() {
        when(client.chat(anyString())).thenReturn(new LlmResult(VALID, "model", 0, 0, 10, false));
        service.analyze(1, 7L); service.analyze(1, 7L);
        var result = service.dashboard();
        assertEquals(1L, ((Number) result.get("unknownTokenCalls")).longValue());
        assertEquals(0L, ((Number) result.get("savedTokensEstimate")).longValue());
    }
    @Test void attemptRecordingFailureDoesNotLoseValidModelResult() {
        db.execute("DROP TABLE analysis_model_attempt");
        when(client.chat(anyString())).thenReturn(new LlmResult(VALID, "model", 3, 4, 10));
        assertEquals("MODEL", service.analyze(1, 7L).get("source"));
        assertEquals("CACHED", service.analyze(1, 7L).get("cacheStatus"));
        verify(client, times(1)).chat(anyString());
    }
    @Test void invalidResponseRetryIsCountedSeparately() {
        when(client.chat(anyString())).thenReturn(new LlmResult("{}", "model", 2, 1, 3), new LlmResult(VALID, "model", 3, 4, 5));
        assertEquals("MODEL", service.analyze(1, 7L).get("source"));
        var result = service.dashboard();
        assertEquals(2L, ((Number) result.get("callCount")).longValue());
        assertEquals(5L, ((Number) result.get("promptTokens")).longValue());
        assertEquals(5L, ((Number) result.get("completionTokens")).longValue());
        assertEquals(List.of("INVALID", "VALID"), db.queryForList("SELECT outcome FROM analysis_model_attempt ORDER BY id", String.class));
    }
    @Test void fingerprintIgnoresEvidenceOrderButNotEventOrModel() {
        var e1 = Map.<String,Object>of("evidence", List.of(Map.of("logId", 10L, "ruleCode", "R1"), Map.of("logId", 11L, "ruleCode", "R2")));
        var e2 = Map.<String,Object>of("evidence", List.of(Map.of("logId", 11L, "ruleCode", "R2"), Map.of("logId", 10L, "ruleCode", "R1")));
        assertEquals(service.fingerprint(1, e1), service.fingerprint(1, e2));
        assertNotEquals(service.fingerprint(1, e1), service.fingerprint(2, e1));
    }
    @Test void failedCallsAreRecordedAndFallbackIsNotCached() {
        when(client.chat(anyString())).thenThrow(new RuntimeException("secret"));
        assertEquals("RULE_FALLBACK", service.analyze(1, 7L).get("source"));
        assertEquals("MISS", service.analyze(1, 7L).get("cacheStatus"));
        verify(client, times(2)).chat(anyString());
        var result = service.dashboard();
        assertEquals(2L, ((Number) result.get("callCount")).longValue());
        assertEquals(2L, ((Number) result.get("unknownTokenCalls")).longValue());
        assertEquals(0L, result.get("cacheHits"));
    }
}
