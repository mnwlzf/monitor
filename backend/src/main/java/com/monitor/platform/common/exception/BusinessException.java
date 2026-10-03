package com.monitor.platform.common.exception;

/**
 * 通用业务异常。
 *
 * <p>适用于无法归入更具体异常类型、但需要返回 422 业务错误的场景。</p>
 */
public class BusinessException extends BaseException {

    private static final String DEFAULT_CODE = "BUSINESS_ERROR";

    /**
     * 使用指定错误码创建业务异常。
     */
    public BusinessException(String code, String message) {
        super(code, message);
    }

    /**
     * 使用指定错误码和根因创建业务异常。
     */
    public BusinessException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }

    /**
     * 使用默认错误码创建业务异常。
     */
    public static BusinessException of(String message) {
        return new BusinessException(DEFAULT_CODE, message);
    }
}