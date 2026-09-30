package com.example.server.controller;

import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.exception.BusinessException;
import com.example.server.service.AgentLoopService;
import com.example.server.service.VideoContextNotReadyException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * 全局异常处理：把各类异常统一转换为 {@link Result}，业务码放响应体、HTTP 状态表达传输语义。
 *
 * <p>核心目标：客户端参数类错误正确落到 4xx（不再被兜底压成 500）；对外只返回受控文案，
 * 内部异常细节仅进日志，避免信息泄漏。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // ---- 业务异常：错误码即语义 ----
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException error) {
        ErrorCode code = error.errorCode();
        return build(code.httpStatus(), code.code(), safe(error.getMessage(), "请求处理失败"));
    }

    // ---- 请求体 Bean Validation 失败 → 400，返回字段级错误 ----
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> invalidBody(MethodArgumentNotValidException error) {
        String detail = error.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.code(),
                detail.isBlank() ? "请求参数校验失败" : detail);
    }

    // ---- 请求参数 @Validated 约束失败 → 400 ----
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> invalidParam(ConstraintViolationException error) {
        String detail = error.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.code(),
                detail.isBlank() ? "请求参数校验失败" : detail);
    }

    // ---- 其余客户端错误（缺参/类型不匹配/请求体不可读/非法参数）→ 400 ----
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Result<Void>> badRequest(Exception error) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT.code(), clientMessage(error));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException error) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.INVALID_ARGUMENT.code(), "请求方法不被支持");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> uploadTooLarge(MaxUploadSizeExceededException error) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.code(),
                "上传文件或请求体超过大小限制，请使用分片上传或压缩文件");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> unsupportedMediaType(HttpMediaTypeNotSupportedException error) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.code(),
                "请求内容类型不受支持");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Result<Void>> notFound(NoSuchElementException error) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND.code(), safe(error.getMessage(), "资源不存在"));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Result<Void>> forbidden(SecurityException error) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN.code(), safe(error.getMessage(), "无访问权限"));
    }

    @ExceptionHandler(AgentLoopService.BudgetExceededException.class)
    public ResponseEntity<Result<Void>> budgetExceeded(AgentLoopService.BudgetExceededException error) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.UNPROCESSABLE.code(),
                safe(error.getMessage(), "任务已超出预算"));
    }

    @ExceptionHandler(VideoContextNotReadyException.class)
    public ResponseEntity<Result<Void>> videoContextNotReady(VideoContextNotReadyException error) {
        return build(HttpStatus.CONFLICT, ErrorCode.CONFLICT.code(),
                safe(error.getMessage(), "视频上下文尚未就绪"));
    }

    /**
     * CompletableFuture 会把工作线程里的业务异常包成 CompletionException。
     * 解包后继续走原有映射，避免异步化把原本的 4xx/422 全部降级成 500。
     */
    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<Result<Void>> asyncFailure(CompletionException error) {
        Throwable cause = error.getCause();
        while (cause instanceof CompletionException nested && nested.getCause() != null) {
            cause = nested.getCause();
        }
        if (cause instanceof BusinessException businessException) return business(businessException);
        if (cause instanceof AgentLoopService.BudgetExceededException budgetException) {
            return budgetExceeded(budgetException);
        }
        if (cause instanceof VideoContextNotReadyException contextException) {
            return videoContextNotReady(contextException);
        }
        if (cause instanceof NoSuchElementException notFoundException) return notFound(notFoundException);
        if (cause instanceof SecurityException securityException) return forbidden(securityException);
        if (cause instanceof IllegalArgumentException argumentException) return badRequest(argumentException);
        if (cause instanceof Exception exception) return internalError(exception);
        return internalError(error);
    }

    // ---- 兜底：真正未知的异常才落到这里，不对外泄漏细节 ----
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> internalError(Exception error) {
        log.error("unhandled_request_error", error);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.code(), "服务暂时不可用");
    }

    private ResponseEntity<Result<Void>> build(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(Result.error(code, message));
    }

    /**
     * IllegalArgumentException 的 message 由应用层主动抛出、面向用户，可直接返回；
     * 其余框架异常的 message 常含技术细节，用通用文案兜底，避免信息泄漏。
     */
    private String clientMessage(Exception error) {
        if (error instanceof IllegalArgumentException) {
            return safe(error.getMessage(), "请求参数不合法");
        }
        return "请求参数不合法";
    }

    private String safe(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
