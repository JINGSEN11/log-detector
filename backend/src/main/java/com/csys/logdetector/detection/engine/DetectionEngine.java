package com.csys.logdetector.detection.engine;


import com.google.re2j.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** 对脱敏日志执行特征与行为规则，返回命中规则的日志及证据。 */
@Component
public class DetectionEngine {
    public record Log(long id, String ipHash, String ipMask, LocalDateTime time, String method,
                      String path, String query, int status, String userAgent, String raw) {}
    public record Hit(Log log, String rule, String field, String evidence, String severity) {}
    private record Rule(String code, String field, String severity, Pattern pattern) {}
    private static Rule rule(String code, String field, String severity, String regex) {
        return new Rule(code, field, severity, Pattern.compile("(?i)" + regex));
    }
    private static final List<Rule> RULES = List.of(
        rule("R1", "url", "HIGH", "union\\s+(?:all\\s+)?select|(?:or|and)\\s+['\\\"0-9]+\\s*=\\s*['\\\"0-9]+|(?:sleep|benchmark)\\s*\\(|information_schema|;\\s*(?:select|drop|insert|update|delete)\\b"),//
        rule("R2", "url", "HIGH", "<\\s*script\\b|\\bon(?:error|load|click|mouseover|focus)\\s*=|javascript\\s*:|document\\s*\\.\\s*cookie"),
        rule("R3", "url", "HIGH", "\\.\\.[/\\\\]|/etc/passwd|(?:^|[/\\\\])win\\.ini"),
        rule("R4", "user_agent", "MEDIUM", "sqlmap|nikto|nmap|nuclei|gobuster|hydra"),
        rule("R7", "url", "HIGH", "(?:;|\\|\\||&&|[\\r\\n])\\s*(?:cat|id|whoami|ls|wget|curl|bash|sh|powershell|cmd)\\b|\\$\\([^)]{1,200}\\)|`[^`]{1,200}`"),
        rule("R8", "url", "HIGH", "(?:https?://(?:169\\.254\\.169\\.(?:254|\\*\\*\\*)|127\\.(?:[0-9]{1,3}\\.){2}(?:[0-9]{1,3}|\\*\\*\\*)|10\\.(?:[0-9]{1,3}\\.){2}(?:[0-9]{1,3}|\\*\\*\\*)|192\\.168\\.[0-9]{1,3}\\.(?:[0-9]{1,3}|\\*\\*\\*)|172\\.(?:1[6-9]|2[0-9]|3[01])\\.[0-9]{1,3}\\.(?:[0-9]{1,3}|\\*\\*\\*)|localhost|\\[::1\\])(?::[0-9]+)?(?:[/?:#]|$))|(?:file|gopher)://"),
        rule("R9", "path", "MEDIUM", "(?:^|/)(?:\\.env(?:\\.[a-z0-9_-]+)?|\\.git|wp-config\\.php|actuator|[^/?]+\\.(?:bak|backup|sql|pem|key))(?:[/?#]|$)"),
        rule("R10", "url_or_user_agent", "CRITICAL", "(?:\\$\\{)?jndi\\s*:\\s*(?:ldap|ldaps|rmi|dns|iiop)\\s*:"),
        rule("R11", "path", "HIGH", "(?:^|/)(?:webshell|shell|c99|r57|b374k|cmd)\\.(?:php|jsp|jspx|asp|aspx)(?:$|[/?#])"),
        rule("R12", "method", "MEDIUM", "^(?:TRACE|CONNECT)$"),
        rule("R13", "query", "MEDIUM", "(?:^|[&;])(?:password|passwd|token|access[_-]?token|api[_-]?key|secret|authorization)=([^&;\\s]+)") ,
        rule("INJECT", "url_or_user_agent", "HIGH", "ignore\\s+(?:all\\s+)?(?:previous|prior)\\s+instructions|忽略.{0,20}(?:指令|规则)|(?:<\\|(?:system|assistant|user|im_start|im_end)\\|>)|(?:^|[\\s\\[<])system\\s*:")
    );
    private static final Pattern LOGIN = Pattern.compile("(?i)(?:^|/)(?:login|signin|sign-in|auth)(?:/|\\?|$)");
    private final int notFoundThreshold;
    private final int loginThreshold;
    private final int floodThreshold;
    public DetectionEngine(int notFoundThreshold, int loginThreshold) {
        this(notFoundThreshold, loginThreshold, 100);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DetectionEngine(@Value("${detection.threshold.404:12}") int notFoundThreshold,
                           @Value("${detection.threshold.login:8}") int loginThreshold,
                           @Value("${detection.threshold.flood:100}") int floodThreshold) {
        if (notFoundThreshold < 2 || loginThreshold < 2 || floodThreshold < 2) throw new IllegalArgumentException("检测阈值必须至少为 2");
        this.notFoundThreshold = notFoundThreshold;
        this.loginThreshold = loginThreshold;
        this.floodThreshold = floodThreshold;
    }
    public List<Hit> detect(List<Log> input) {
        List<Log> logs = new ArrayList<>(input);
        logs.sort(Comparator.comparing(Log::ipHash).thenComparing(Log::time).thenComparingLong(Log::id));
        List<Hit> hits = new ArrayList<>();
        // 逐条匹配特征规则并生成命中记录。
        for (Log log : logs) {
            // 处理当前日志
            String url = decode(nvl(log.path()) + (log.query() == null || log.query().isEmpty() ? "" : "?" + log.query()));
            String ua = decode(nvl(log.userAgent()));
            for (Rule r : RULES) {
                // 用当前规则检查当前日志
                String value = switch (r.field) { case "user_agent" -> ua; case "path" -> decode(nvl(log.path())); case "method" -> nvl(log.method()); case "query" -> decode(nvl(log.query())); case "url_or_user_agent" -> url + " " + ua; default -> url; };
                if (r.pattern.matcher(value).find()) {
                    String evidence = r.code.equals("INJECT") ? "[PROMPT_INJECTION_DETECTED]" : r.code.equals("R13") ? "URL 包含凭据参数（值已隐藏）" : switch (r.field) {
                        case "method" -> nvl(log.method());
                        case "user_agent" -> nvl(log.userAgent());
                        case "url_or_user_agent" -> nvl(log.path()) + (log.query() == null || log.query().isEmpty() ? "" : "?" + log.query()) + " " + nvl(log.userAgent());
                        case "path" -> nvl(log.path());
                        default -> nvl(log.path()) + (log.query() == null ? "" : "?" + log.query());
                    };
                    hits.add(new Hit(log, r.code, r.field, evidence.substring(0, Math.min(1024, evidence.length())), r.severity));
                }
            }
        }
        // 按 IP 分组检测高频访问和登录失败行为。
        int start = 0;
        while (start < logs.size()) {
            int end = start + 1;
            while (end < logs.size() && logs.get(end).ipHash().equals(logs.get(start).ipHash())) end++;
            List<Log> group = logs.subList(start, end);
            behavior(group, hits, "R5", notFoundThreshold, 10, l -> l.status() == 404);
            behavior(group, hits, "R6", loginThreshold, 5, l -> "POST".equalsIgnoreCase(l.method()) && (l.status() == 401 || l.status() == 403) && LOGIN.matcher(decode(nvl(l.path()))).find());
            behavior(group, hits, "R14", floodThreshold, 1, l -> true);
            start = end;
        }
        hits.sort(Comparator.comparing((Hit h) -> h.log().ipHash()).thenComparing(h -> h.log().time()).thenComparingLong(h -> h.log().id()).thenComparing(Hit::rule));
        return hits;
    }
    //找时间窗口
    private void behavior(List<Log> group, List<Hit> hits, String code, int threshold, int minutes, java.util.function.Predicate<Log> predicate) {
        List<Log> matches = group.stream().filter(predicate).toList();
        int[] diff = new int[matches.size() + 1];
        int left = 0;
        for (int right = 0; right < matches.size(); right++) {
            while (Duration.between(matches.get(left).time(), matches.get(right).time()).compareTo(Duration.ofMinutes(minutes)) > 0) left++;
            if (right - left + 1 >= threshold) { diff[left]++; diff[right + 1]--; }
        }
        int active = 0;
        for (int i = 0; i < matches.size(); i++) {
            active += diff[i];
            if (active > 0) {
                String field = code.equals("R14") ? "client_ip_hash" : "status_code";
                String evidence = switch (code) { case "R5" -> "404 高频窗口"; case "R6" -> "登录失败窗口"; default -> "单 IP 请求高频窗口"; };
                hits.add(new Hit(matches.get(i), code, field, evidence, "HIGH"));
            }
        }
    }
    //把null转换成空字符串
    private static String nvl(String s) { return s == null ? "" : s; }
    //解码日志中的 URL 编码内容, 如:q=%3Cscript%3E -> q=<script>
    private static String decode(String s) {
        s = s.replace('+', ' ');
        for (int i = 0; i < 4 && s.contains("%"); i++) {
            try { String next = URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); if (next.equals(s)) break; s = next; }
            catch (IllegalArgumentException e) { break; }
        }
        return s;
    }
}
