package com.csys.logdetector.detection;

import com.csys.logdetector.detection.service.DetectionService;
import com.csys.logdetector.detection.service.EventAnalysisPersistenceService;
import com.csys.logdetector.detection.service.EventWorkflowService;

import com.csys.logdetector.common.BizException;
import com.csys.logdetector.log.sanitizer.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventWorkflowServiceTest {
    JdbcTemplate db;
    DetectionService detection;
    EventWorkflowService service;
    @BeforeEach void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE event(id BIGINT PRIMARY KEY,status VARCHAR(24),client_ip_mask VARCHAR(64),window_start TIMESTAMP,window_end TIMESTAMP,severity VARCHAR(16),risk_score DECIMAL(5,2))");
        db.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY,username VARCHAR(64))");
        db.execute("CREATE TABLE event_timeline(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id BIGINT,actor_user_id BIGINT,action VARCHAR(32),old_status VARCHAR(24),new_status VARCHAR(24),note TEXT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE audit_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_user_id BIGINT,action VARCHAR(32),target_type VARCHAR(32),target_id VARCHAR(64),trace_id VARCHAR(64))");
        db.execute("CREATE TABLE ai_analysis(id BIGINT PRIMARY KEY, event_id BIGINT, requested_by BIGINT, status VARCHAR(24), model VARCHAR(128), summary TEXT, attack_chain TEXT, recommendations TEXT, confidence DECIMAL(4,3), false_positive_notes TEXT, prompt_tokens INT, completion_tokens INT, cost_ms BIGINT, created_at TIMESTAMP)");
        db.execute("CREATE TABLE analysis_cache_record(analysis_id BIGINT PRIMARY KEY, fingerprint VARCHAR(64), cache_source_id BIGINT, saved_tokens INT)");
        db.update("INSERT INTO event VALUES (1,'NEW','1.2.3.***','2025-01-02 12:00:00','2025-01-02 12:10:00','HIGH',80)");
        db.update("INSERT INTO app_user VALUES (2,'analyst')");
        detection = mock(DetectionService.class);
        Map<String,Object> event = new HashMap<>();
        event.put("id", 1L); event.put("clientIpMask", "1.2.3.***"); event.put("status", "NEW");
        event.put("evidence", List.of(Map.of("logId", 12L, "ruleCode", "R1", "matchedField", "path", "evidenceSnippet", "<script>|", "rawLineMasked", "token=secret")));
        when(detection.event(1)).thenReturn(event);
        service = new EventWorkflowService(EventAnalysisTestMapperFactory.create(db.getDataSource(), com.csys.logdetector.detection.mapper.EventWorkflowMapper.class, "EventWorkflowMapper.xml"), detection, new LogSanitizer(), new EventAnalysisPersistenceService(EventAnalysisTestMapperFactory.create(db.getDataSource()), new ObjectMapper()));
    }
    @Test void changesAllStatusesWithSanitizedNotesAndAudit() {
        for (String status : List.of("INVESTIGATING", "CONFIRMED", "FALSE_POSITIVE", "CLOSED", "NEW")) service.updateStatus(1, status, "token=secret", 2);
        assertEquals("NEW", db.queryForObject("SELECT status FROM event WHERE id=1", String.class));
        var timeline = service.timeline(1);
        assertEquals(5, timeline.size()); assertEquals("NEW", timeline.get(0).get("oldStatus"));
        assertEquals("analyst", timeline.get(0).get("actor")); assertFalse(timeline.get(0).get("note").toString().contains("secret"));
        assertEquals(5, db.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class));
        assertThrows(BizException.class, () -> service.updateStatus(1,"INVALID","x",2));
        assertThrows(BizException.class, () -> service.updateStatus(1,null,"x",2));
        assertThrows(BizException.class, () -> service.updateStatus(1,"NEW"," ",2));
        assertThrows(BizException.class, () -> service.updateStatus(999,"NEW","x",2));
        assertThrows(BizException.class, () -> service.timeline(999));
    }
    @Test void auditIncludesRequestTrace() {
        org.slf4j.MDC.put("traceId", "test-trace");
        try {
            service.updateStatus(1, "CLOSED", "核查完成", 2);
            assertEquals("test-trace", db.queryForObject("SELECT trace_id FROM audit_log", String.class));
        } finally { org.slf4j.MDC.remove("traceId"); }
    }
    @Test void csvEscapesFormulasEvenAfterWhitespaceAndQuotes() {
        for (String v : List.of("=1+2", "+1", "-1", "@test", " \t=1", "\ttext")) assertTrue(EventWorkflowService.csvCell(v).startsWith("\"'"), v);
        assertEquals("\"a\"\"b\"", EventWorkflowService.csvCell("a\"b"));
        String csv = service.csv(2); assertTrue(csv.contains("1.2.3.***")); assertTrue(csv.contains("HIGH"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EVENT_CSV_EXPORT'", Integer.class));
    }
    private void analysis(long id, long eventId, String status, String chain, String recommendations, String time) {
        db.update("INSERT INTO ai_analysis(id,event_id,status,attack_chain,recommendations,created_at) VALUES (?,?,?,?,?,?)",
                id, eventId, status, chain, recommendations, java.sql.Timestamp.valueOf(time));
    }
    @Test void reportUsesLatestAnalysisAndEscapesItsContent() {
        analysis(1, 1, "SUCCESS", "[]", "[\"过期建议\"]", "2025-01-02 12:00:00");
        analysis(2, 1, "SUCCESS", "[{\"stage\":\"探测\",\"description\":\"<script>| token=secret\",\"logIds\":[12,13]}]", "[\"检查 **日志**\"]", "2025-01-02 12:00:00");
        analysis(3, 2, "RULE_FALLBACK", "[]", "[\"其他事件\"]", "2025-01-03 12:00:00");
        String report = service.markdown(1, 2);
        assertTrue(report.contains("研判来源：模型研判")); assertTrue(report.contains("2025-01-02T12:00"));
        assertTrue(report.contains("阶段：探测")); assertTrue(report.contains("引用日志 ID：12, 13"));
        assertTrue(report.contains("检查 \\*\\*日志\\*\\*")); assertTrue(report.contains("&lt;script&gt;\\|"));
        assertFalse(report.contains("token=secret")); assertFalse(report.contains("过期建议")); assertFalse(report.contains("其他事件"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EVENT_MD_EXPORT'", Integer.class));
    }
    @Test void reportLabelsFallbackAndEmptyFields() {
        analysis(1, 1, "RULE_FALLBACK", "[]", "[]", "2025-01-02 12:00:00");
        String report = service.markdown(1, 2);
        assertTrue(report.contains("研判来源：规则降级"));
        assertTrue(report.contains("暂无攻击链")); assertTrue(report.contains("暂无处置建议"));
    }
    @Test void reportEscapesUntrustedTextAndStatesAnalysisUnavailable() {
        String report = service.markdown(1, 2);
        assertTrue(report.contains("## 攻击链")); assertTrue(report.contains("## 处置建议"));
        assertTrue(report.contains("暂无研判记录")); assertTrue(report.contains("## 证据表")); assertTrue(report.contains("## 规则统计"));
        assertFalse(report.contains("<script>")); assertFalse(report.contains("token=secret")); assertTrue(report.contains("R1"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EVENT_MD_EXPORT'", Integer.class));
    }
}
