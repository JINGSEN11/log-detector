package com.csys.logdetector.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 生成和管理 追踪id编号
 * 1.生成编号：用 UUID 创建一个随机的 traceId。
 * 2.暂存编号：放进 MDC，供处理当前请求的代码读取。
 * 3.放进响应头：前端可以从 X-Trace-Id 响应头中拿到编号。
 * 4.继续处理请求：`chain.doFilter(...)` 让请求继续进入后面的过滤器和 Controller。
 * 5.处理结束后清理或恢复：避免线程被复用时，下一次请求错误地沿用这个编号。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";
    private static final String ATTRIBUTE = TraceFilter.class.getName() + ".id";

    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        String previous = MDC.get(TraceContext.KEY);
        MDC.put(TraceContext.KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) MDC.remove(TraceContext.KEY);
            else MDC.put(TraceContext.KEY, previous);
        }
    }
}
