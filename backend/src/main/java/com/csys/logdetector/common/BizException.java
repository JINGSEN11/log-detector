package com.csys.logdetector.common;

/**
 * 自定义异常类: 业务异常
 * 业务异常：由全局异常处理器统一转成 {@link Result}，避免到处写 try-catch。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
