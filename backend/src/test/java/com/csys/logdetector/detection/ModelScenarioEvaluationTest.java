package com.csys.logdetector.detection;

import com.csys.logdetector.detection.engine.DetectionEngine;
import com.csys.logdetector.detection.service.EventAnalysisService;
import com.csys.logdetector.llm.client.LlmClient;
import com.csys.logdetector.llm.client.OpenAiCompatLlmClient;
import com.csys.logdetector.llm.config.LlmProperties;
import com.csys.logdetector.llm.dto.LlmResult;
import com.csys.logdetector.llm.service.LlmCallRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Opt-in synthetic evaluation. Never loads Spring, a database, or application configuration. */
class ModelScenarioEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SCENARIOS = 16, MAX_CALLS = 32;
    private static final LocalDateTime T = LocalDateTime.of(2025, 1, 1, 0, 0);
    private record Case(String id, String rationale, List<String> truth, List<Map<String, Object>> evidence) {}

    private static List<Case> cases() {
        DetectionEngine engine = new DetectionEngine(12, 8, 100);
        List<Case> cases = new ArrayList<>();
        for (RuleEvaluationTest.Scenario scenario : RuleEvaluationTest.dataset()) {
            List<DetectionEngine.Log> logs = new ArrayList<>();
            long id = 0;
            for (RuleEvaluationTest.Row r : scenario.rows()) {
                logs.add(new DetectionEngine.Log(++id, r.ip(), "masked", T.plusSeconds(r.second()),
                        r.method(), r.path(), r.query(), r.status(), r.ua(), "masked"));
            }
            List<DetectionEngine.Hit> hits = engine.detect(logs);
            Set<String> actual = new TreeSet<>();
            hits.forEach(hit -> actual.add(hit.rule()));
            // Abort before any model call if rule output or the fixed labels have changed.
            if (!actual.equals(new TreeSet<>(scenario.labels()))) throw new IllegalStateException("Dataset mismatch: " + scenario.id());
            if (!hits.isEmpty()) {
                List<Map<String, Object>> evidence = hits.stream()
                        .map(hit -> Map.<String, Object>of("logId", hit.log().id(), "ruleCode", hit.rule())).distinct().toList();
                cases.add(new Case(scenario.id(), scenario.rationale(), scenario.labels(), evidence));
            }
        }
        if (cases.size() != MAX_SCENARIOS) throw new IllegalStateException("Unexpected positive scenario count");
        return List.copyOf(cases);
    }

    private static final class BoundedClient implements LlmClient {
        private final LlmClient delegate;
        private int calls;
        BoundedClient(LlmClient delegate) { this.delegate = delegate; }
        public LlmResult chat(String prompt) {
            if (calls >= MAX_CALLS) throw new IllegalStateException("Call budget exhausted");
            calls++;
            return delegate.chat(prompt);
        }
        public <T> T chatAsJson(String prompt, Class<T> type) { throw new UnsupportedOperationException(); }
    }

    private static Map<String, Object> analyze(Case c, BoundedClient client, List<String> attempts) {
        EventAnalysisService service = new EventAnalysisService(null, client,
                new LlmProperties("", "", "", 5, false), JSON);
        return service.analyzeEvent(1L, Map.of("evidence", c.evidence()),
                (outcome, elapsed, result) -> attempts.add(outcome));
    }

    @Test void offlineDatasetAndBoundary() throws Exception {
        List<Case> data = cases();
        LlmClient stub = mock(LlmClient.class);
        when(stub.chat(anyString())).thenReturn(new LlmResult("invalid", "stub", 0, 0, 0, false));
        BoundedClient bounded = new BoundedClient(stub);
        for (Case c : data) {
            List<String> attempts = new ArrayList<>();
            assertEquals("RULE_FALLBACK", analyze(c, bounded, attempts).get("source"));
            assertEquals(List.of("INVALID", "INVALID"), attempts);
        }
        assertEquals(MAX_CALLS, bounded.calls);
        verify(stub, times(MAX_CALLS)).chat(argThat(prompt -> {
            try {
                String facts = prompt.substring(prompt.indexOf("数据：") + 3);
                var array = JSON.readTree(facts);
                for (var fact : array) {
                    if (fact.size() != 2 || !fact.path("logId").isIntegralNumber() || !fact.path("ruleCode").isTextual()) return false;
                }
                return array.isArray() && !array.isEmpty();
            } catch (Exception e) { return false; }
        }));
        assertThrows(IllegalStateException.class, () -> bounded.chat("extra"));
    }

    @Test void liveEvaluation() throws Exception {
        assumeTrue("true".equals(System.getProperty("evaluation.live")), "Explicit live opt-in required");
        String output = System.getProperty("evaluation.output");
        if (output == null || output.isBlank()) throw new IllegalArgumentException("evaluation.output is required");
        List<Case> data = cases();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.writeValueAsBytes(RuleEvaluationTest.dataset())));
        // Environment is consulted only after explicit opt-in. Never print credentials.
        String base = System.getenv("LLM_BASE_URL"), key = System.getenv("LLM_API_KEY"), model = System.getenv("LLM_MODEL");
        if (base == null || base.isBlank() || key == null || key.isBlank() || model == null || model.isBlank())
            throw new IllegalArgumentException("Set LLM_BASE_URL, LLM_API_KEY and LLM_MODEL in the environment");
        java.net.URI endpoint = java.net.URI.create(base);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("LLM_BASE_URL must be HTTPS without credentials, query or fragment");
        Path path = Path.of(output).toAbsolutePath();
        Files.createDirectories(path.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            write(writer, Map.of("type", "metadata", "datasetVersion", "synthetic-scenario-v1", "datasetSha256", hash,
                    "scenarioCount", data.size(), "maxCalls", MAX_CALLS,
                    "scope", "rule-positive synthetic scenarios only; no benign controls; no automatic verdict"));
            LlmProperties properties = new LlmProperties(base, key, model, 60, false);
            LlmCallRecorder recorder = mock(LlmCallRecorder.class);
            BoundedClient client = new BoundedClient(new OpenAiCompatLlmClient(JSON, recorder, properties));
            for (Case c : data) {
                List<String> attempts = new ArrayList<>();
                Map<String, Object> result = analyze(c, client, attempts);
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("type", "scenario"); record.put("id", c.id()); record.put("rationale", c.rationale());
                record.put("truth", c.truth()); record.put("evidence", c.evidence()); record.put("attempts", attempts);
                record.put("result", result);
                write(writer, record);
            }
            write(writer, Map.of("type", "complete", "calls", client.calls));
        }
    }

    private static void write(BufferedWriter writer, Object value) throws Exception {
        writer.write(JSON.writeValueAsString(value)); writer.newLine(); writer.flush();
    }
}
