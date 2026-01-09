package com.example.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // 1. 保留你原来的：映射本地文件，让前端能访问 D 盘图片
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/files/**")
                .addResourceLocations("file:D:/Project/MediaApp/uploads/");
    }

    // 2. 新增的：全局跨域配置 (解决 Network Error)
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // 允许所有前端来源
                .allowedOriginPatterns("*")
                // 允许 GET, POST, DELETE 等所有方法
                .allowedMethods("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}