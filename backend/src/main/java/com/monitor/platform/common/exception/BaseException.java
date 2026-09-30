package com.monitor.platform.common.exception;

/**
 * 业务异常基类。
 *
 * <p>所有需要由全局异常处理器识别并返回稳定错误码的异常，都应继承该类型。
 * {@code code} 是与展示文案解耦的稳定标识，调用方可据此做分支处理。</p>
 */
public abstract class BaseException extends RuntimeException {

    private final String code;

    /**
     * @param code    稳定业务错误码
     * @param message 错误说明
     */
    protected BaseException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @param code    稳定业务错误码
     * @param message 错误说明
     * @param cause   原始异常
     */
    protected BaseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}