package com.csys.logdetector.detection.report;


import com.csys.logdetector.log.sanitizer.LogSanitizer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** Render untrusted data as text only; never load links or other external resources. 将事件报告内容排版为 PDF，供事件报告下载使用。 */
@Component
public class EventPdfRenderer {
    private final LogSanitizer sanitizer;
    public EventPdfRenderer(LogSanitizer sanitizer) { this.sanitizer = sanitizer; }

    public byte[] render(long id, Map<String, Object> event, Map<String, Object> analysis) {
        try (PDDocument doc = new PDDocument();
             InputStream fontFile = EventPdfRenderer.class.getResourceAsStream("/fonts/ttf/NotoSansSC/NotoSansSC-Regular.ttf")) {
            if (fontFile == null) throw new IllegalStateException("PDF 中文字体缺失");
            PDType0Font font = PDType0Font.load(doc, fontFile, true);
            try (Writer w = new Writer(doc, font)) {
                w.heading("事件报告 #" + id);
                w.heading("事件概览");
                for (String[] f : new String[][]{{"来源 IP（掩码）", "clientIpMask"}, {"开始时间", "windowStart"}, {"结束时间", "windowEnd"}, {"严重度", "severity"}, {"风险分", "riskScore"}, {"状态", "status"}, {"命中数", "hitCount"}, {"日志数", "logCount"}})
                    w.paragraph(f[0] + "：" + safe(event.get(f[1])));
                w.heading("最新研判");
                if (analysis == null) w.paragraph("暂无研判记录。");
                else {
                    w.paragraph("研判时间：" + safe(analysis.get("createdAt")));
                    w.paragraph("研判来源：" + ("MODEL".equals(analysis.get("source")) ? "模型研判" : "规则降级"));
                }
                w.heading("攻击链");
                Object chain = analysis == null ? null : analysis.get("attackChain");
                if (!(chain instanceof List<?> steps) || steps.isEmpty()) w.paragraph(analysis == null ? "暂无研判记录。" : "暂无攻击链。");
                else for (Object item : steps) {
                    if (!(item instanceof Map<?, ?> step)) throw new IllegalStateException("研判攻击链格式无效");
                    w.paragraph("阶段：" + safe(step.get("stage")) + "；描述：" + safe(step.get("description")) + "；引用日志 ID：" + safe(step.get("logIds")));
                }
                w.heading("处置建议");
                Object rec = analysis == null ? null : analysis.get("recommendations");
                if (!(rec instanceof List<?> items) || items.isEmpty()) w.paragraph(analysis == null ? "暂无研判记录。" : "暂无处置建议。");
                else for (Object item : items) w.paragraph("• " + safe(item));
                w.heading("证据表");
                @SuppressWarnings("unchecked") List<Map<String, Object>> evidence = (List<Map<String, Object>>) event.get("evidence");
                Map<String, Long> rules = new TreeMap<>();
                w.tableHeader(new String[]{"日志 ID", "规则", "命中字段", "证据片段", "脱敏原始行"}, new float[]{55, 50, 70, 130, 218});
                for (Map<String, Object> e : evidence) {
                    rules.merge(String.valueOf(e.get("ruleCode")), 1L, Long::sum);
                    w.tableRow(new String[]{safe(e.get("logId")), safe(e.get("ruleCode")), safe(e.get("matchedField")), safe(e.get("evidenceSnippet")), safe(e.get("rawLineMasked"))});
                }
                w.heading("规则统计");
                w.tableHeader(new String[]{"规则", "命中数"}, new float[]{260, 263});
                for (var entry : rules.entrySet()) w.tableRow(new String[]{safe(entry.getKey()), String.valueOf(entry.getValue())});
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(); doc.save(out); return out.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException("生成 PDF 报告失败", ex); }
    }
    private String safe(Object value) { return sanitizer.sanitize(value == null ? "" : String.valueOf(value)); }

    private static final class Writer implements AutoCloseable {
        private static final float LEFT = 36, TOP = 806, BOTTOM = 36, LINE = 12, SIZE = 9;
        private final PDDocument doc; private final PDType0Font font;
        private PDPageContentStream stream; private float y; private String[] headers; private float[] widths;
        Writer(PDDocument doc, PDType0Font font) throws IOException { this.doc = doc; this.font = font; newPage(false); }
        private void newPage(boolean repeatHeader) throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4); doc.addPage(page); stream = new PDPageContentStream(doc, page); y = TOP;
            if (repeatHeader && headers != null) drawRow(headers, false);
        }
        private String printable(String text) {
            StringBuilder out = new StringBuilder();
            text.codePoints().forEach(cp -> {
                if (Character.isISOControl(cp)) { out.append(' '); return; }
                String ch = new String(Character.toChars(cp));
                try { font.encode(ch); out.append(ch); } catch (Exception ex) { out.append('?'); }
            });
            return out.toString();
        }
        private List<String> wrap(String input, float width) throws IOException {
            String s = printable(input); List<String> result = new ArrayList<>(); StringBuilder line = new StringBuilder(); float used = 0;
            for (int i = 0; i < s.length();) {
                int cp = s.codePointAt(i); i += Character.charCount(cp); String ch = new String(Character.toChars(cp));
                float advance = font.getStringWidth(ch) / 1000 * SIZE;
                if (used + advance > width && !line.isEmpty()) { result.add(line.toString()); line.setLength(0); used = 0; }
                line.append(ch); used += advance;
            }
            result.add(line.toString()); return result;
        }
        private void text(float x, float baseline, String value, float size) throws IOException {
            stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(x, baseline); stream.showText(value); stream.endText();
        }
        void heading(String value) throws IOException {
            headers = null; widths = null; if (y < BOTTOM + 38) newPage(false);
            y -= 18; text(LEFT, y, printable(value), 13); y -= 10;
        }
        void paragraph(String value) throws IOException {
            for (String part : wrap(value, 520)) { if (y < BOTTOM + LINE) newPage(false); text(LEFT, y, part, SIZE); y -= LINE; }
            y -= 4;
        }
        void tableHeader(String[] labels, float[] columnWidths) throws IOException {
            headers = labels; widths = columnWidths;
            if (y < BOTTOM + 35) newPage(false);
            drawRow(headers, false);
        }
        void tableRow(String[] cells) throws IOException { drawRow(cells, true); }
        private void drawRow(String[] cells, boolean mayRepeatHeader) throws IOException {
            List<List<String>> lines = new ArrayList<>(); int max = 1;
            for (int i = 0; i < cells.length; i++) { var wrapped = wrap(cells[i], widths[i] - 8); lines.add(wrapped); max = Math.max(max, wrapped.size()); }
            int offset = 0;
            while (offset < max) {
                int available = (int)((y - BOTTOM - 6) / LINE);
                if (available < 1) { newPage(mayRepeatHeader); available = (int)((y - BOTTOM - 6) / LINE); }
                int count = Math.min(available, max - offset); float height = count * LINE + 6; float x = LEFT;
                for (int i = 0; i < cells.length; i++) {
                    stream.addRect(x, y - height, widths[i], height); stream.stroke();
                    for (int j = 0; j < count && offset + j < lines.get(i).size(); j++) text(x + 4, y - 10 - j * LINE, lines.get(i).get(offset + j), SIZE);
                    x += widths[i];
                }
                y -= height; offset += count;
            }
        }
        public void close() throws IOException { if (stream != null) stream.close(); }
    }
}
