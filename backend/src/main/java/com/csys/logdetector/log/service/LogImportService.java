package com.csys.logdetector.log.service;

import com.csys.logdetector.common.BizException;
import com.csys.logdetector.security.SecurityUtils;
import com.csys.logdetector.log.dto.LogImportResult;
import com.csys.logdetector.log.parser.AccessLogParser;
import com.csys.logdetector.log.parser.JsonLineLogParser;
import com.csys.logdetector.log.parser.ParsedLogLine;
import com.csys.logdetector.log.parser.LogParser;
import com.csys.logdetector.log.parser.CustomAccessLogParser;
import com.csys.logdetector.log.sanitizer.IpPseudonymizer;
import com.csys.logdetector.log.sanitizer.LogSanitizer;
import com.csys.logdetector.log.mapper.LogImportMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class LogImportService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_LINES = 30_000;
    private static final int RESPONSE_PREVIEW_LIMIT = 100;
    private static final int MAX_LINE_LENGTH = 8192;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final java.util.Map<String, LogParser> parsers;
    private final LogSanitizer sanitizer;
    private final IpPseudonymizer pseudonymizer;
    private final LogImportMapper mapper;

    public LogImportService(AccessLogParser accessLogParser, JsonLineLogParser jsonLineLogParser,
                            LogSanitizer sanitizer, IpPseudonymizer pseudonymizer, LogImportMapper mapper) {
        this(List.of(accessLogParser, jsonLineLogParser, new CustomAccessLogParser()), sanitizer, pseudonymizer, mapper);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LogImportService(List<LogParser> parserList, LogSanitizer sanitizer,
                            IpPseudonymizer pseudonymizer, LogImportMapper mapper) {
        java.util.Map<String, LogParser> registered = new java.util.LinkedHashMap<>();
        for (LogParser parser : parserList) {
            String id = parser.formatId();
            if (id == null || id.isBlank() || id.equals("auto") || registered.putIfAbsent(id, parser) != null)
                throw new IllegalStateException("日志解析器格式标识无效或重复: " + id);
        }
        this.parsers = java.util.Map.copyOf(registered);
        this.sanitizer = sanitizer;
        this.pseudonymizer = pseudonymizer;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public LogImportResult.Page getBatchEntries(long batchId, int page, int pageSize) {
        if (batchId <= 0) throw new BizException("批次 ID 必须大于 0");
        if (page < 1) throw new BizException("页码必须大于 0");
        if (pageSize < 1 || pageSize > 1000) throw new BizException("每页条数必须在 1 到 1000 之间");
        if (!mapper.batchExists(batchId)) throw new BizException(404, "导入批次不存在");
        long total = mapper.countSuccessfulEntries(batchId);
        long offset = (long) (page - 1) * pageSize;
        List<LogImportResult.SanitizedLogPreview> items = offset >= total ? List.of()
                : mapper.pageSuccessfulEntries(batchId, pageSize, offset);
        return new LogImportResult.Page(batchId, page, pageSize, total, items);
    }

    @Transactional
    public LogImportResult importFile(MultipartFile file) {
        validateFile(file);
        return importContent(file.getOriginalFilename(), readBytes(file), "UPLOAD", null, "auto");
    }

    @Transactional
    public LogImportResult importFile(MultipartFile file, String format) {
        validateFile(file);
        return importContent(file.getOriginalFilename(), readBytes(file), "UPLOAD", null, format);
    }

    @Transactional
    public LogImportResult importGenerated(String filename, byte[] bytes, String scenarioCode) {
        if (bytes == null || bytes.length == 0) throw new BizException("生成场景内容为空");
        if (bytes.length > MAX_BYTES) throw new BizException(413, "生成日志超过 10 MB 限制");
        return importContent(filename, bytes, "GENERATOR", scenarioCode, "auto");
    }

    private LogImportResult importContent(String originalFilename, byte[] bytes, String sourceType, String scenarioCode, String format) {
        if (bytes == null || bytes.length == 0) throw new BizException("请选择非空日志文件");
        if (bytes.length > MAX_BYTES) throw new BizException(413, "文件超过 10 MB 限制");
        if (sourceType.equals("UPLOAD")) {
            for (byte value : bytes) {
                if (value == 0) throw new BizException("检测到二进制文件（包含 NUL 字节），请上传 UTF-8 文本日志");
            }
        }
        String sha256 = sha256(bytes);
        Integer duplicate = mapper.countBySha256(sha256);
        if (duplicate != null && duplicate > 0) {
            throw new BizException(409, "该文件内容已导入，请勿重复上传");
        }
        String filename = safeFilename(originalFilename == null ? "generated.log" : originalFilename).toLowerCase(Locale.ROOT);
        if (sourceType.equals("UPLOAD") && !(filename.endsWith(".log") || filename.endsWith(".txt")
                || filename.endsWith(".json") || filename.endsWith(".jsonl"))) {
            throw new BizException("仅支持 .log、.txt、.json、.jsonl 文件");
        }
        if (!filename.endsWith(".log") && !filename.endsWith(".txt") && !filename.endsWith(".json") && !filename.endsWith(".jsonl")) {
            throw new BizException("生成日志文件扩展名无效");
        }
        String selected = format == null || format.isBlank() || format.equals("auto")
                ? (filename.endsWith(".json") || filename.endsWith(".jsonl") ? "jsonl" : "access") : format;
        LogParser parser = parsers.get(selected);
        if (parser == null) throw new BizException(400, "不支持的日志格式");
        String content = decodeUtf8(bytes);
        if (content.startsWith("﻿")) content = content.substring(1);// 移除 BOM 头, Bom 头为 0xEF 0xBB 0xBF, 作用是标识文件编码为 UTF-8, 但不参与解析
        String[] lines = content.split("\\r\\n|\\n|\\r", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            lines = java.util.Arrays.copyOf(lines, lines.length - 1);
        }
        if (lines.length > MAX_LINES) throw new BizException(413, "日志行数超过 30000 行限制");

        List<ParsedLogLine> parsedRows = new ArrayList<>(); //跳过无法解析的行
        int skipped = 0;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.length() > MAX_LINE_LENGTH || line.isEmpty()) {
                skipped++;
                continue;
            }
            var result = parser.parse(line, index + 1);
            if (result.isPresent()) parsedRows.add(result.get());
            else skipped++;
        }

        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        Long actorId = authentication != null && authentication.getPrincipal() instanceof com.csys.logdetector.security.LoginUser user
                ? user.id() : (sourceType.equals("UPLOAD") ? SecurityUtils.currentUser().id() : null);
        if (parsedRows.isEmpty()) throw new BizException("没有可解析的日志行，未创建导入批次");
        String batchStatus = skipped == 0 ? "SUCCESS" : "PARTIAL"; // 有跳过的行，状态为 PARTIAL, 部分导入成功
        Long batchId = mapper.insertBatch(actorId, sourceType,
                sourceType.equals("UPLOAD") ? sanitizer.sanitize(safeFilename(filename)) : null, sha256, scenarioCode,
                batchStatus, lines.length, parsedRows.size(), skipped);
        mapper.insertAudit(actorId, batchId, sourceType, parsedRows.size(), skipped);

        List<Object[]> rows = new ArrayList<>(parsedRows.size());
        List<LogImportResult.SanitizedLogPreview> responsePreview = new ArrayList<>();
        for (ParsedLogLine row : parsedRows) {
            String ipToken = pseudonymizer.pseudonymize(row.sourceIp());
            String maskedIp = sanitizer.maskIpv4(row.sourceIp());
            String[] requestTarget = splitRequestTarget(row.path());
            String path = sanitizer.sanitize(requestTarget[0]);
            String query = sanitizer.sanitize(requestTarget[1]);

            if (!selected.equals("access") && !selected.equals("jsonl")) {//request target 中包含特殊字符, 无法正常解析
                if (!requestTarget[0].matches("/[A-Za-z0-9._~/-]*")) path = "[REDACTED_CUSTOM_PATH]";// 路径中包含特殊字符, 无法正常解析
                if (!requestTarget[1].isEmpty()) query = "[REDACTED_CUSTOM_QUERY]";// 查询参数中包含特殊字符, 无法正常解析
            }
            String referer = sanitizer.sanitize(row.referer());
            String userAgent = sanitizer.sanitize(row.userAgent());
            String method = sanitizer.sanitize(row.method());
            String rawLine = switch (selected) {
                case "jsonl" -> sanitizer.sanitizeJson(row.rawLine());
                case "access" -> sanitizer.sanitize(row.rawLine());
                default -> "[REDACTED_CUSTOM_LOG]";
            };
            rows.add(new Object[]{batchId, row.lineNumber(), java.time.LocalDateTime.ofInstant(row.timestamp(), java.time.ZoneId.of("Asia/Shanghai")), ipToken, maskedIp,
                    method, path, query, row.status(), row.bytes(), referer, userAgent, rawLine, "SUCCESS"});
            if (responsePreview.size() < RESPONSE_PREVIEW_LIMIT) {
                responsePreview.add(new LogImportResult.SanitizedLogPreview(row.lineNumber(), ipToken, maskedIp,
                        TIME_FORMAT.format(row.timestamp().atOffset(ZoneOffset.UTC)), method, path, row.status(),
                        referer, userAgent, rawLine));
            }
        }
        mapper.insertEntries(rows);
        return new LogImportResult(batchId, sha256, batchStatus, lines.length, parsedRows.size(), skipped, List.copyOf(responsePreview));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("请选择非空日志文件");
        if (file.getSize() > MAX_BYTES) throw new BizException(413, "文件超过 10 MB 限制");
        String name = file.getOriginalFilename();
        if (!StringUtils.hasText(name)) throw new BizException("缺少文件名");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".log") || lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".jsonl"))) {
            throw new BizException("仅支持 .log、.txt、.json、.jsonl 文件");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            for (byte value : bytes) {
                if (value == 0) throw new BizException("检测到二进制文件（包含 NUL 字节），请上传 UTF-8 文本日志");
            }
            return bytes;
        } catch (IOException ex) {
            throw new BizException("读取上传文件失败");
        }
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new BizException("文件不是有效的 UTF-8 文本");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String[] splitRequestTarget(String requestTarget) {
        int queryIndex = requestTarget.indexOf('?');
        if (queryIndex < 0) return new String[]{requestTarget, ""};
        return new String[]{requestTarget.substring(0, queryIndex), requestTarget.substring(queryIndex + 1)};
    }

    private String safeFilename(String name) {
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
}
