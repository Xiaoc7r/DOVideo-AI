package com.example.server.config;

import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

  private final AuthService authService;
  private final ObjectMapper objectMapper;

  public AuthInterceptor(AuthService authService, ObjectMapper objectMapper) {
    this.authService = authService;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
    try {
      Long userId = authService.resolveUser(request.getHeader("Authorization"));
      request.setAttribute(AuthService.REQUEST_USER_ID, userId);
      return true;
    } catch (SecurityException e) {
      // 拦截器在 DispatcherServlet 之前返回，不会经过 @RestControllerAdvice，
      // 因此这里必须自己输出统一响应体，否则 401 会是唯一一种“裸文本”错误，
      // 破坏“所有错误都是 {code,message,data}”的契约，前端解包层也要为它开特例。
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      objectMapper.writeValue(
          response.getWriter(), Result.error(ErrorCode.UNAUTHORIZED, e.getMessage()));
      return false;
    }
  }
}
