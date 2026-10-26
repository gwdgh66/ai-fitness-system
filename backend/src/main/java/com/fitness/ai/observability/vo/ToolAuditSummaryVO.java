package com.fitness.ai.observability.vo;

import com.fitness.ai.observability.entity.ToolAuditLog;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工具调用审计汇总：流水 + 统计指标。
 *
 * @param totalCalls       该用户历史总调用次数
 * @param guardrailBlocked 被护栏拦截次数（返回值以 ERROR: 开头）
 * @param exceptionCount   未捕获异常次数
 * @param avgCostMs        平均耗时（毫秒）
 * @param maxCostMs        最大耗时（毫秒）
 */
public record ToolAuditSummaryVO(
        Long userId,
        long totalCalls,
        long guardrailBlocked,
        long exceptionCount,
        BigDecimal avgCostMs,
        Long maxCostMs,
        int limit,
        List<ToolAuditLog> logs
) {
}