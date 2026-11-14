package com.csys.logdetector.log.sanitizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/** 持久化或展示前的凭据与 PII 清理边界。 */
@Component
public class LogSanitizer {

    private static final Pattern SECRET_PARAMETER = Pattern.compile(
            "(?i)(password|passwd|token|access_token|api[_-]?key|secret|cookie)=([^&\\s;]+)");
    private static final Pattern JWT = Pattern.compile("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?![A-Za-z0-9_-])");
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)(cookie\\s*:\\s*)[^\\r\\n\\\"]+");
    private static final Pattern AUTHORIZATION_PARAMETER = Pattern.compile("(?i)(authorization)=([^&\\s;]+)");
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(authorization\\s*:\\s*)([^\\s,]+(?:\\s+[^\\s,]+)?)");
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD_CANDIDATE = Pattern.compile("(?<![\\d.])\\d{13,19}(?![\\d.])");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![a-z0-9_:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]*(?:%[a-z0-9_.~-]+)?(?![a-z0-9_:])");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)^(?:password|passwd|token|access[_-]?token|api[_-]?key|secret|cookie|authorization)$");
    private static final ObjectMapper JSON = new ObjectMapper();

    public String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // Canonicalize percent encoding before inspecting, but retain the original text if
        // decoding did not expose anything sensitive (attack rules may need its encoding).
        String decoded = value;
        for (int i = 0; i < 4 && decoded.contains("%"); i++) {
            try {
                String next = URLDecoder.decode(decoded.replace("+", "%2B"), StandardCharsets.UTF_8);
                if (next.equals(decoded)) break;
                decoded = next;
            } catch (IllegalArgumentException ex) {
                // Decode valid escapes even when unrelated malformed escapes are present.
                String repaired = decoded.replaceAll("%(?![0-9a-fA-F]{2})", "%25");
                String next = URLDecoder.decode(repaired.replace("+", "%2B"), StandardCharsets.UTF_8);
                if (next.equals(decoded)) break;
                decoded = next;
            }
        }
        // Unresolved nested encoding must not bypass the storage boundary.
        if (Pattern.compile("%[0-9a-fA-F]{2}").matcher(decoded).find()) return "[REDACTED]";
        String cleaned = sanitizePlain(decoded);
        return cleaned.equals(decoded) ? sanitizePlain(value) : cleaned;
    }

    private String sanitizePlain(String value) {
        String safe = SECRET_PARAMETER.matcher(value).replaceAll("$1=[REDACTED]");
        safe = COOKIE_HEADER.matcher(safe).replaceAll("$1[REDACTED]");
        safe = JWT.matcher(safe).replaceAll("[JWT_REDACTED]");
        safe = AUTHORIZATION_PARAMETER.matcher(safe).replaceAll("$1=[REDACTED]");
        safe = AUTHORIZATION.matcher(safe).replaceAll("$1[REDACTED]");
        safe = EMAIL.matcher(safe).replaceAll("***@REDACTED");
        safe = PHONE.matcher(safe).replaceAll("***********");
        safe = ID_CARD.matcher(safe).replaceAll("******************");
        safe = BANK_CARD_CANDIDATE.matcher(safe).replaceAll(match -> isLuhnValid(match.group()) ? "[CARD_REDACTED]" : match.group());
        safe = IPV6.matcher(safe).replaceAll(match -> isIpv6(match.group()) ? "[IPV6_REDACTED]" : match.group());
        return IPV4.matcher(safe).replaceAll(match -> maskIpv4(match.group()));
    }

    /** JSON 原始行必须先按字段清理，避免引号、转义和嵌套结构绕过文本规则。 */
    public String sanitizeJson(String value) {
        try {
            JsonNode node = JSON.readTree(value);
            if (node == null) return "[REDACTED]";
            return JSON.writeValueAsString(sanitizeNode(node));
        } catch (Exception ex) {
            return "[REDACTED]";
        }
    }

    private JsonNode sanitizeNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = JSON.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                boolean secret = SECRET_KEY.matcher(field.getKey()).matches()
                        || sanitize(field.getKey() + "=sensitive").contains("[REDACTED]");
                object.set(sanitize(field.getKey()), secret
                        ? JSON.getNodeFactory().textNode("[REDACTED]") : sanitizeNode(field.getValue()));
            }
            return object;
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) array.set(i, sanitizeNode(array.get(i)));
        } else if (node.isTextual() || node.isNumber()) {
            String text = node.asText();
            String safe = sanitize(text);
            if (node.isTextual() || !safe.equals(text)) return JSON.getNodeFactory().textNode(safe);
        }
        return node;
    }

    private boolean isIpv6(String candidate) {
        try {
            int zone = candidate.indexOf('%');
            InetAddress.getByName(zone < 0 ? candidate : candidate.substring(0, zone));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isLuhnValid(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int value = digits.charAt(i) - '0';
            if (doubleDigit) {
                value *= 2;
                if (value > 9) value -= 9;
            }
            sum += value;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    public String maskIpv4(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return "***";
        }
        return parts[0] + "." + parts[1] + "." + parts[2] + ".***";
    }
}
