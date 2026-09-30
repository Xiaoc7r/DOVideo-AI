package com.example.server.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码枚举，同时携带对应的 HTTP 状态码。
 *
 * <p>业务码采用五位数，前三位与 HTTP 语义对齐、后两位区分细分场景，便于前端与告警系统识别。
 * 全局异常处理器据此同时决定响应体的 {@code code} 与 HTTP 状态码。
 */
public enum ErrorCode {

    INVALID_ARGUMENT(40000, HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED(40001, HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40100, HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, HttpStatus.FORBIDDEN),
    NOT_FOUND(40400, HttpStatus.NOT_FOUND),
    CONFLICT(40900, HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE(41300, HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(41500, HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    UNPROCESSABLE(42200, HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(42900, HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(50300, HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final HttpStatus httpStatus;

    ErrorCode(int code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
