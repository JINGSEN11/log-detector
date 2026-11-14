package com.csys.logdetector.log.service;

import com.csys.logdetector.common.BizException;
import com.csys.logdetector.log.mapper.LogImportMapper;
import com.csys.logdetector.log.parser.AccessLogParser;
import com.csys.logdetector.log.parser.JsonLineLogParser;
import com.csys.logdetector.log.sanitizer.IpPseudonymizer;
import com.csys.logdetector.log.sanitizer.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogImportBoundaryTest {
    private static final String VALID = "203.0.113.1 - - [04/Nov/2026:10:15:30 +0000] \"GET / HTTP/1.1\" 200 1";
    private final LogImportMapper mapper = mock(LogImportMapper.class);
    private final IpPseudonymizer ip = mock(IpPseudonymizer.class);
    private final LogImportService service = new LogImportService(new AccessLogParser(),
            new JsonLineLogParser(new ObjectMapper()), new LogSanitizer(), ip, mapper);

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    @Test
    void rejectsInvalidUploadsBeforePersistence() {
        assertThrows(BizException.class, () -> service.importFile(file("x.log", new byte[0])));
        assertThrows(BizException.class, () -> service.importFile(file("x.exe", new byte[]{1})));
        assertThrows(BizException.class, () -> service.importFile(file("x.log", new byte[10 * 1024 * 1024 + 1])));
        assertThrows(BizException.class, () -> service.importFile(file("x.log", new byte[]{65, 0})));
        assertThrows(BizException.class, () -> service.importFile(file("x.log", new byte[]{(byte) 0xc3, 0x28})));
        verify(mapper, never()).insertBatch(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void generatedImportRecordsAuthenticatedActor() {
        var user = new com.csys.logdetector.security.LoginUser(42L, "analyst", "ANALYST");
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
        try {
            when(mapper.insertBatch(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(1L);
            when(ip.pseudonymize(anyString())).thenReturn("token");
            service.importGenerated("x.log", VALID.getBytes(StandardCharsets.UTF_8), "TEST");
            verify(mapper).insertAudit(eq(42L), eq(1L), eq("GENERATOR"), eq(1), eq(0));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void countsBlankMalformedAndOverlongLinesAndAcceptsExactLimit() {
        when(mapper.insertBatch(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(1L);
        when(ip.pseudonymize(anyString())).thenReturn("token");
        String exact = VALID + " ".repeat(8192 - VALID.length());
        String content = "﻿" + exact + "\n" + VALID + " ".repeat(8193 - VALID.length()) + "\n\ninvalid\n";
        var result = service.importGenerated("x.log", content.getBytes(StandardCharsets.UTF_8), "TEST");
        assertEquals(4, result.totalLines());
        assertEquals(1, result.parsedLines());
        assertEquals(3, result.skippedLines());
        assertEquals("PARTIAL", result.status());
    }

    @Test
    void sanitizesEveryPersistedFieldBeforeBatchInsert() {
        when(mapper.insertBatch(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(1L);
        when(ip.pseudonymize(anyString())).thenReturn("token");
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature";
        String line = "203.0.113.9 - - [04/Nov/2026:10:15:30 +0000] \"GET /?%2570assword%253Dhunter2 HTTP/1.1\" 200 1 \"-\" \"" + jwt + "\"";
        service.importGenerated("x.log", line.getBytes(StandardCharsets.UTF_8), "TEST");
        var rows = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(mapper).insertEntries(rows.capture());
        Object[] row = (Object[]) rows.getValue().get(0);
        String persisted = java.util.Arrays.toString(row);
        assertFalse(persisted.contains("hunter2"), persisted);
        assertFalse(persisted.contains(jwt), persisted);
        assertFalse(persisted.contains("203.0.113.9"), persisted);
    }

    @Test
    void rejectsDuplicateAndAllInvalidLines() {
        when(mapper.countBySha256(anyString())).thenReturn(1);
        BizException duplicate = assertThrows(BizException.class, () -> service.importGenerated("x.log", VALID.getBytes(StandardCharsets.UTF_8), "TEST"));
        assertEquals(409, duplicate.getCode());
        when(mapper.countBySha256(anyString())).thenReturn(0);
        assertThrows(BizException.class, () -> service.importGenerated("x.log", "invalid\n".getBytes(StandardCharsets.UTF_8), "TEST"));
        verify(mapper, never()).insertBatch(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void customFormatIsExplicitAndRawContentIsConservativelyRedacted() {
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new com.csys.logdetector.security.LoginUser(42L, "analyst", "ANALYST"), null));
        try {
            when(mapper.insertBatch(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(7L);
            when(ip.pseudonymize(anyString())).thenReturn("token");
            String valid = "2026-11-10T10:00:00+08:00\t192.0.2.1\tGET\t/search?payload={\"password\":\"demo\"}\t200\t128";
            var input = file("custom.log", (valid + "\ninvalid\n").getBytes(StandardCharsets.UTF_8));
            var result = service.importFile(input, "custom");
            assertEquals(1, result.parsedLines()); assertEquals(1, result.skippedLines()); assertEquals("PARTIAL", result.status());
            var captor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
            verify(mapper).insertEntries(captor.capture());
            Object[] row = (Object[]) captor.getValue().get(0);
            assertEquals("/search", row[6]); assertEquals("[REDACTED_CUSTOM_QUERY]", row[7]);
            assertEquals("[REDACTED_CUSTOM_LOG]", row[12]);
            assertThrows(BizException.class, () -> service.importFile(input, "unknown"));
            assertThrows(BizException.class, () -> service.importFile(input, "auto"));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    @Test
    void enforcesPhysicalLineCountBoundary() {
        when(mapper.insertBatch(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(1L);
        when(ip.pseudonymize(anyString())).thenReturn("token");
        String lines = VALID + "\n" + "bad\n".repeat(29999);
        var result = service.importGenerated("x.log", lines.getBytes(StandardCharsets.UTF_8), "TEST");
        assertEquals(30000, result.totalLines());
        assertEquals(29999, result.skippedLines());
        assertThrows(BizException.class, () -> service.importGenerated("x.log", (lines + "bad").getBytes(StandardCharsets.UTF_8), "TEST"));
    }
}
