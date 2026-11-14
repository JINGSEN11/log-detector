package com.csys.logdetector.log.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.csys.logdetector.common.BizException;
import com.csys.logdetector.log.dto.LogImportResult;
import com.csys.logdetector.log.mapper.ScenarioReplayMapper;
import java.security.MessageDigest;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.HexFormat;
import com.csys.logdetector.log.parser.AccessLogParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LogScenarioServiceTest {
    private final LogImportService importer = mock(LogImportService.class);
    private final ScenarioReplayMapper replayMapper = mock(ScenarioReplayMapper.class);
    private final LogScenarioService service = new LogScenarioService(importer, replayMapper);
    private final AccessLogParser parser = new AccessLogParser();

    @Test
    void allScenariosGenerateValidTestNetLogsAtBothLimits() {
        var scenarios = service.list();
        assertEquals(List.of("combined", "web-attack", "login-bruteforce", "scanner", "prompt-injection"),
                scenarios.stream().map(LogScenarioService.ScenarioInfo::id).toList());
        for (var scenario : scenarios) for (int count : List.of(180, 5000)) {
            var preview = service.preview(scenario.id(), 42L, 1700000000L, count);
            assertEquals(scenario.name(), preview.name());
            assertEquals(count, preview.lines().size());
            for (String line : preview.lines()) {
                var parsed = parser.parse(line, 1).orElseThrow();
                assertTrue(parsed.sourceIp().matches("(?:192\\.0\\.2|198\\.51\\.100|203\\.0\\.113)\\.(?:[1-9]|[1-9][0-9]|1[0-9][0-9]|200)"), parsed.sourceIp());
            }
        }
    }

    @Test
    void previewParametersReproduceExactImportedBytes() {
        var preview = service.preview("scanner");
        LogImportResult result = new LogImportResult(1L, "hash", "SUCCESS", 200, 200, 0, List.of());
        when(importer.importGenerated(eq("scanner.log"), any(byte[].class), eq("scanner"))).thenReturn(result);
        assertEquals(preview, service.preview("scanner", preview.seed(), preview.baseTime(), preview.count()));
        assertSame(result, service.importScenario("scanner", preview.seed(), preview.baseTime(), preview.count()));
        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(importer).importGenerated(eq("scanner.log"), content.capture(), eq("scanner"));
        assertEquals(String.join("\n", preview.lines()), new String(content.getValue(), StandardCharsets.UTF_8));
        verify(replayMapper).insert(1L, 1, preview.seed(), preview.baseTime(), preview.count());
    }

    @Test
    void rejectsInvalidParametersAndUnknownScenarios() {
        assertEquals(404, assertThrows(BizException.class, () -> service.preview("unknown")).getCode());
        assertEquals(404, assertThrows(BizException.class, () -> service.importScenario("unknown")).getCode());
        assertThrows(BizException.class, () -> service.preview("combined", 1L, null, 200));
        assertThrows(BizException.class, () -> service.preview("combined", 1L, 1700000000L, 179));
        assertThrows(BizException.class, () -> service.preview("combined", 1L, 1700000000L, 5001));
        assertThrows(BizException.class, () -> service.preview("combined", 1L, Long.MAX_VALUE, 200));
        verifyNoInteractions(importer);
    }

    @Test
    void metadataFailureRollsBackImportTransaction() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(importer.importGenerated(anyString(), any(byte[].class), anyString()))
                .thenReturn(new LogImportResult(1L, "hash", "SUCCESS", 200, 200, 0, List.of()));
        doThrow(new IllegalStateException("metadata failure")).when(replayMapper).insert(anyLong(), anyInt(), anyLong(), anyLong(), anyInt());
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        LogScenarioService proxy = (LogScenarioService) factory.getProxy();
        assertThrows(IllegalStateException.class, () -> proxy.importScenario("combined", 1L, 1700000000L, 200));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void replaysPersistedBatchAndChecksVersionAndDigest() throws Exception {
        var preview = service.preview("combined", 42L, 1700000000L, 180);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", preview.lines()).getBytes(StandardCharsets.UTF_8)));
        when(replayMapper.find(1)).thenReturn(new ScenarioReplayMapper.BatchReplay("combined", digest, 1, 42L, 1700000000L, 180));
        assertEquals(preview, service.replay(1));
        when(replayMapper.find(2)).thenReturn(new ScenarioReplayMapper.BatchReplay("combined", digest, 2, 42L, 1700000000L, 180));
        when(replayMapper.find(3)).thenReturn(new ScenarioReplayMapper.BatchReplay("combined", "wrong", 1, 42L, 1700000000L, 180));
        when(replayMapper.find(4)).thenReturn(new ScenarioReplayMapper.BatchReplay(null, digest, null, null, null, null));
        for (long id : List.of(2L, 3L, 4L)) assertEquals(409, assertThrows(BizException.class, () -> service.replay(id)).getCode());
        assertEquals(404, assertThrows(BizException.class, () -> service.replay(5)).getCode());
    }

    @Test
    void combinedContainsAttackAndBehaviorSamples() {
        String lines = String.join("\n", service.preview("combined", 1L, 1700000000L, 180).lines());
        for (String marker : List.of("UNION%20SELECT", "%3Cscript%3E", "/../../etc/passwd", "Nuclei",
                "%3Bid", "127.0.0.1", "/.env", "${jndi:ldap", "ignore previous instructions")) {
            assertTrue(lines.contains(marker), marker);
        }
        assertEquals(20, lines.lines().filter(line -> line.contains("POST /login") && line.contains(" 401 ")).count());
        assertEquals(20, lines.lines().filter(line -> line.contains("/missing-") && line.contains(" 404 ")).count());
    }
}
