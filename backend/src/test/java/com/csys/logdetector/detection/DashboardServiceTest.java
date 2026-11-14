package com.csys.logdetector.detection;

import com.csys.logdetector.detection.service.DashboardService;

import com.csys.logdetector.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DashboardServiceTest {
    JdbcTemplate db;
    DashboardService service;
    @BeforeEach void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE event(id BIGINT PRIMARY KEY, client_ip_hash VARCHAR(64), client_ip_mask VARCHAR(64), window_start TIMESTAMP, severity VARCHAR(16), risk_score DECIMAL(5,2), status VARCHAR(24))");
        db.execute("CREATE TABLE log_entry(id BIGINT PRIMARY KEY, batch_id BIGINT, line_no INT, log_time TIMESTAMP, raw_line_masked VARCHAR(100), parse_status VARCHAR(16))");
        db.execute("CREATE TABLE ai_analysis(id BIGINT PRIMARY KEY, event_id BIGINT, status VARCHAR(24))");
        try {
            var factory = new org.mybatis.spring.SqlSessionFactoryBean();
            factory.setDataSource(db.getDataSource());
            factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/DashboardMapper.xml"));
            var mapper = new org.mybatis.spring.SqlSessionTemplate(factory.getObject()).getMapper(com.csys.logdetector.detection.mapper.DashboardMapper.class);
            service = new DashboardService(mapper, Clock.fixed(Instant.parse("2025-01-02T04:37:00Z"), ZoneOffset.UTC));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    void event(long id, String hash, String mask, String time, String severity) {
        db.update("INSERT INTO event VALUES (?,?,?,?,?,90,'NEW')", id, hash, mask, Timestamp.valueOf(time), severity);
    }
    void line(long id, int batch, int number, String status) {
        db.update("INSERT INTO log_entry VALUES (?,?,?,?,'masked',?)", id, batch, number, Timestamp.valueOf("2025-01-02 12:00:00"), status);
    }
    @SuppressWarnings("unchecked")
    @Test void dashboardBucketsAndCountsRealData() {
        event(1,"a","1.2.3.***","2025-01-01 12:59:59","HIGH"); // outside first bucket
        event(2,"a","1.2.3.***","2025-01-01 13:00:00","CRITICAL");
        event(3,"b","1.2.3.***","2025-01-02 12:45:00","LOW");
        event(4,"a","1.2.3.***","2025-01-02 13:00:00","HIGH"); // future bucket
        line(1,1,1,"SUCCESS"); line(2,1,2,"FAILED");
        db.update("INSERT INTO ai_analysis VALUES (1, 2, 'SUCCESS'), (2, 2, 'RULE_FALLBACK'), (3, 3, 'SUCCESS')");
        Map<String,Object> data = service.dashboard();
        assertEquals(4L, data.get("eventCount")); assertEquals(3L, data.get("highSeverityEventCount"));
        assertEquals(1L, data.get("logCount")); assertEquals(3L, data.get("aiAnalysisCount"));
        var trend = (List<Map<String,Object>>) data.get("trend24h");
        assertEquals(24, trend.size()); assertEquals(1L, trend.get(0).get("eventCount"));
        assertEquals(1L, trend.get(23).get("eventCount"));
        var sources = (List<Map<String,Object>>) data.get("topSources");
        assertEquals(2, sources.size()); assertEquals(3L, sources.get(0).get("eventCount"));
        assertEquals(4L, ((List<Map<String,Object>>) data.get("latestEvents")).get(0).get("id"));
    }
    @SuppressWarnings("unchecked")
    @Test void contextUsesAdjacentParsedLinesAndNeverLeavesBatch() {
        line(1,1,1,"SUCCESS"); line(2,1,4,"SUCCESS"); line(3,1,5,"FAILED"); line(4,1,8,"SUCCESS"); line(5,2,3,"SUCCESS");
        var result = service.context(2,1,1);
        assertEquals(1L, result.get("batchId"));
        var lines = (List<Map<String,Object>>) result.get("lines");
        assertEquals(List.of(1L,2L,4L), lines.stream().map(l -> l.get("logId")).toList());
        assertEquals(true, lines.get(1).get("isCurrent")); assertEquals("masked", lines.get(1).get("rawLineMasked"));
        assertFalse(lines.get(1).containsKey("clientIpHash"));
        assertEquals(1, ((List<?>) service.context(2,0,0).get("lines")).size());
        assertThrows(BizException.class, () -> service.context(999,1,1));
        assertThrows(BizException.class, () -> service.context(2,21,0));
    }
    @Test void emptyDashboardReturnsZeroAndAllBuckets() {
        var data = service.dashboard();
        assertEquals(0L, data.get("eventCount")); assertEquals(0L, data.get("aiAnalysisCount"));
        assertEquals(24, ((List<?>) data.get("trend24h")).size());
        assertEquals(List.of(), data.get("latestEvents"));
    }
}
