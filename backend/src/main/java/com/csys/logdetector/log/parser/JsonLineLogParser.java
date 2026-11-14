package com.csys.logdetector.log.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

@Component
public class JsonLineLogParser implements LogParser {

    private final ObjectMapper objectMapper;

    public JsonLineLogParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String formatId() { return "jsonl"; }

    @Override
    public Optional<ParsedLogLine> parse(String line, int lineNumber) {
        if (line == null || line.length() > 8192) return Optional.empty();
        try {
            JsonNode json = objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(line);
            if (json == null || !json.isObject()) return Optional.empty();
            String ip = text(json, "ip", "client_ip", "remote_addr");
            String time = text(json, "timestamp", "time", "@timestamp");
            String method = text(json, "method", "request_method");
            String path = text(json, "path", "url", "request_uri");
            int status = json.path("status").asInt(json.path("status_code").asInt(-1));
            if (ip.isBlank() || time.isBlank() || method.isBlank() || path.isBlank() || status < 100 || status > 599) {
                return Optional.empty();
            }
            Instant instant;
            try {
                instant = Instant.parse(time);
            } catch (DateTimeParseException ignored) {
                try {
                    instant = OffsetDateTime.parse(time).toInstant();
                } catch (DateTimeParseException alsoIgnored) {
                    instant = LocalDateTime.parse(time).toInstant(ZoneOffset.UTC);
                }
            }
            return Optional.of(new ParsedLogLine(lineNumber, ip, instant, method, path, status,
                    json.path("bytes").asLong(json.path("body_bytes_sent").asLong(0)),
                    text(json, "referer", "http_referer"), text(json, "userAgent", "user_agent", "http_user_agent"), line));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String text(JsonNode json, String... names) {
        for (String name : names) {
            JsonNode value = json.get(name);
            if (value != null && value.isValueNode()) return value.asText("");
        }
        return "";
    }
}
