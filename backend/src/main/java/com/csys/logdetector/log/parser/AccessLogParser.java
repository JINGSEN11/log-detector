package com.csys.logdetector.log.parser;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

/** Apache/Nginx common 与 combined 格式解析器。 */
@Component
public class AccessLogParser implements LogParser {
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss xx", Locale.ENGLISH);

    @Override
    public String formatId() { return "access"; }

    @Override
    public Optional<ParsedLogLine> parse(String line, int lineNumber) {
        if (line == null || line.length() > 8192) return Optional.empty();
        try {
            Scanner scan = new Scanner(line);
            String ip = scan.token();
            scan.token(); // ident
            scan.token(); // user
            String time = scan.delimited('[', ']');
            String request = scan.delimited('"', '"');
            String[] parts = request.split(" +");
            if (parts.length < 2 || parts.length > 3 || !parts[0].matches("[A-Z]+")
                    || (parts.length == 3 && !parts[2].startsWith("HTTP/"))) return Optional.empty();
            int status = Integer.parseInt(scan.token());
            String bytesText = scan.token();
            long bytes = "-".equals(bytesText) ? 0 : Long.parseLong(bytesText);
            if (status < 100 || status > 599 || bytes < 0) return Optional.empty();
            String referer = "", agent = "";
            if (scan.hasMore()) {
                referer = scan.delimited('"', '"');
                agent = scan.delimited('"', '"');
            }
            if (scan.hasMore()) return Optional.empty();
            return Optional.of(new ParsedLogLine(lineNumber, ip, OffsetDateTime.parse(time, LOG_TIME).toInstant(),
                    parts[0], parts[1], status, bytes, "-".equals(referer) ? "" : referer,
                    "-".equals(agent) ? "" : agent, line));
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    private static class Scanner {
        private final String text;
        private int index;
        Scanner(String text) { this.text = text; }
        boolean hasMore() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
            return index < text.length();
        }
        String token() {
            if (!hasMore()) throw new IllegalArgumentException();
            int start = index;
            while (index < text.length() && !Character.isWhitespace(text.charAt(index))) index++;
            return text.substring(start, index);
        }
        String delimited(char open, char close) {
            if (!hasMore() || text.charAt(index++) != open) throw new IllegalArgumentException();
            int start = index;
            boolean escaped = false;
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == close && !escaped) return text.substring(start, index - 1);
                if (open == '"' && c == '\\' && !escaped) escaped = true;
                else escaped = false;
            }
            throw new IllegalArgumentException();
        }
    }
}
