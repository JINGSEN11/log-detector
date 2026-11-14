package com.csys.logdetector.detection.service;

import com.csys.logdetector.detection.report.EventPdfRenderer;

import com.csys.logdetector.common.BizException;
import com.csys.logdetector.detection.mapper.EventWorkflowMapper;
import com.csys.logdetector.log.sanitizer.LogSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 处理事件状态和时间线，组织事件列表及单个事件报告的导出。 */
@Service
public class EventWorkflowService {
    private static final Set<String> STATUSES = Set.of("NEW", "INVESTIGATING", "CONFIRMED", "FALSE_POSITIVE", "CLOSED");
    private final EventWorkflowMapper mapper;
    private final DetectionService detection;
    private final LogSanitizer sanitizer;
    private final EventAnalysisPersistenceService analyses;
    private final EventPdfRenderer pdfRenderer;

    public EventWorkflowService(EventWorkflowMapper mapper, DetectionService detection, LogSanitizer sanitizer, EventAnalysisPersistenceService analyses) {
        this.mapper = mapper; this.detection = detection; this.sanitizer = sanitizer; this.analyses = analyses; this.pdfRenderer = new EventPdfRenderer(sanitizer);
    }

    /** 更新事件状态并记录状态变更和操作审计。 */
    @Transactional
    public Map<String, Object> updateStatus(long id, String status, String note, long actorId) {
        if (status == null || !STATUSES.contains(status)) throw new BizException(400, "事件状态无效");
        if (note == null || note.isBlank()) throw new BizException(400, "请填写备注");
        if (note.length() > 2000) throw new BizException(400, "备注不能超过 2000 字符");
        String safeNote = sanitizer.sanitize(note.trim());
        List<String> old = mapper.lockStatus(id);
        if (old.isEmpty()) throw new BizException(404, "事件不存在");
        mapper.updateStatus(id, status);
        mapper.insertStatusChange(id, actorId, old.get(0), status, safeNote);
        audit(actorId, "EVENT_STATUS_CHANGE", id);
        return detection.event(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> timeline(long id) {
        if (mapper.countEvents(id) == 0) throw new BizException(404, "事件不存在");
        List<Map<String, Object>> rows = mapper.timeline(id);
        rows.forEach(row -> {
            for (String key : List.of("id", "action", "oldStatus", "newStatus", "note", "createdAt", "actor")) row.putIfAbsent(key, null);
        });
        return rows;
    }

    /** 导出事件列表并记录导出操作。 */
    @Transactional
    public String csv(long actorId) { return csv(actorId, null); }

    @Transactional
    public String csv(long actorId, Long batchId) {
        StringBuilder out = new StringBuilder("\ufeff事件ID,来源IP,开始时间,结束时间,严重度,风险分,状态\r\n");
        mapper.forEachExportRow(batchId, context -> {
            Map<String, String> row = context.getResultObject();
            String[] fields = {"id", "clientIp", "start", "end", "severity", "risk", "status"};
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) out.append(',');
                out.append(csvCell(sanitizer.sanitize(row.get(fields[i]))));
            }
            out.append("\r\n");
        });
        audit(actorId, "EVENT_CSV_EXPORT", null);
        return out.toString();
    }

