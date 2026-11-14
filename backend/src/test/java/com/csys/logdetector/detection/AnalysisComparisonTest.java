package com.csys.logdetector.detection;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Offline, human-reviewed comparison. This does not call a model or read production logs. */
class AnalysisComparisonTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> GOLD = Set.of("ATTACK", "BENIGN", "UNCERTAIN");
    private static final Set<String> REVIEW = Set.of("ATTACK", "BENIGN", "UNCERTAIN");
    private static final Set<String> SOURCE = Set.of("MODEL", "RULE_FALLBACK", "NOT_REQUESTED", "ERROR", "NO_HIT");

    static Map<String, Object> evaluate(JsonNode root) {
        if (!root.isObject() || !root.path("datasetVersion").isTextual() || root.path("datasetVersion").asText().isBlank()
                || !root.path("runId").isTextual() || root.path("runId").asText().isBlank() || !root.path("cases").isArray())
            throw new IllegalArgumentException("datasetVersion、runId 和 cases 必填");
        Set<String> ids = new HashSet<>();
        Map<String, Integer> sources = new TreeMap<>();
        Map<String, Integer> exclusions = new TreeMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        int[] rules = new int[4], assisted = new int[4], pairedRules = new int[4], pairedAssisted = new int[4];
        int eligible = 0, decided = 0;
        for (JsonNode c : root.path("cases")) {
            if (!c.isObject() || !c.path("id").isTextual() || c.path("id").asText().isBlank()
                    || !ids.add(c.path("id").asText()) || !c.path("ruleHit").isBoolean())
                throw new IllegalArgumentException("样本 id 必须唯一且 ruleHit 必须为布尔值");
            String gold = c.path("gold").asText(), source = c.path("source").asText();
            if (!GOLD.contains(gold) || !SOURCE.contains(source)) throw new IllegalArgumentException("gold 或 source 无效");
            boolean hit = c.path("ruleHit").booleanValue();
            if ((!hit && !source.equals("NO_HIT")) || (hit && source.equals("NO_HIT")))
                throw new IllegalArgumentException("NO_HIT 必须且只能用于无规则命中样本");
            String review = c.path("review").asText("");
            if (source.equals("MODEL")) {
                if (!REVIEW.contains(review)) throw new IllegalArgumentException("MODEL 需要人工判定 ATTACK/BENIGN/UNCERTAIN");
            } else if (!review.isEmpty()) throw new IllegalArgumentException("非 MODEL 样本不能填写研判判定");
            sources.merge(source, 1, Integer::sum);
            String prediction = !hit ? "BENIGN" : source.equals("MODEL") ? review : "ATTACK";
            if (!gold.equals("UNCERTAIN")) {
                eligible++;
                add(rules, gold.equals("ATTACK"), hit);
                if (!prediction.equals("UNCERTAIN")) {
                    decided++;
                    add(assisted, gold.equals("ATTACK"), prediction.equals("ATTACK"));
                    add(pairedRules, gold.equals("ATTACK"), hit);
                    add(pairedAssisted, gold.equals("ATTACK"), prediction.equals("ATTACK"));
                } else exclusions.merge("reviewUncertain", 1, Integer::sum);
            } else exclusions.merge("goldUncertain", 1, Integer::sum);
            details.add(Map.of("id", c.path("id").asText(), "gold", gold, "ruleHit", hit, "source", source, "review", review, "assistedPrediction", prediction));
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("cases 不能为空");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasetVersion", root.path("datasetVersion").asText()); out.put("runId", root.path("runId").asText());
        out.put("definition", "Scenario-level binary workflow, ruleHit=positive; no hit=negative; fallback/error/not requested retain rule positive; MODEL uses independent human review. Uncertain decisions excluded and counted. Not model-only performance.");
        out.put("total", ids.size()); out.put("eligibleGold", eligible); out.put("assistedDecided", decided);
        out.put("sources", sources); out.put("excluded", exclusions); out.put("rulesAllEligible", metrics(rules));
        out.put("rulesPaired", metrics(pairedRules)); out.put("assistedPaired", metrics(pairedAssisted)); out.put("details", details);
        return out;
    }
    private static void add(int[] c, boolean gold, boolean predicted) { c[gold ? (predicted ? 0 : 2) : (predicted ? 1 : 3)]++; }
    private static Map<String, Object> metrics(int[] c) {
        Double p = c[0] + c[1] == 0 ? null : (double)c[0] / (c[0] + c[1]);
        Double r = c[0] + c[2] == 0 ? null : (double)c[0] / (c[0] + c[2]);
        Double f = p == null || r == null ? null : p + r == 0 ? 0.0 : 2 * p * r / (p + r);
        Map<String, Object> out = new LinkedHashMap<>(); out.put("tp", c[0]); out.put("fp", c[1]); out.put("fn", c[2]); out.put("tn", c[3]);
        out.put("precision", p); out.put("recall", r); out.put("f1", f); return out;
    }
    @Test void validatesAndComparesWithExplicitCoverage() throws Exception {
        JsonNode input = JSON.readTree("""
          {"datasetVersion":"synthetic-demo","runId":"unit","cases":[
            {"id":"a","gold":"ATTACK","ruleHit":true,"source":"MODEL","review":"ATTACK"},
            {"id":"b","gold":"BENIGN","ruleHit":true,"source":"MODEL","review":"BENIGN"},
            {"id":"c","gold":"ATTACK","ruleHit":false,"source":"NO_HIT"},
            {"id":"d","gold":"BENIGN","ruleHit":true,"source":"RULE_FALLBACK"},
            {"id":"e","gold":"ATTACK","ruleHit":true,"source":"MODEL","review":"UNCERTAIN"},
            {"id":"f","gold":"UNCERTAIN","ruleHit":false,"source":"NO_HIT"}]}
          """);
        Map<String, Object> report = evaluate(input);
        assertEquals(6, report.get("total")); assertEquals(5, report.get("eligibleGold")); assertEquals(4, report.get("assistedDecided"));
        assertEquals(Map.of("goldUncertain", 1, "reviewUncertain", 1), report.get("excluded"));
        Map<?, ?> original = (Map<?, ?>) report.get("rulesPaired"), assisted = (Map<?, ?>) report.get("assistedPaired");
        assertEquals(2, original.get("fp")); assertEquals(1, assisted.get("fp")); assertEquals(1, assisted.get("fn"));
        assertThrows(IllegalArgumentException.class, () -> evaluate(JSON.readTree("{\"datasetVersion\":\"v\",\"runId\":\"r\",\"cases\":[{\"id\":\"x\",\"gold\":\"ATTACK\",\"ruleHit\":true,\"source\":\"MODEL\"}]}")));
        assertThrows(IllegalArgumentException.class, () -> evaluate(JSON.readTree("{\"datasetVersion\":\"v\",\"runId\":\"r\",\"cases\":[{\"id\":\"x\",\"gold\":\"ATTACK\",\"ruleHit\":false,\"source\":\"MODEL\",\"review\":\"ATTACK\"}]}")));
        assertNull(metrics(new int[4]).get("precision"));
    }
    @Test void evaluateOptionalReviewedRun() throws Exception {
        String input = System.getProperty("comparison.input"), output = System.getProperty("comparison.output");
        if (input == null && output == null) return;
        if (input == null || output == null) throw new IllegalArgumentException("必须同时提供 comparison.input 和 comparison.output");
        Path target = Path.of(output).toAbsolutePath();
        if (Files.exists(target)) throw new IllegalArgumentException("输出文件已存在，拒绝覆盖");
        Map<String, Object> result = evaluate(JSON.readTree(Path.of(input).toFile()));
        Files.createDirectories(target.getParent());
        try (var stream = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            JSON.writerWithDefaultPrettyPrinter().writeValue(stream, result);
        }
        System.out.println("Comparison: total=" + result.get("total") + ", eligible=" + result.get("eligibleGold") + ", paired=" + result.get("assistedDecided"));
    }
}
