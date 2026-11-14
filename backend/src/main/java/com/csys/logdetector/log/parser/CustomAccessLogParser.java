package com.csys.logdetector.log.parser;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** 六列制表符分隔的自定义访问日志解析器。 */
@Component
public class CustomAccessLogParser implements LogParser {
    @Override
    public String formatId() { return "custom"; }

    @Override
    public Optional<ParsedLogLine> parse(String line, int lineNumber) {
        if (line == null || line.length() > 8192) return Optional.empty();
        String[] fields = line.split("\t", -1);
        if (fields.length != 6 || !fields[0].matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})")
                || !isIpv4(fields[1]) || !fields[2].matches("[A-Z]{1,16}")
                || fields[3].isEmpty() || fields[3].chars().anyMatch(Character::isWhitespace)
                || !fields[4].matches("[1-5][0-9]{2}") || !fields[5].matches("[0-9]+")) {
            return Optional.empty();
        }
        try {
            var instant = OffsetDateTime.parse(fields[0]).toInstant();
            int databaseYear = instant.atOffset(java.time.ZoneOffset.ofHours(8)).getYear();
            if (databaseYear < 1000 || databaseYear > 9999) return Optional.empty();
            return Optional.of(new ParsedLogLine(lineNumber, fields[1], instant,
                    fields[2], fields[3], Integer.parseInt(fields[4]), Long.parseLong(fields[5]), "", "", line));
        } catch (DateTimeParseException | NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private boolean isIpv4(String ip) {
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }
}
