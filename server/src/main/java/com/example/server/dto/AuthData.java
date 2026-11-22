package com.example.server.dto;

/**
 * 鉴权成功后返回给前端的数据载荷。
 *
 * <p>与旧的 {@link AuthResponse} 相比，这里剥离了 code/msg（这些已由统一响应体 {@code Result} 承载），只保留真正的业务数据：用户信息与会话令牌。
 */
public record AuthData(AuthResponse.UserInfo userInfo, String token) {}
