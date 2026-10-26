package com.fitness.ai.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置。
 *
 * <p>放行前端本地开发端口（任意端口的 localhost / 127.0.0.1），并额外暴露 SSE 流式响应
 * 所需的响应头，避免浏览器端 EventSource / fetch-stream 读不到分块信息。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type", "Cache-Control", "Connection", "Transfer-Encoding", "X-Accel-Buffering")
                .allowCredentials(true)
                .maxAge(3600);
    }
}