    public static String csvCell(String value) {
        String s = value == null ? "" : value;
        int i = 0;
        while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || Character.isISOControl(s.charAt(i)))) i++;
        if (i < s.length() && "=+-@".indexOf(s.charAt(i)) >= 0 || (!s.isEmpty() && (s.charAt(0) == '\t' || s.charAt(0) == '\r' || s.charAt(0) == '\n'))) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** 根据事件、研判和证据生成 Markdown 报告。 */
    @Transactional
    public String markdown(long id, long actorId) {
        Map<String, Object> event = detection.event(id);
        StringBuilder out = new StringBuilder("# 事件报告 #").append(id).append("\n\n## 事件概览\n\n");
        for (String[] field : new String[][]{{"来源 IP（掩码）", "clientIpMask"}, {"开始时间", "windowStart"}, {"结束时间", "windowEnd"}, {"严重度", "severity"}, {"风险分", "riskScore"}, {"状态", "status"}, {"命中数", "hitCount"}, {"日志数", "logCount"}})
            out.append("- ").append(field[0]).append("：").append(markdownCell(event.get(field[1]))).append('\n');
        Map<String, Object> analysis = analyses.latest(id);
        if (analysis != null) {
            out.append("\n## 最新研判\n\n- 研判时间：").append(markdownCell(analysis.get("createdAt")))
                    .append("\n- 研判来源：").append("MODEL".equals(analysis.get("source")) ? "模型研判" : "规则降级")
                    .append('\n');
        }
        out.append("\n## 攻击链\n\n");
        Object chain = analysis == null ? null : analysis.get("attackChain");
        if (!(chain instanceof List<?> steps) || steps.isEmpty()) {
            out.append(analysis == null ? "暂无研判记录。\n" : "暂无攻击链。\n");
        } else {
            for (Object item : steps) {
                if (!(item instanceof Map<?, ?> step)) throw new IllegalStateException("研判攻击链格式无效");
                out.append("- 阶段：").append(markdownCell(step.get("stage")))
                        .append("；描述：").append(markdownCell(step.get("description")))
                        .append("；引用日志 ID：");
                Object ids = step.get("logIds");
                if (ids instanceof List<?> logIds && !logIds.isEmpty()) {
                    out.append(String.join(", ", logIds.stream().map(this::markdownCell).toList()));
                } else out.append("无");
                out.append('\n');
            }
        }
        out.append("\n## 处置建议\n\n");
        Object recommendations = analysis == null ? null : analysis.get("recommendations");
        if (!(recommendations instanceof List<?> items) || items.isEmpty()) {
            out.append(analysis == null ? "暂无研判记录。\n" : "暂无处置建议。\n");
        } else {
            for (Object item : items) out.append("- ").append(markdownCell(item)).append('\n');
        }
        out.append("\n## 证据表\n\n| 日志 ID | 规则 | 命中字段 | 证据片段 | 脱敏原始行 |\n| --- | --- | --- | --- | --- |\n");
        @SuppressWarnings("unchecked") List<Map<String,Object>> evidence = (List<Map<String,Object>>) event.get("evidence");
        Map<String, Long> rules = new TreeMap<>();
        for (Map<String,Object> e : evidence) {
            rules.merge(String.valueOf(e.get("ruleCode")), 1L, Long::sum);
            out.append("| ").append(markdownCell(e.get("logId"))).append(" | ").append(markdownCell(e.get("ruleCode"))).append(" | ")
                    .append(markdownCell(e.get("matchedField"))).append(" | ").append(markdownCell(e.get("evidenceSnippet"))).append(" | ")
                    .append(markdownCell(e.get("rawLineMasked"))).append(" |\n");
        }
        out.append("\n## 规则统计\n\n| 规则 | 命中数 |\n| --- | ---: |\n");
        rules.forEach((rule, count) -> out.append("| ").append(markdownCell(rule)).append(" | ").append(count).append(" |\n"));
        audit(actorId, "EVENT_MD_EXPORT", id);
        return out.toString();
    }

    @Transactional
    public byte[] pdf(long id, long actorId) {
        byte[] report = pdfRenderer.render(id, detection.event(id), analyses.latest(id));
        audit(actorId, "EVENT_PDF_EXPORT", id);
        return report;
    }

    private String markdownCell(Object value) {
        String text = sanitizer.sanitize(value == null ? "" : value.toString());
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\\", "\\\\").replace("|", "\\|")
                .replace("[", "\\[").replace("]", "\\]").replace("`", "\\`").replace("!", "\\!")
                .replace("*", "\\*").replace("_", "\\_").replace("~", "\\~").replace("#", "\\#")
                .replace("\r", " ").replace("\n", " ");
    }
    private void audit(long actorId, String action, Long id) {
        mapper.audit(actorId, action, id == null ? null : String.valueOf(id), org.slf4j.MDC.get("traceId"));
    }
}
