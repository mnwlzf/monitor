package com.monitor.platform.common.exception;

import com.monitor.platform.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 资源不存在 -> 404 */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found: {} {} - {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return response(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage(), Map.of());
    }

    /** 业务异常 -> 422 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        log.warn("Business error: {} {} - [{}] {}", request.getMethod(), request.getRequestURI(), ex.getCode(), ex.getMessage());
        return response(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), Map.of());
    }

    /** @RequestBody 参数校验失败 -> 400 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        f -> f.getField(),
                        f -> f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage(),
                        (left, right) -> left
                ));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败", details);
    }

    /** 表单绑定校验失败 -> 400 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBind(BindException ex) {
        Map<String, String> details = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        f -> f.getField(),
                        f -> f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage(),
                        (left, right) -> left
                ));
        return response(HttpStatus.BAD_REQUEST, "BIND_ERROR", "参数绑定失败", details);
    }

    /** 方法级参数校验失败（@Validated 在类上） -> 400 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex) {
        Map<String, String> details = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> lastNode(v),
                        ConstraintViolation::getMessage,
                        (left, right) -> left
                ));
        return response(HttpStatus.BAD_REQUEST, "CONSTRAINT_VIOLATION", "参数约束校验失败", details);
    }

    /** 缺少请求参数 -> 400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return response(HttpStatus.BAD_REQUEST, "MISSING_PARAM",
                "缺少必要参数: " + ex.getParameterName(), Map.of());
    }

    /** 参数类型不匹配 -> 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return response(HttpStatus.BAD_REQUEST, "TYPE_MISMATCH",
                "参数类型错误: " + ex.getName(), Map.of());
    }

    /** 请求体 JSON 解析失败 -> 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Request body not readable: {}", ex.getMessage());
        return response(HttpStatus.BAD_REQUEST, "MESSAGE_NOT_READABLE", "请求体格式错误或无法解析", Map.of());
    }

    /** 请求方法不支持 -> 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "不支持的请求方法: " + ex.getMethod(), Map.of());
    }

    /** 404 无匹配 handler */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "NO_HANDLER",
                "接口不存在: " + ex.getRequestURL(), Map.of());
    }

    /** 兜底 -> 500，务必打印堆栈 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: {} {}", request.getMethod(), request.getRequestURI(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误", Map.of());
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message, Map<String, String> details) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, currentRequestId(), Instant.now(), details));
    }

    private String currentRequestId() {
        String requestId = MDC.get("requestId");
        return requestId != null ? requestId : UUID.randomUUID().toString();
    }

    private String lastNode(ConstraintViolation<?> v) {
        String path = v.getPropertyPath().toString();
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}