package com.csys.logdetector.log.service;

import com.csys.logdetector.log.mapper.LogImportMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class LogImportPaginationMapperTest {
    @Test
    void selectsMaskedSuccessRowsWithStablePaginationAndUtcTimestamp() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:log_page;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS log_entry");
        jdbc.execute("CREATE TABLE log_entry (batch_id BIGINT, line_no INT, client_ip_hash VARCHAR, client_ip_mask VARCHAR, log_time TIMESTAMP, method VARCHAR, path VARCHAR, status_code INT, referer VARCHAR, user_agent VARCHAR, raw_line_masked VARCHAR, parse_status VARCHAR)");
        jdbc.update("INSERT INTO log_entry VALUES (42, 2, 'hash', 'mask', TIMESTAMP '2024-01-01 08:00:00', 'GET', '/', 200, '-', 'agent', '[REDACTED]', 'SUCCESS'), (42, 1, 'hash', 'mask', TIMESTAMP '2024-01-01 08:00:00', 'GET', '/', 200, '-', 'agent', 'bad', 'FAILED')");
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setMapperLocations(new ClassPathResource("mapper/log/LogImportMapper.xml"));
        SqlSessionFactory factory = bean.getObject();
        try (SqlSession session = factory.openSession()) {
            var mapper = session.getMapper(LogImportMapper.class);
            assertEquals(1, mapper.countSuccessfulEntries(42));
            var row = mapper.pageSuccessfulEntries(42, 1, 0).get(0);
            assertEquals(2, row.lineNo());
            assertEquals("2024-01-01T00:00:00Z", row.timestamp());
            assertEquals("[REDACTED]", row.rawLine());
            assertTrue(mapper.pageSuccessfulEntries(42, 1, 1).isEmpty());
        }
    }
}
