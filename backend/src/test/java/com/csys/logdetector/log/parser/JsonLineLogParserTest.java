package com.csys.logdetector.log.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonLineLogParserTest {
    private final JsonLineLogParser parser = new JsonLineLogParser(new ObjectMapper());
    private final String valid = "{\"remote_addr\":\"203.0.113.1\",\"@timestamp\":\"2026-11-04T10:15:30Z\",\"request_method\":\"GET\",\"request_uri\":\"/x\",\"status_code\":200}";

    @Test
    void acceptsAliasesAndLengthBoundary() {
        assertTrue(parser.parse(valid + " ".repeat(8192 - valid.length()), 1).isPresent());
        assertTrue(parser.parse(valid + " ".repeat(8193 - valid.length()), 1).isEmpty());
    }

    @Test
    void rejectsMalformedAndMultipleJsonValues() {
        for (String line : new String[]{"null", "[]", "{", valid + " {}", valid.replace("200", "999"), valid.replace("2026-11-04T10:15:30Z", "bad")}) {
            assertTrue(parser.parse(line, 1).isEmpty(), line);
        }
    }
}
