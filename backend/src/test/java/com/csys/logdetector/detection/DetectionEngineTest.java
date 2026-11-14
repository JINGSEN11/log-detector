package com.csys.logdetector.detection;

import com.csys.logdetector.detection.engine.DetectionEngine;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DetectionEngineTest {
    private final DetectionEngine engine = new DetectionEngine(12, 8);
    private final LocalDateTime start = LocalDateTime.of(2025, 1, 1, 0, 0);
    private DetectionEngine.Log log(long id, String ip, int minute, String method, String path, int status, String ua) {
        return new DetectionEngine.Log(id, ip, "1.2.3.***", start.plusMinutes(minute), method, path, null, status, ua, "masked");
    }
    @Test void individualRulesAndClean() {
        Map<String, DetectionEngine.Log> samples = Map.of(
            "R1", log(1,"a",0,"GET","/?id=1%20UNION%20SELECT%20x",200,"Mozilla"),
            "R2", log(2,"a",0,"GET","/?x=%3Cscript%3E",200,"Mozilla"),
            "R3", log(3,"a",0,"GET","/%2e%2e/etc/passwd",200,"Mozilla"),
            "R4", log(4,"a",0,"GET","/",200,"sqlmap"),
            "R7", log(7,"a",0,"GET","/?x=1%3Bwhoami",200,"Mozilla"),
            "R8", log(8,"a",0,"GET","/?url=http://127.0.0.***/x",200,"Mozilla"),
            "R9", log(9,"a",0,"GET","/.env",200,"Mozilla"),
            "R10", log(10,"a",0,"GET","/?x=${jndi:ldap://host}",200,"Mozilla"),
            "INJECT", log(11,"a",0,"GET","/?q=ignore%20previous%20instructions",200,"Mozilla"));
        samples.forEach((rule, sample) -> assertTrue(engine.detect(List.of(sample)).stream().anyMatch(h -> h.rule().equals(rule)), rule));
        assertTrue(engine.detect(List.of(log(20,"a",0,"GET","/search?q=hello",200,"Mozilla"))).isEmpty());
    }
    @Test void behaviorWindowAndIsolation() {
        List<DetectionEngine.Log> logs = new ArrayList<>();
        for (int i=0;i<11;i++) logs.add(log(i,"a",i%10,"GET","/missing",404,"Mozilla"));
        assertTrue(engine.detect(logs).isEmpty());
        logs.add(log(12,"b",0,"GET","/missing",404,"Mozilla"));
        assertTrue(engine.detect(logs).isEmpty());
        logs.add(log(13,"a",10,"GET","/missing",404,"Mozilla"));
        assertEquals(12, engine.detect(logs).stream().filter(h -> h.rule().equals("R5")).count());
        List<DetectionEngine.Log> login = new ArrayList<>();
        for (int i=0;i<8;i++) login.add(log(i,"a",i==7?6:0,"POST","/login",401,"Mozilla"));
        assertTrue(engine.detect(login).isEmpty());
        login.set(7, log(7,"a",5,"POST","/login",403,"Mozilla"));
        assertEquals(8, engine.detect(login).stream().filter(h -> h.rule().equals("R6")).count());
    }
    @Test void cleanSamplesDoNotMatchAnyRule() {
        for (String path : List.of("/", "/search?q=hello", "/assets/app.js", "/help/login", "/api/status", "/about")) {
            assertTrue(engine.detect(List.of(log(1,"a",0,"GET",path,200,"Mozilla/5.0"))).isEmpty(), path);
        }
        List<DetectionEngine.Log> lowFailures = new ArrayList<>();
        for (int i = 0; i < 7; i++) lowFailures.add(log(i,"a",i%5,"POST","/login",401,"Mozilla"));
        assertTrue(engine.detect(lowFailures).isEmpty());
    }
    private DetectionEngine.Log fields(long id, String ip, long seconds, String method, String path,
                                       String query, int status, String ua) {
        return new DetectionEngine.Log(id, ip, "1.2.3.***", start.plusSeconds(seconds), method,
            path, query, status, ua, "masked");
    }
    private List<DetectionEngine.Hit> hits(DetectionEngine e, String rule, List<DetectionEngine.Log> logs) {
        return e.detect(logs).stream().filter(h -> h.rule().equals(rule)).toList();
    }
    @Test void individualRuleFieldsEvidenceAndBoundaries() {
        record Case(String rule, String path, String query, String ua, String field, String evidence, String severity) {}
        List<Case> cases = List.of(
            new Case("R1", "/search", "id=1%20UNION%20SELECT%20x", "browser", "url", "/search?id=1%20UNION%20SELECT%20x", "HIGH"),
            new Case("R2", "/search", "q=%3Cscript%3E", "browser", "url", "/search?q=%3Cscript%3E", "HIGH"),
            new Case("R3", "/%2e%2e/etc/passwd", null, "browser", "url", "/%2e%2e/etc/passwd", "HIGH"),
            new Case("R4", "/", null, "sqlmap/1", "user_agent", "sqlmap/1", "MEDIUM"),
            new Case("R7", "/run", "x=1%3Bwhoami", "browser", "url", "/run?x=1%3Bwhoami", "HIGH"),
            new Case("R8", "/fetch", "url=http%3A%2F%2F127.0.0.***%2Fx", "browser", "url", "/fetch?url=http%3A%2F%2F127.0.0.***%2Fx", "HIGH"),
            new Case("R9", "/.git/config", null, "browser", "path", "/.git/config", "MEDIUM"),
            new Case("R10", "/search", "q=%24%7Bjndi%3Aldap%3A%2F%2Fhost%7D", "browser", "url_or_user_agent", "/search?q=%24%7Bjndi%3Aldap%3A%2F%2Fhost%7D browser", "CRITICAL")
        );
        for (Case c : cases) {
            var result = hits(engine, c.rule(), List.of(fields(1, "a", 0, "GET", c.path(), c.query(), 200, c.ua())));
            assertEquals(1, result.size(), c.rule());
            assertEquals(c.field(), result.get(0).field(), c.rule());
            assertEquals(c.evidence(), result.get(0).evidence(), c.rule());
            assertEquals(c.severity(), result.get(0).severity(), c.rule());
        }
        for (String path : List.of("/.git", "/.git/", "/.git/config", "/nested/.GIT/config", "/actuator", "/actuator/", "/actuator/health", "/.env", "/backup.sql"))
            assertEquals(1, hits(engine, "R9", List.of(fields(1, "a", 0, "GET", path, null, 200, "browser"))).size(), path);
        for (String path : List.of("/.github/config", "/.gitignore", "/actuators/health", "/safe/.envfile", "/backup.sql.txt"))
            assertTrue(hits(engine, "R9", List.of(fields(1, "a", 0, "GET", path, null, 200, "browser"))).isEmpty(), path);
        var uaHit = hits(engine, "R10", List.of(fields(2, "a", 0, "GET", "/", null, 200, "${jndi:ldap://host}"))).get(0);
        assertEquals("/ ${jndi:ldap://host}", uaHit.evidence());
        assertTrue(hits(engine, "R10", List.of(fields(3, "a", 0, "GET", "/", "q=jndi:unknown:host", 200, "browser"))).isEmpty());
        Map<String, String> normal = Map.of("R1", "unionized+selection", "R2", "scripture", "R3", "..safe/file", "R7", "shell", "R8", "http://example.com", "R10", "jndi:unknown:host");
        normal.forEach((rule, query) -> assertTrue(hits(engine, rule, List.of(fields(1, "a", 0, "GET", "/search", query, 200, "browser"))).isEmpty(), rule));
        assertTrue(hits(engine, "R4", List.of(fields(1, "a", 0, "GET", "/sqlmap", null, 200, "browser"))).isEmpty());
        Map<String, String> benign = Map.of("R1", "q=select+an+item", "R2", "q=%3Cb%3Ehello%3C%2Fb%3E",
            "R3", "file=images%2Flogo.png", "R7", "q=hello%2C+world%21", "R8", "url=https%3A%2F%2Fexample.com%2F");
        benign.forEach((rule, query) -> assertTrue(hits(engine, rule, List.of(fields(1, "a", 0, "GET", "/search", query, 200, "Mozilla/5.0"))).isEmpty(), rule));
    }
    @Test void behaviorThresholdWindowEvidenceAndFilters() {
        DetectionEngine custom = new DetectionEngine(3, 3);
        for (String rule : List.of("R5", "R6")) {
            boolean login = rule.equals("R6");
            long window = login ? 300 : 600;
            String method = login ? "POST" : "GET", path = login ? "/login" : "/missing";
            int status = login ? 401 : 404;
            var a = fields(1, "a", 0, method, path, null, status, "browser");
            var b = fields(2, "a", 1, method, path, null, status, "browser");
            var edge = fields(3, "a", window, method, path, null, status, "browser");
            assertTrue(hits(custom, rule, List.of(a, b)).isEmpty(), rule);
            var result = hits(custom, rule, List.of(edge, a, b));
            assertEquals(3, result.size(), rule);
            for (var hit : result) {
                assertEquals("status_code", hit.field());
                assertEquals(login ? "登录失败窗口" : "404 高频窗口", hit.evidence());
                assertEquals("HIGH", hit.severity());
            }
            assertTrue(hits(custom, rule, List.of(a, fields(2, "a", 0, method, path, null, status, "browser"),
                fields(3, "a", window + 1, method, path, null, status, "browser"))).isEmpty(), rule);
            assertTrue(hits(custom, rule, List.of(a, b, fields(3, "b", 1, method, path, null, status, "browser"))).isEmpty(), rule);
            assertTrue(hits(custom, rule, List.of(a, b)).isEmpty()); // separate calls are separate batches
        }
        for (var invalid : List.of(fields(3, "a", 1, "GET", "/login", null, 401, "browser"),
                fields(3, "a", 1, "POST", "/login", null, 200, "browser"),
                fields(3, "a", 1, "POST", "/login-extra", null, 401, "browser"))) {
            assertTrue(hits(custom, "R6", List.of(fields(1, "a", 0, "POST", "/login", null, 401, "browser"),
                fields(2, "a", 0, "POST", "/login", null, 403, "browser"), invalid)).isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> new DetectionEngine(1, 3));
        assertThrows(IllegalArgumentException.class, () -> new DetectionEngine(3, 1));
    }
    @Test void newSignaturesMatchAndAvoidCommonNegatives() {
        var shell = fields(1, "a", 0, "POST", "/uploads/c99.php", null, 200, "browser");
        assertEquals(1, hits(engine, "R11", List.of(shell)).size());
        for (String path : List.of("/assets/shell.css", "/docs/shell.php.txt", "/shells.php"))
            assertTrue(hits(engine, "R11", List.of(fields(1, "a", 0, "GET", path, null, 200, "browser"))).isEmpty(), path);
        for (String method : List.of("TRACE", "CONNECT"))
            assertEquals(method, hits(engine, "R12", List.of(fields(1, "a", 0, method, "/", null, 200, "browser"))).get(0).evidence());
        for (String method : List.of("GET", "POST", "OPTIONS", "DELETE", "PATCH"))
            assertTrue(hits(engine, "R12", List.of(fields(1, "a", 0, method, "/", null, 200, "browser"))).isEmpty(), method);
        var sanitizer = new com.csys.logdetector.log.sanitizer.LogSanitizer();
        for (String query : List.of("password=demo-value", "x=1&api_key=demo-value", "%74oken=demo-value")) {
            String safe = sanitizer.sanitize(query);
            var hit = hits(engine, "R13", List.of(fields(1, "a", 0, "GET", "/login", safe, 200, "browser"))).get(0);
            assertFalse(safe.contains("demo-value"));
            assertFalse(hit.evidence().contains("demo-value"));
        }
        for (String query : List.of("password=", "password_hint=hello", "q=password", "tokenize=abc"))
            assertTrue(hits(engine, "R13", List.of(fields(1, "a", 0, "GET", "/", query, 200, "browser"))).isEmpty(), query);
    }
    @Test void floodThresholdWindowAndIpIsolation() {
        DetectionEngine custom = new DetectionEngine(12, 8, 3);
        var a = fields(1, "a", 0, "GET", "/", null, 200, "browser");
        var b = fields(2, "a", 0, "GET", "/", null, 200, "browser");
        var edge = fields(3, "a", 60, "POST", "/", null, 201, "browser");
        assertTrue(hits(custom, "R14", List.of(a, b)).isEmpty());
        var result = hits(custom, "R14", List.of(edge, b, a));
        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(h -> h.field().equals("client_ip_hash") && h.evidence().equals("单 IP 请求高频窗口")));
        assertTrue(hits(custom, "R14", List.of(a, b, fields(3, "a", 61, "GET", "/", null, 200, "browser"))).isEmpty());
        assertTrue(hits(custom, "R14", List.of(a, b, fields(3, "b", 1, "GET", "/", null, 200, "browser"))).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new DetectionEngine(12, 8, 1));
    }
    @Test void configuredThresholdAndLongInput() {
        DetectionEngine custom = new DetectionEngine(2, 2);
        assertEquals(2, custom.detect(List.of(log(1,"a",0,"GET","/x",404,""), log(2,"a",1,"GET","/x",404,""))).size());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> engine.detect(List.of(log(1,"a",0,"GET","/" + "a".repeat(7600),200,"Mozilla"))));
    }
}
