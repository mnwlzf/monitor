package com.monitor.platform.common.exception;

public class ResourceNotFoundException extends BaseException {

    public ResourceNotFoundException(String code, String message) {
        super(code, message);
    }

    public ResourceNotFoundException(String resource, Object id) {
        super("RESOURCE_NOT_FOUND", resource + " 不存在: " + id);
    }
}