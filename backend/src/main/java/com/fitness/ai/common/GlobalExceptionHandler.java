package com.fitness.ai.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器：把各类异常统一转换为 {@link Result}。
 *
 * <p>状态码约定：业务异常返回 HTTP 200 + 业务错误码（由前端统一拦截器判定）；
 * 参数类异常返回 HTTP 400；未预期异常返回 HTTP 500 且不向外暴露堆栈细节。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：业务校验或 AI 安全护栏主动抛出。 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException ex) {
        log.warn("业务异常: code={}, message={}", ex.getErrorCode().getCode(), ex.getMessage());
        return Result.fail(ex.getErrorCode(), ex.getMessage());
    }

    /** {@code @RequestBody} 上的 {@code @Valid} 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        return Result.fail(ErrorCode.PARAM_VALIDATION_FAILED, joinFieldErrors(ex.getBindingResult().getFieldErrors()));
    }

    /** 表单 / 查询参数对象绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException ex) {
        return Result.fail(ErrorCode.PARAM_VALIDATION_FAILED, joinFieldErrors(ex.getBindingResult().getFieldErrors()));
    }

    /** 方法级参数校验失败（Spring 6.1+ 的 {@code @Validated} 控制器参数）。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        // Spring 6.2 起 getAllValidationResults() 已废弃，改用 getParameterValidationResults()
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(error -> error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(ErrorCode.PARAM_VALIDATION_FAILED, message.isBlank() ? ErrorCode.PARAM_VALIDATION_FAILED.getMessage() : message);
    }

    /** Bean Validation 约束校验失败（如 Service 层手写校验）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(violation -> violation.getMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(ErrorCode.PARAM_VALIDATION_FAILED, message.isBlank() ? ErrorCode.PARAM_VALIDATION_FAILED.getMessage() : message);
    }

    /** 缺少必填请求参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException ex) {
        return Result.fail(ErrorCode.BAD_REQUEST, "缺少必填参数: " + ex.getParameterName());
    }

    /** 请求体格式非法（JSON 解析失败）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, "请求体格式非法，无法解析");
    }

    /** 兜底：未预期的系统异常。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception ex) {
        log.error("系统异常", ex);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    private String joinFieldErrors(List<FieldError> fieldErrors) {
        if (fieldErrors.isEmpty()) {
            return ErrorCode.PARAM_VALIDATION_FAILED.getMessage();
        }
        return fieldErrors.stream()
                .map(error -> error.getField() + ": "
                        + (error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage()))
                .collect(Collectors.joining("; "));
    }
}