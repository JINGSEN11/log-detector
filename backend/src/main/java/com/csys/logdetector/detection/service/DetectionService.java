package com.csys.logdetector.detection.service;

import com.csys.logdetector.detection.engine.DetectionEngine;

import com.csys.logdetector.common.BizException;
import com.csys.logdetector.detection.engine.DetectionEngine.Hit;
import com.csys.logdetector.detection.engine.DetectionEngine.Log;
import com.csys.logdetector.detection.mapper.DetectionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

/** 组织批次检测：读取日志、执行规则、聚合事件并保存命中和证据。 */
@Service
public class DetectionService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DetectionService.class);
    private final DetectionMapper mapper;
    private final DetectionEngine engine;
    public DetectionService(DetectionMapper mapper, DetectionEngine engine) { this.mapper = mapper; this.engine = engine; }

    @Transactional
    public Map<String, Object> detect(long batchId) {
        // 锁定并校验待检测批次。
        if (mapper.lockBatch(batchId) == null) throw new BizException(404, "导入批次不存在");
        if (mapper.detectedCount(batchId) > 0) throw new BizException(409, "该批次已经检测，不能重复检测");
        long readStart = System.nanoTime();
        List<Log> logs = mapper.logs(batchId);
        long readNanos = System.nanoTime() - readStart;
        long engineStart = System.nanoTime();
        // 对批次日志执行规则检测，生成命中记录。
        List<Hit> hits = engine.detect(logs);
        long engineNanos = System.nanoTime() - engineStart;
        long setupStart = System.nanoTime();
        // 登记检测规则的元数据。
        for (String code : List.of("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "R10", "R11", "R12", "R13", "R14", "INJECT")) {
            mapper.registerRule(code);
        }
        long setupNanos = System.nanoTime() - setupStart;
        long aggregationStart = System.nanoTime(), eventWriteNanos = 0, hitWriteNanos = 0, evidenceWriteNanos = 0;
        int count = 0;
        List<DetectionMapper.Evidence> evidenceBatch = new ArrayList<>(500);
        // 按 IP 和十分钟窗口聚合命中，保存事件及证据。
        for (int i = 0; i < hits.size();) {
            Hit first = hits.get(i);
            int end = i + 1;
            while (end < hits.size() && hits.get(end).log().ipHash().equals(first.log().ipHash()) && Duration.between(first.log().time(), hits.get(end).log().time()).compareTo(Duration.ofMinutes(10)) <= 0) end++;
            List<Hit> group = hits.subList(i, end);
            String severity = group.stream().map(Hit::severity).max(Comparator.comparingInt(DetectionService::rank)).orElse("LOW");
            double risk = Math.min(100, rank(severity) * 20 + Math.min(20, group.size()));
            long eventStart = System.nanoTime();
            Map<String, Object> eventRow = new HashMap<>();
            eventRow.put("ipHash", first.log().ipHash()); eventRow.put("ipMask", first.log().ipMask());
            eventRow.put("windowStart", first.log().time()); eventRow.put("windowEnd", group.get(group.size() - 1).log().time());
            eventRow.put("risk", risk); eventRow.put("severity", severity);
            mapper.insertEvent(eventRow);
            long eventId = generatedId(eventRow);
            eventWriteNanos += System.nanoTime() - eventStart;
            for (Hit hit : group) {
                long hitStart = System.nanoTime();
                Map<String, Object> hitRow = new HashMap<>();
                hitRow.put("logId", hit.log().id()); hitRow.put("rule", hit.rule()); hitRow.put("field", hit.field());
                hitRow.put("evidence", hit.evidence()); hitRow.put("severity", hit.severity()); hitRow.put("time", hit.log().time());
                mapper.insertHit(hitRow);
                long hitId = generatedId(hitRow);
                hitWriteNanos += System.nanoTime() - hitStart;
                evidenceBatch.add(new DetectionMapper.Evidence(eventId, hitId, hit == first ? 1 : 0));
                if (evidenceBatch.size() == 500) {
                    long evidenceStart = System.nanoTime();
                    mapper.insertEvidence(evidenceBatch);
                    evidenceWriteNanos += System.nanoTime() - evidenceStart;
                    evidenceBatch.clear();
                }
            }
            count++;
            i = end;
        }
        if (!evidenceBatch.isEmpty()) {
            long evidenceStart = System.nanoTime();
            mapper.insertEvidence(evidenceBatch);
            evidenceWriteNanos += System.nanoTime() - evidenceStart;
        }
        long aggregationNanos = System.nanoTime() - aggregationStart;
        // 标记批次检测完成。
        mapper.markDetected(batchId);
        LOG.info("detection_timing batchId={} logs={} hits={} events={} readMs={} engineMs={} setupMs={} aggregationTotalMs={} eventWriteMs={} hitWriteMs={} evidenceWriteMs={} (transaction commit excluded)",
                batchId, logs.size(), hits.size(), count, readNanos / 1_000_000, engineNanos / 1_000_000, setupNanos / 1_000_000,
                aggregationNanos / 1_000_000, eventWriteNanos / 1_000_000, hitWriteNanos / 1_000_000, evidenceWriteNanos / 1_000_000);
        return Map.of("batchId", batchId, "hitCount", hits.size(), "eventCount", count);
    }
    private static int rank(String severity) { return switch (severity) { case "CRITICAL" -> 4; case "HIGH" -> 3; case "MEDIUM" -> 2; default -> 1; }; }
    private static long generatedId(Map<String, Object> row) {
        Number id = (Number) row.get("id");
        if (id == null) throw new IllegalStateException("无法获取生成的 ID");
        return id.longValue();
    }
    private static Map<String, Object> formatEvent(Map<String, Object> event) {
        for (String key : List.of("clientIpMask", "riskScore", "severity", "status")) event.putIfAbsent(key, null);
        String codes = (String) event.remove("ruleCodesRaw");
        List<String> rules = codes == null ? List.of() : Arrays.asList(codes.split(","));
        event.put("ruleCodes", rules); event.put("aiInjectionDetected", rules.contains("INJECT"));
        return event;
    }
    public List<Map<String, Object>> events() { return events(null); }
    public List<Map<String, Object>> events(Long batchId) { return mapper.events(batchId).stream().map(DetectionService::formatEvent).toList(); }
    public Map<String, Object> event(long id) {
        List<Map<String, Object>> events = mapper.event(id);
        if (events.isEmpty()) throw new BizException(404, "事件不存在");
        Map<String, Object> event = formatEvent(events.get(0));
        List<Map<String, Object>> evidence = mapper.evidence(id);
        evidence.forEach(row -> {
            for (String key : List.of("ruleCode", "matchedField", "evidenceSnippet", "severity", "rawLineMasked")) row.putIfAbsent(key, null);
        });
        event.put("evidence", evidence);
        return event;
    }
}
