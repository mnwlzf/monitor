package com.monitor.platform.common.exception;

public class BusinessException extends BaseException {

    public BusinessException(String code, String message) {
        super(code, message);
    }

    public BusinessException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }

    /** 常用快捷构造 */
    public static BusinessException of(String message) {
        return new BusinessException("BUSINESS_ERROR", message);
    }
}