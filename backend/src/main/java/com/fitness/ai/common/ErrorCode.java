package com.fitness.ai.common;

/**
 * 全局业务错误码。
 *
 * <p>区间约定：200/4xx 通用 HTTP 语义；1xxx 业务错误；2xxx 参数与 AI 护栏错误；500 系统错误。</p>
 */
public enum ErrorCode {

    SUCCESS(200, "操作成功"),

    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证或登录已过期"),
    FORBIDDEN(403, "无访问权限"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),

    BUSINESS_ERROR(1000, "业务处理失败"),

    PARAM_VALIDATION_FAILED(2001, "参数校验失败"),

    /**
     * 供确定性业务方法 / {@code @Tool} 安全护栏使用：入参越界
     * （例如每日训练组数、每日热量摄入超出安全边界）。
     */
    PARAM_OUT_OF_BOUNDS(2002, "参数超出安全边界"),

    SYSTEM_ERROR(500, "系统内部错误，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}