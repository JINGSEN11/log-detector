package com.csys.logdetector.detection.service;


import com.csys.logdetector.common.BizException;
import com.csys.logdetector.detection.mapper.DashboardMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 整理仪表盘统计数据，并查询指定日志前后的上下文。 */
@Service
public class DashboardService {
    private final DashboardMapper mapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DashboardService(DashboardMapper mapper) { this(mapper, Clock.system(ZoneId.of("Asia/Shanghai"))); }
    public DashboardService(DashboardMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }

    /** 汇总事件、日志、研判数量和最近 24 小时的事件趋势。
     * Event time is the recorded window start, in the database's documented UTC+8 time zone. */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        LocalDateTime hour = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime from = hour.minusHours(23);
        LocalDateTime until = hour.plusHours(1);
        Map<LocalDateTime, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) counts.put(from.plusHours(i), 0L);
        // Query the last 24 buckets only; grouping in Java avoids database-specific timezone conversions.
        for (LocalDateTime time : mapper.eventWindowStarts(from, until)) {
            LocalDateTime bucket = time.truncatedTo(ChronoUnit.HOURS);
            counts.computeIfPresent(bucket, (key, count) -> count + 1);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        counts.forEach((time, count) -> trend.add(Map.of("hour", time, "eventCount", count)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventCount", mapper.eventCount());
        out.put("highSeverityEventCount", mapper.highSeverityEventCount());
        out.put("logCount", mapper.logCount());
        // Each persisted event analysis counts once, including rule fallback results.
        out.put("aiAnalysisCount", mapper.aiAnalysisCount());
        out.put("trend24h", trend);
        out.put("severityDistribution", mapper.severityDistribution());
        out.put("topSources", mapper.topSources());
        List<Map<String, Object>> latest = mapper.latestEvents();
        latest.forEach(row -> {
            for (String key : List.of("clientIpMask", "severity", "riskScore", "status")) row.putIfAbsent(key, null);
        });
        out.put("latestEvents", latest);
        return out;
    }

    /** 查询指定日志及其前后的脱敏日志。
     * Only persisted, successfully parsed and masked lines from the same batch are exposed. */
    @Transactional(readOnly = true)
    public Map<String, Object> context(long logId, int before, int after) {
        if (before < 0 || before > 20 || after < 0 || after > 20) throw new BizException(400, "上下文范围必须在 0 到 20 之间");
        List<Map<String, Object>> targets = mapper.contextTarget(logId);
        if (targets.isEmpty()) throw new BizException(404, "日志不存在");
        long batchId = (Long) targets.get(0).get("batchId");
        int lineNo = (Integer) targets.get(0).get("lineNo");
        List<Map<String, Object>> lines = new ArrayList<>(mapper.contextBefore(batchId, lineNo, before, logId));
        Collections.reverse(lines);
        lines.addAll(mapper.contextCurrent(batchId, logId));
        lines.addAll(mapper.contextAfter(batchId, lineNo, after, logId));
        lines.forEach(line -> {
            line.putIfAbsent("rawLineMasked", null);
            line.put("isCurrent", ((Number) line.get("logId")).longValue() == logId);
        });
        return Map.of("batchId", batchId, "targetLogId", logId, "lines", lines);
    }
}
