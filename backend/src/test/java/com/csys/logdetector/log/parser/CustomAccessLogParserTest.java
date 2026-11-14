package com.csys.logdetector.log.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CustomAccessLogParserTest {
    private final LogParser parser = new CustomAccessLogParser();
    private final String valid = "2026-11-04T18:15:30+08:00\t203.0.113.9\tGET\t/x?q=1\t200\t123";

    @Test
    void parsesFieldsAndNormalizesTimezone() {
        var parsed = parser.parse(valid, 7).orElseThrow();
        assertEquals(7, parsed.lineNumber());
        assertEquals("203.0.113.9", parsed.sourceIp());
        assertEquals(Instant.parse("2026-11-04T10:15:30Z"), parsed.timestamp());
        assertEquals("GET", parsed.method());
        assertEquals("/x?q=1", parsed.path());
        assertEquals(200, parsed.status());
        assertEquals(123, parsed.bytes());
        assertEquals("", parsed.referer());
        assertEquals("", parsed.userAgent());
        assertEquals(valid, parsed.rawLine());
        assertTrue(parser.parse(valid.replace("2026-11-04T18:15:30+08:00", "2026-11-04T10:15:30Z"), 1).isPresent());
        assertTrue(parser.parse(valid.replace("200\t123", "100\t0"), 1).isPresent());
        assertTrue(parser.parse(valid.replace("200\t123", "599\t9223372036854775807"), 1).isPresent());
    }

    @Test
    void rejectsMalformedFields() {
        String[] bad = {
                valid.replace('\t', ' '), valid + "\textra", valid + "\t", valid.replace("\t123", ""),
                valid.replace("+08:00", ""), valid.replace("+08:00", "+25:00"),
                valid.replace("2026-11-04", "2026-02-30"),
                valid.replace("203.0.113.9", "256.0.113.9"), valid.replace("203.0.113.9", "::1"),
                valid.replace("203.0.113.9", "01.0.0.1"), valid.replace("GET", "get"),
                valid.replace("GET", "A".repeat(17)), valid.replace("2026-11-04", "0001-11-04"),
                valid.replace("2026-11-04T18:15:30+08:00", "9999-12-31T23:59:59Z"),
                valid.replace("/x?q=1", ""), valid.replace("/x?q=1", "/a b"),
                valid.replace("\t200\t", "\t099\t"), valid.replace("\t200\t", "\t600\t"),
                valid.replace("\t123", "\t-1"), valid.replace("\t123", "\t+1"),
                valid.replace("\t123", "\t9223372036854775808")
        };
        assertTrue(parser.parse(null, 1).isEmpty());
        for (String line : bad) assertTrue(parser.parse(line, 1).isEmpty(), line);
    }

    @Test
    void enforcesLengthLimit() {
        String prefix = "2026-11-04T10:15:30Z\t1.2.3.4\tGET\t/";
        String suffix = "\t200\t0";
        String boundary = prefix + "a".repeat(8192 - prefix.length() - suffix.length()) + suffix;
        assertTrue(parser.parse(boundary, 1).isPresent());
        assertTrue(parser.parse(boundary + "0", 1).isEmpty());
    }

    @Test
    void exposesFormatIds() {
        assertEquals("custom", parser.formatId());
        assertEquals("access", new AccessLogParser().formatId());
        assertEquals("jsonl", new JsonLineLogParser(new ObjectMapper()).formatId());
    }
}
