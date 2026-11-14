package com.csys.logdetector.detection;

import com.csys.logdetector.detection.service.EventAnalysisPersistenceService;

import com.csys.logdetector.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** H2 MySQL-mode coverage; production MySQL remains a separate integration check. */
class EventAnalysisPersistenceServiceTest {
    private JdbcTemplate db;
    private EventAnalysisPersistenceService service;
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE event(id BIGINT PRIMARY KEY)");
        db.execute("CREATE TABLE log_entry(id BIGINT PRIMARY KEY)");
        db.execute("CREATE TABLE rule_hit(id BIGINT PRIMARY KEY, log_entry_id BIGINT NOT NULL)");
        db.execute("CREATE TABLE event_evidence(event_id BIGINT NOT NULL, rule_hit_id BIGINT NOT NULL)");
        db.execute("CREATE TABLE ai_analysis(id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id BIGINT NOT NULL REFERENCES event(id), requested_by BIGINT, status VARCHAR(24), model VARCHAR(128), summary TEXT, attack_chain TEXT, recommendations TEXT, confidence DECIMAL(4,3), false_positive_notes TEXT, prompt_tokens INT, completion_tokens INT, cost_ms BIGINT, created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3))");
        db.execute("CREATE TABLE analysis_cache_record(analysis_id BIGINT PRIMARY KEY, fingerprint VARCHAR(64), cache_source_id BIGINT, saved_tokens INT)");
        db.execute("CREATE TABLE ai_analysis_evidence(analysis_id BIGINT NOT NULL REFERENCES ai_analysis(id), log_entry_id BIGINT NOT NULL REFERENCES log_entry(id), citation_order INT, stage_no INT, PRIMARY KEY(analysis_id, log_entry_id))");
        db.update("INSERT INTO event VALUES (1), (2)");
        db.update("INSERT INTO log_entry VALUES (10), (11), (12)");
        db.update("INSERT INTO rule_hit VALUES (1, 10), (2, 11), (3, 12)");
        db.update("INSERT INTO event_evidence VALUES (1, 1), (1, 2), (2, 3)");
        service = new EventAnalysisPersistenceService(EventAnalysisTestMapperFactory.create(ds), new ObjectMapper());
    }

    private Map<String,Object> result(String source, List<?> chain) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("source", source); value.put("model", source.equals("MODEL") ? "test-model" : null);
        value.put("summary", "摘要"); value.put("attackChain", chain); value.put("recommendations", List.of("检查日志"));
        value.put("confidence", source.equals("MODEL") ? 0.75 : null); value.put("falsePositiveHint", "人工确认");
        value.put("promptTokens", 3); value.put("completionTokens", 4); value.put("costMs", 5L);
        return value;
    }
    private Map<String,Object> save(long event, Map<String,Object> result) {
        return tx.execute(s -> service.save(event, 7L, result));
    }

    @Test void savesAndReadsHistoryAndDeduplicatesEvidence() {
        var chain = List.of(Map.of("stage", "一", "description", "描述", "logIds", List.of(10, 11)),
                            Map.of("stage", "二", "description", "描述", "logIds", List.of(10)));
        Map<String,Object> first = save(1, result("MODEL", chain));
        assertEquals("SUCCESS", first.get("status")); assertEquals("MODEL", first.get("source"));
        assertEquals(chain, first.get("attackChain"));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM ai_analysis_evidence", Integer.class));
        assertEquals(1, db.queryForObject("SELECT stage_no FROM ai_analysis_evidence WHERE log_entry_id=10", Integer.class));
        Map<String,Object> second = save(1, result("RULE_FALLBACK", List.of()));
        assertEquals("RULE_FALLBACK", second.get("status")); assertNull(second.get("model"));
        assertEquals(second.get("id"), service.latest(1).get("id"));
        Map<String,Object> page = service.list(1, 1, 1);
        assertEquals(2L, page.get("total"));
        assertEquals(second.get("id"), ((Map<?,?>)((List<?>)page.get("records")).get(0)).get("id"));
        assertEquals(first.get("id"), ((Map<?,?>)((List<?>)service.list(1, 2, 1).get("records")).get(0)).get("id"));
        assertNull(service.latest(2));
        assertEquals(0L, service.list(2, 1, 10).get("total"));
        assertEquals(404, assertThrows(BizException.class, () -> service.latest(999)).getCode());
    }

    @Test void rollsBackRecordIfEvidenceInsertFails() {
        var chain = List.of(Map.of("stage", "一", "description", "描述", "logIds", List.of(10L, 12L)));
        assertThrows(RuntimeException.class, () -> save(1, result("MODEL", chain)));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ai_analysis", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ai_analysis_evidence", Integer.class));
    }
}
