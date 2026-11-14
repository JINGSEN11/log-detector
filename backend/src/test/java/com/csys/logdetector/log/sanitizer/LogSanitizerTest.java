package com.csys.logdetector.log.sanitizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogSanitizerTest {

    private final LogSanitizer sanitizer = new LogSanitizer();

    @Test
    void masksCredentialPiiAndIpv4() {
        String safe = sanitizer.sanitize("ip=203.0.113.9 password=hunter2 email=student@example.test phone=13800138000");
        assertFalse(safe.contains("hunter2"));
        assertFalse(safe.contains("student@example.test"));
        assertFalse(safe.contains("13800138000"));
        assertEquals("ip=203.0.113.*** password=[REDACTED] email=***@REDACTED phone=***********", safe);
    }

    @Test
    void masksIdentityAndLuhnValidCardNumbers() {
        String identity = "11010519491231002X";
        String card = "4532015112830366";
        String safe = sanitizer.sanitize("id=" + identity + " card=" + card);
        assertFalse(safe.contains(identity));
        assertFalse(safe.contains(card));
        assertTrue(safe.contains("******************"));
        assertTrue(safe.contains("[CARD_REDACTED]"));
    }

    @Test
    void masksJsonSecretsRecursivelyAndIpv6() {
        String safe = sanitizer.sanitizeJson("{\"password\":\"p\\\"ass\",\"nested\":[{\"Authorization\":\"Bearer abc\",\"token\":12345}],\"ip\":\"2001:db8::1\",\"message\":\"from [::ffff:192.0.2.1] and 2001:db8:0:0:0:0:0:2\"}");
        assertFalse(safe.contains("p\\\"ass"));
        assertFalse(safe.contains("Bearer abc"));
        assertFalse(safe.contains("12345"));
        assertFalse(safe.contains("2001:db8"));
        assertFalse(safe.contains("192.0.2.1"));
        assertTrue(safe.contains("[IPV6_REDACTED]"));
        assertEquals("[REDACTED]", sanitizer.sanitizeJson("{invalid"));
    }

    @Test
    void masksBareJwtEncodedCredentialsAndCookieHeader() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature";
        String safe = sanitizer.sanitize("GET /?%2570assword%253Dhunter2&mail=student%40example.test "
                + jwt + " Cookie: session=abc; other=def\"");
        for (String secret : new String[]{"hunter2", "student%40example.test", "student@example.test", jwt, "abc", "def"}) {
            assertFalse(safe.contains(secret), safe);
        }
        assertEquals("/search?q=%3Cscript%3E", sanitizer.sanitize("/search?q=%3Cscript%3E"));
        String json = sanitizer.sanitizeJson("{\"%74oken\":\"secret-value\",\"" + jwt + "\":\"x\"}");
        assertFalse(json.contains("secret-value"));
        assertFalse(json.contains(jwt));
    }

    @Test
    void masksIpv4ForDisplay() {
        assertEquals("203.0.113.***", sanitizer.maskIpv4("203.0.113.9"));
    }
}
