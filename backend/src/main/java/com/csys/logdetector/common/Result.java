package com.csys.logdetector.common;

/**
 * 统一响应体：{@code {code, message, data}}。
 *
 * <p>约定：{@code code == 0} 表示成功，其余为业务错误码（与 HTTP 状态码保持一致，便于排查）。</p>
 *
 * @param <T> 业务数据类型
 */
public class Result<T> {

    private int code; // 状态码
    private String message; //文字说明 "success", "用户名或密码错误"
    private T data; //用户信息, 表, null
    private String traceId; //请求追踪id 用于关联一次请求和服务器日志, 方便排查问题, 示例: "a1b2c3..."

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok() {
        return new Result<>(0, "ok", null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> result = new Result<>(code, message, null);
        result.traceId = TraceContext.currentId();
        return result;
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
