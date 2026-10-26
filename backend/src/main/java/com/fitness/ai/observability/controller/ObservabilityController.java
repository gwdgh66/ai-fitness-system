package com.fitness.ai.observability.controller;

import com.fitness.ai.common.Result;
import com.fitness.ai.observability.service.ToolAuditService;
import com.fitness.ai.observability.vo.ToolAuditSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 可观测性接口。
 */
@Tag(name = "Observability", description = "AI 工具调用审计流水与统计")
@RestController
@RequestMapping("/api/observability")
@Validated
public class ObservabilityController {

    private final ToolAuditService toolAuditService;

    public ObservabilityController(ToolAuditService toolAuditService) {
        this.toolAuditService = toolAuditService;
    }

    @Operation(summary = "查询 AI 工具调用审计流水",
            description = "返回最近 N 条流水（含入参、返回值、状态、耗时）以及总调用次数、护栏拦截次数、平均耗时")
    @GetMapping("/tool-logs")
    public Result<ToolAuditSummaryVO> toolLogs(@RequestParam Long userId,
                                               @RequestParam(defaultValue = "20") int limit) {
        return Result.success(toolAuditService.query(userId, limit));
    }
}