package com.monitor.platform.common.exception;

/**
 * 资源不存在异常。
 *
 * <p>由全局异常处理器转换为 HTTP 404。</p>
 */
public class ResourceNotFoundException extends BaseException {

    private static final String DEFAULT_CODE = "RESOURCE_NOT_FOUND";

    /**
     * 使用指定错误码创建资源不存在异常。
     */
    public ResourceNotFoundException(String code, String message) {
        super(code, message);
    }

    /**
     * @param resource 资源名称，例如 account
     * @param id       未命中的资源标识
     */
    public ResourceNotFoundException(String resource, Object id) {
        super(DEFAULT_CODE, resource + " 不存在: " + id);
    }
}