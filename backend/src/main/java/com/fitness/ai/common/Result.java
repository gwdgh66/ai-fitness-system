package com.fitness.ai.common;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * 统一 API 响应体。
 *
 * @param code      业务状态码，成功时为 {@link ErrorCode#SUCCESS}
 * @param message   提示信息
 * @param data      业务数据载荷
 * @param timestamp 响应时间戳（epoch 毫秒）
 * @param <T>       业务数据类型
 */
public record Result<T>(int code, String message, T data, long timestamp) {

    /** 成功响应（无数据载荷）。 */
    public static <T> Result<T> success() {
        return success(null);
    }

    /** 成功响应（携带数据载荷）。 */
    public static <T> Result<T> success(T data) {
        return new Result<>(
                ErrorCode.SUCCESS.getCode(),
                ErrorCode.SUCCESS.getMessage(),
                data,
                Instant.now().toEpochMilli());
    }

    /** 失败响应：使用错误码自带的提示信息。 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    /** 失败响应：保留错误码语义，覆盖提示信息。 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null, Instant.now().toEpochMilli());
    }

    /** 失败响应：完全自定义状态码与提示信息。 */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, Instant.now().toEpochMilli());
    }

    /**
     * 是否为成功响应。
     *
     * <p>加 {@code @JsonIgnore}：否则 Jackson 会把 {@code isSuccess()} 当成一个名为 success 的属性
     * 序列化进响应体，而统一响应体的字段约定只有 code / message / data / timestamp。</p>
     */
    @JsonIgnore
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }
}