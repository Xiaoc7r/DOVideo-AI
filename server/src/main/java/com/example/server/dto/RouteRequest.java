package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 自动意图路由请求。目标放在请求体中，避免进入 URL、代理日志和浏览器历史。 */
public record RouteRequest(
    @NotBlank(message = "分析目标不能为空") @Size(max = 500, message = "分析目标不能超过 500 字") String goal) {}
