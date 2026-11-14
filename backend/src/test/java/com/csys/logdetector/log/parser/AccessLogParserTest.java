package com.csys.logdetector.log.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccessLogParserTest {

    private final AccessLogParser parser = new AccessLogParser();

    @Test
    void parsesCombinedLog() {
        var parsed = parser.parse("203.0.113.9 - - [04/Nov/2026:10:15:30 +0000] \"GET /x?q=1 HTTP/1.1\" 404 123 \"https://example.test/\" \"Mozilla/5.0\"", 7);
        assertTrue(parsed.isPresent());
        assertEquals("203.0.113.9", parsed.get().sourceIp());
        assertEquals("GET", parsed.get().method());
        assertEquals("/x?q=1", parsed.get().path());
        assertEquals(404, parsed.get().status());
        assertEquals(123, parsed.get().bytes());
        assertEquals("Mozilla/5.0", parsed.get().userAgent());
        assertEquals(7, parsed.get().lineNumber());
    }

    @Test
    void parsesCommonLogWithoutOptionalFields() {
        var parsed = parser.parse("198.51.100.1 - - [04/Nov/2026:10:15:30 +0000] \"GET / HTTP/1.1\" 200 10", 1);
        assertTrue(parsed.isPresent());
        assertEquals("", parsed.get().referer());
        assertEquals("", parsed.get().userAgent());
    }

    @Test
    void acceptsEscapedCombinedFieldsAndRejectsMalformedSuffixes() {
        String base = "203.0.113.9 - - [04/Nov/2026:10:15:30 +0000] \"GET / HTTP/1.1\" 200 -";
        assertTrue(parser.parse(base + " \"-\" \"agent \\\"quoted\\\"\"", 1).isPresent());
        assertTrue(parser.parse(base + " garbage", 1).isEmpty());
        assertTrue(parser.parse(base.replace("200 -", "999 -"), 1).isEmpty());
        assertTrue(parser.parse(base.replace("04/Nov/2026", "bad"), 1).isEmpty());
    }

    @Test
    void skipsMalformedAndOversizedLines() {
        assertTrue(parser.parse("not an access log", 1).isEmpty());
        assertTrue(parser.parse("x".repeat(8193), 2).isEmpty());
    }
}
