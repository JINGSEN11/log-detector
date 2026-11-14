package com.csys.logdetector.log.sanitizer;

import com.csys.logdetector.log.service.LogImportService;
import com.csys.logdetector.log.mapper.LogImportMapper;
import com.csys.logdetector.log.parser.AccessLogParser;
import com.csys.logdetector.log.parser.JsonLineLogParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogImportSecurityTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void jsonAndJsonlPersistAndPreviewOnlySanitizedValues() {
        for (String extension : List.of("json", "jsonl")) {
            LogImportMapper mapper = mock(LogImportMapper.class);
            when(mapper.countBySha256(anyString())).thenReturn(0);
            when(mapper.insertBatch(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(42L);
            IpPseudonymizer ip = mock(IpPseudonymizer.class);
            when(ip.pseudonymize(anyString())).thenReturn("ip-hmac");
            LogImportService service = new LogImportService(new AccessLogParser(),
                    new JsonLineLogParser(new ObjectMapper()), new LogSanitizer(), ip, mapper);
            String line = "{\"ip\":\"2001:db8::1\",\"timestamp\":\"2024-01-01T00:00:00Z\",\"method\":\"GET\",\"path\":\"/x?token=pathsecret\",\"status\":200,\"referer\":\"https://x/?password=refsecret\",\"user_agent\":\"ip 2001:db8::2\",\"nested\":{\"password\":\"jsonsecret\",\"cookie\":\"sessionsecret\"}}";
            var result = service.importGenerated("input." + extension, line.getBytes(StandardCharsets.UTF_8), "TEST");
            ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
            verify(mapper).insertEntries(captor.capture());
            Object[] persistedRow = (Object[]) captor.getValue().get(0);
            assertEquals(java.time.LocalDateTime.of(2024, 1, 1, 8, 0), persistedRow[2]);
            verify(mapper).insertBatch(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
            String persisted = Arrays.deepToString(captor.getValue().toArray());
            String preview = result.preview().toString();
            for (String secret : List.of("2001:db8::1", "2001:db8::2", "pathsecret", "refsecret", "jsonsecret", "sessionsecret")) {
                assertFalse(persisted.contains(secret), persisted);
                assertFalse(preview.contains(secret), preview);
            }
            assertTrue(persisted.contains("[REDACTED]"));
            assertEquals("***", result.preview().get(0).maskedIp());
        }
    }
}
