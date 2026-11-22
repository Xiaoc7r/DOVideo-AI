/**
 * 跨层通用契约:统一响应体与错误码。
 *
 * <p>{@code Result<T>} 是所有 REST 接口的统一返回信封(code / message / data), {@code ErrorCode} 定义业务错误码及其对应的
 * HTTP 状态。二者共同保证前端可以用固定结构解析 成功与失败,不必为每个接口单独适配。
 *
 * <p>本包不依赖任何业务层,可被 controller、exception、service 自由引用。
 */
package com.example.server.common;
