package com.example.server.exception;

import com.example.server.common.ErrorCode;

/**
 * 业务异常：携带 {@link ErrorCode} 与一段面向用户的安全文案。
 *
 * <p>业务代码在遇到可预期的失败（重复提交、限流、越权、资源不存在等）时抛出本异常， 由 {@code ApiExceptionHandler} 统一转换为带业务码与恰当 HTTP 状态的
 * {@code Result}， 避免在各处手写 {@code ResponseEntity.status(...).body(...)}。
 */
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }
}
