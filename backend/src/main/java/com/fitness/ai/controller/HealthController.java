package com.fitness.ai.controller;

import com.fitness.ai.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 底座连通性检查，仅用于验证工程骨架是否可用。
 */
@Tag(name = "Health", description = "底座连通性检查")
@RestController
@RequestMapping("/api")
public class HealthController {

    @Operation(summary = "健康检查", description = "返回固定文本，用于验证后端服务已正常启动")
    @GetMapping("/health")
    public Result<String> health() {
        return Result.success("AI Fitness System Backend is running");
    }
}