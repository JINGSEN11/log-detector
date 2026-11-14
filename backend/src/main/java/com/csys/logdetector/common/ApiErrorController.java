package com.csys.logdetector.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兜底错误处理入口
 * 当服务器将错误分发到 /error 时，它会：
 * 1. 读取错误状态码，例如 404。
 * 2. 根据状态码选择提示文字，例如“资源不存在”。
 * 3. 用 Result.fail(code, message) 组织响应，返回给前端。
 * 前端收到响应
 *     ↓
 * 前端决定是否弹窗、显示在哪里
 */
@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("/error")
    public ResponseEntity<Result<Void>> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = value instanceof Integer ? (Integer) value : 500;
        HttpStatus status = HttpStatus.resolve(code);
        if (status == null || !status.isError()) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = 500;
        }
        String message = switch (code) {
            case 400 -> "请求参数错误";
            case 401 -> "未登录或登录已过期";
            case 403 -> "没有访问权限";
            case 404 -> "资源不存在";
            case 405 -> "请求方法不支持";
            case 413 -> "上传文件过大";
            case 415 -> "请求内容类型不支持";
            case 429 -> "请求过于频繁，请稍后重试";
            default -> code >= 500 ? "服务器内部错误，请稍后重试" : "请求失败";
        };
        return ResponseEntity.status(status).body(Result.fail(code, message));
    }
}
