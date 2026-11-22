package com.example.server.common;

/**
 * 统一 API 响应体。
 *
 * <p>约定：{@code code == 0} 表示成功，非 0 表示业务/系统错误；HTTP 状态码只表达传输层语义， 业务语义一律由 {@code code}
 * 承载。这样前端可以用固定结构解析成功/失败，不必对每个接口 各写一套解析逻辑。
 *
 * @param <T> 业务数据类型
 */
public record Result<T>(int code, String message, T data) {

  /** 成功业务码。 */
  public static final int SUCCESS_CODE = 0;

  private static final String SUCCESS_MESSAGE = "success";

  public static <T> Result<T> ok(T data) {
    return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
  }

  public static <T> Result<T> ok() {
    return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
  }

  public static <T> Result<T> error(int code, String message) {
    return new Result<>(code, message, null);
  }

  public static <T> Result<T> error(ErrorCode errorCode, String message) {
    return new Result<>(errorCode.code(), message, null);
  }
}
