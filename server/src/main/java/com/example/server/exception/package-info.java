/**
 * 领域异常。
 *
 * <p>{@code BusinessException} 携带 {@link com.example.server.common.ErrorCode} 与面向用户的 安全文案,由 {@code
 * ApiExceptionHandler} 统一转换为带业务码与恰当 HTTP 状态的 {@code Result},避免在各处手写 {@code
 * ResponseEntity.status(...).body(...)}。
 *
 * <p>约定:可预期的业务失败(重复提交、限流、越权、资源不存在等)抛 {@code BusinessException}; 不可预期的异常交由全局兜底处理,只记日志、不向外泄漏细节。
 */
package com.example.server.exception;
