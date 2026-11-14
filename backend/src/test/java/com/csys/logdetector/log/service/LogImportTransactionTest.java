package com.csys.logdetector.log.service;

import com.csys.logdetector.log.mapper.LogImportMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class LogImportTransactionTest {
    @Test
    void generatedKeyAndBatchInsertsRollBackTogetherOnFailure() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:log_tx;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS import_batch");
        jdbc.execute("CREATE TABLE import_batch (id BIGINT AUTO_INCREMENT PRIMARY KEY, created_by BIGINT, source_type VARCHAR, file_name VARCHAR, file_sha256 VARCHAR, scenario_code VARCHAR, status VARCHAR, total_lines INT, parsed_lines INT, skipped_lines INT, completed_at TIMESTAMP)");
        jdbc.execute("DROP TABLE IF EXISTS log_entry");
        jdbc.execute("CREATE TABLE log_entry (batch_id BIGINT, line_no INT, log_time TIMESTAMP, client_ip_hash VARCHAR, client_ip_mask VARCHAR, method VARCHAR, path VARCHAR, query_string VARCHAR, status_code INT, response_bytes BIGINT, referer VARCHAR, user_agent VARCHAR, raw_line_masked VARCHAR, parse_status VARCHAR)");
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/log/LogImportMapper.xml"), new ClassPathResource("mapper/log/ScenarioReplayMapper.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(LogImportMapper.class);
        jdbc.execute("DROP TABLE IF EXISTS scenario_replay");
        jdbc.execute("CREATE TABLE scenario_replay (batch_id BIGINT, algorithm_version INT, seed BIGINT, base_time BIGINT, line_count INT)");
        var replay = new SqlSessionTemplate(factory.getObject()).getMapper(com.csys.logdetector.log.mapper.ScenarioReplayMapper.class);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            Long id = mapper.insertBatch(null, "GENERATOR", null, "hash", "test", "SUCCESS", 1, 1, 0);
            assertNotNull(id);
            assertTrue(mapper.batchExists(id));
            assertNull(replay.find(id).version());
            replay.insert(id, 1, 42L, 1700000000L, 180);
            assertEquals(42L, replay.find(id).seed());
            Object[] row = {id, 1, java.time.LocalDateTime.of(2024, 1, 1, 8, 0), "hash", "mask", "GET", "/", "", 200, 10L, "-", "agent", "masked", "SUCCESS"};
            mapper.insertEntries(java.util.Collections.nCopies(501, row));
            assertEquals(501, mapper.countSuccessfulEntries(id));
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM import_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM log_entry", Integer.class));
    }
}
