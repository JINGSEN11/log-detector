package com.csys.logdetector.detection;

import com.csys.logdetector.detection.service.DetectionService;
import com.csys.logdetector.detection.service.EventAnalysisService;
import com.csys.logdetector.detection.engine.DetectionEngine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.csys.logdetector.llm.client.LlmClient;
import com.csys.logdetector.llm.config.LlmProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fixed, synthetic scenario-level multilabel evaluation, independent of database-generated IDs. */
class RuleEvaluationTest {
    private static final List<String> RULES = List.of("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "R10", "R11", "R12", "R13", "R14", "INJECT");
    private static final LocalDateTime T = LocalDateTime.of(2025, 1, 1, 0, 0);
    private static final DetectionEngine ENGINE = new DetectionEngine(12, 8, 100);
    record Row(String ip, int second, String method, String path, String query, int status, String ua) {}
    record Scenario(String id, String rationale, List<String> labels, List<Row> rows) {}
    record Counts(int tp, int fp, int fn, int tn, Double precision, Double recall, Double f1) {}

    private static Row row(String method, String path, String query, int status, String ua) {
        return new Row("source-a", 0, method, path, query, status, ua);
    }
    private static Scenario single(String id, String reason, String label, Row row) {
        return new Scenario(id, reason, label == null ? List.of() : List.of(label), List.of(row));
    }
    private static Scenario window(String id, String reason, String label, int count, int seconds, boolean splitIp, String method, String path, int status) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(new Row(splitIp && i == count - 1 ? "source-b" : "source-a", i == count - 1 ? seconds : 0, method, path, null, status, "browser"));
        return new Scenario(id, reason, label == null ? List.of() : List.of(label), rows);
    }
    static List<Scenario> dataset() {
        List<Scenario> s = new ArrayList<>();
        s.add(single("sql", "SQL union 注入", "R1", row("GET", "/search", "id=1%20union%20select%20x", 200, "browser")));
        s.add(single("xss", "脚本标签注入", "R2", row("GET", "/search", "q=%3Cscript%3E", 200, "browser")));
        s.add(single("traversal", "目录穿越", "R3", row("GET", "/%2e%2e/etc/passwd", null, 200, "browser")));
        s.add(single("scanner", "已知扫描器标识", "R4", row("GET", "/", null, 200, "sqlmap/1")));
        s.add(window("404-edge", "12 次 404，10 分钟含边界", "R5", 12, 600, false, "GET", "/missing", 404));
        s.add(window("login-edge", "8 次失败登录，5 分钟含边界", "R6", 8, 300, false, "POST", "/login", 401));
        s.add(single("command", "URL 中的 shell 命令", "R7", row("GET", "/run", "x=1%3Bwhoami", 200, "browser")));
        s.add(single("ssrf", "目标为内部地址", "R8", row("GET", "/fetch", "url=http://127.0.0.***/x", 200, "browser")));
        s.add(single("sensitive", "访问 git 配置", "R9", row("GET", "/.git/config", null, 200, "browser")));
        s.add(single("jndi", "JNDI 查找", "R10", row("GET", "/search", "q=${jndi:ldap://host}", 200, "browser")));
        s.add(single("webshell", "已知 WebShell 文件名", "R11", row("POST", "/uploads/c99.php", null, 200, "browser")));
        s.add(single("trace", "TRACE 方法", "R12", row("TRACE", "/", null, 200, "browser")));
        s.add(single("credential", "已脱敏 URL 凭据参数", "R13", row("GET", "/login", "password=[REDACTED]", 200, "browser")));
        s.add(window("flood-edge", "100 次请求，1 分钟含边界", "R14", 100, 60, false, "GET", "/health", 200));
        s.add(single("prompt-injection", "提示指令覆盖", "INJECT", row("GET", "/search", "q=ignore%20previous%20instructions", 200, "browser")));
        s.add(single("clean-home", "普通首页", null, row("GET", "/", null, 200, "Mozilla/5.0")));
        s.add(single("clean-search", "普通搜索", null, row("GET", "/search", "q=hello", 200, "browser")));
        s.add(single("clean-static", "静态文件", null, row("GET", "/assets/shell.css", null, 200, "browser")));
        s.add(single("clean-options", "普通跨域预检", null, row("OPTIONS", "/api", null, 204, "browser")));
        s.add(single("clean-secret-hint", "不是凭据参数", null, row("GET", "/login", "password_hint=hello", 200, "browser")));
        s.add(single("clean-empty-secret", "空凭据参数", null, row("GET", "/login", "password=", 200, "browser")));
        s.add(single("clean-github", "相似但不是 git 元数据", null, row("GET", "/.github/config", null, 200, "browser")));
        s.add(single("clean-external", "外部 URL", null, row("GET", "/fetch", "url=https://example.com/", 200, "browser")));
        s.add(window("404-below", "404 次数低于阈值", null, 11, 0, false, "GET", "/missing", 404));
        s.add(window("404-outside", "404 窗口超过 10 分钟", null, 12, 601, false, "GET", "/missing", 404));
        s.add(window("login-below", "登录失败低于阈值", null, 7, 0, false, "POST", "/login", 401));
        s.add(window("login-outside", "登录窗口超过 5 分钟", null, 8, 301, false, "POST", "/login", 401));
        s.add(window("flood-below", "请求数低于阈值", null, 99, 0, false, "GET", "/health", 200));
        s.add(window("flood-outside", "请求窗口超过 1 分钟", null, 100, 61, false, "GET", "/health", 200));
        s.add(window("flood-split", "请求跨两个来源", null, 100, 0, true, "GET", "/health", 200));
        s.add(new Scenario("multi-sql-xss", "同一场景的两种规则", List.of("R1", "R2"), List.of(row("GET", "/search", "q=union%20select%20x%20%3Cscript%3E", 200, "browser"))));
        return List.copyOf(s);
    }
    private static Counts counts(int tp, int fp, int fn, int tn) {
        Double p = tp + fp == 0 ? null : (double) tp / (tp + fp);
        Double r = tp + fn == 0 ? null : (double) tp / (tp + fn);
        Double f = p == null || r == null ? null : p + r == 0 ? 0.0 : 2 * p * r / (p + r);
        return new Counts(tp, fp, fn, tn, p, r, f);
    }
    @Test void evaluateFixedLabeledScenarios() throws Exception {
        List<Scenario> data = dataset();
        assertTrue(data.size() >= 25);
        assertEquals(data.size(), data.stream().map(Scenario::id).distinct().count());
        assertTrue(data.stream().flatMap(x -> x.labels().stream()).collect(Collectors.toSet()).containsAll(RULES));
        Map<String, int[]> totals = new LinkedHashMap<>();
        RULES.forEach(r -> totals.put(r, new int[4]));
        List<Map<String, Object>> details = new ArrayList<>();
        int clean = 0, predictedPositive = 0, fallbackScenarios = 0;
        DetectionService detection = mock(DetectionService.class);
        LlmClient client = mock(LlmClient.class);
        EventAnalysisService analysis = new EventAnalysisService(detection, client, new LlmProperties("", "", "", 5, true), new ObjectMapper());
        for (Scenario scenario : data) {
            List<DetectionEngine.Log> logs = new ArrayList<>();
            long id = 0;
            for (Row r : scenario.rows()) logs.add(new DetectionEngine.Log(++id, r.ip(), "masked", T.plusSeconds(r.second()), r.method(), r.path(), r.query(), r.status(), r.ua(), "masked"));
            List<DetectionEngine.Hit> hits = ENGINE.detect(logs);
            Set<String> actual = hits.stream().map(DetectionEngine.Hit::rule).collect(Collectors.toCollection(TreeSet::new));
            if (!hits.isEmpty()) {
                List<Map<String, Object>> evidence = hits.stream().map(h -> Map.<String, Object>of("logId", h.log().id(), "ruleCode", h.rule())).toList();
                when(detection.event(1L)).thenReturn(Map.of("evidence", evidence));
                assertEquals("RULE_FALLBACK", analysis.analyze(1L).get("source"));
                fallbackScenarios++;
            }
            Set<String> expected = new TreeSet<>(scenario.labels());
            assertTrue(RULES.containsAll(actual), "unknown output rule");
            if (expected.isEmpty()) clean++;
            if (!actual.isEmpty()) predictedPositive++;
            Set<String> fp = new TreeSet<>(actual); fp.removeAll(expected);
            Set<String> fn = new TreeSet<>(expected); fn.removeAll(actual);
            for (String rule : RULES) {
                boolean e = expected.contains(rule), a = actual.contains(rule);
                totals.get(rule)[e ? (a ? 0 : 2) : (a ? 1 : 3)]++;
            }
            details.add(Map.of("id", scenario.id(), "rationale", scenario.rationale(), "rowCount", scenario.rows().size(), "expected", expected, "actual", actual, "falsePositive", fp, "falseNegative", fn));
        }
        Map<String, Counts> perRule = new LinkedHashMap<>();
        int[] sum = new int[4];
        totals.forEach((rule, n) -> { perRule.put(rule, counts(n[0], n[1], n[2], n[3])); for (int i = 0; i < 4; i++) sum[i] += n[i]; });
        ObjectMapper json = new ObjectMapper();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(data)));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetVersion", "synthetic-scenario-v1");
        report.put("datasetSha256", hash);
        report.put("definition", "scenario-rule multilabel; each scenario is an isolated batch; all 15 labels are exhaustive; input is already sanitized");
        report.put("thresholds", Map.of("R5", "12/10min", "R6", "8/5min", "R14", "100/1min"));
        report.put("scenarioCount", data.size()); report.put("cleanScenarioCount", clean);
        report.put("dataset", data);
        report.put("micro", counts(sum[0], sum[1], sum[2], sum[3])); report.put("perRule", perRule); report.put("details", details);
        verifyNoInteractions(client);
        report.put("comparison", Map.of("unit", "one simulated event per rule-positive scenario, not production event aggregation", "rulePositiveScenarios", predictedPositive, "noRuleHitScenarios", data.size() - predictedPositive, "mockFallbackScenarios", fallbackScenarios, "modelCalls", 0, "analysis", "Mock analysis invoked on synthetic event evidence returns RULE_FALLBACK; current analysis has no attack/benign verdict, so classification metrics or model quality comparison are unavailable. No-rule-hit scenarios cannot enter analysis."));
        String output = System.getProperty("evaluation.output");
        if (output != null && !output.isBlank()) {
            Path path = Path.of(output).toAbsolutePath();
            if (Files.exists(path)) throw new IllegalStateException("Refusing to overwrite evaluation report: " + path);
            Files.createDirectories(path.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        }
        System.out.println("Rule evaluation: scenarios=" + data.size() + " micro=" + report.get("micro") + " sha256=" + hash);
        assertEquals(0, sum[1], "False positives; inspect report details");
        assertEquals(0, sum[2], "False negatives; inspect report details");
    }
}
