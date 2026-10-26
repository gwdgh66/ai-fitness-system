package com.fitness.ai.observability.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.observability.entity.ToolAuditLog;
import com.fitness.ai.observability.mapper.ToolAuditLogMapper;
import com.fitness.ai.observability.vo.ToolAuditSummaryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * AI 工具调用审计的落库与查询。
 *
 * <p>落库使用 {@code REQUIRES_NEW} 独立事务：即使工具自身所在的事务因为后续异常回滚，
 * 审计记录也不会跟着丢；反之审计写失败也只记警告，绝不反过来影响工具执行。</p>
 */
@Slf4j
@Service
public class ToolAuditService {

    /** 审计流水状态 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_GUARDRAIL_BLOCKED = "GUARDRAIL_BLOCKED";
    public static final String STATUS_EXCEPTION = "EXCEPTION";

    private static final int MAX_RESULT_LENGTH = 4000;

    private final ToolAuditLogMapper toolAuditLogMapper;

    public ToolAuditService(ToolAuditLogMapper toolAuditLogMapper) {
        this.toolAuditLogMapper = toolAuditLogMapper;
    }

    /** 独立事务写入一条审计记录。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String toolClass, String toolName, String argsJson,
                       String resultText, String status, long costMs) {
        ToolAuditLog entity = new ToolAuditLog();
        entity.setUserId(userId);
        entity.setToolClass(toolClass);
        entity.setToolName(toolName);
        entity.setArgsJson(truncate(argsJson));
        entity.setResultText(truncate(resultText));
        entity.setStatus(status);
        entity.setCostMs(costMs);
        toolAuditLogMapper.insert(entity);
        log.debug("工具审计已落库：user={} tool={} status={} cost={}ms", userId, toolName, status, costMs);
    }

    /** 查询指定用户最近 N 条审计流水及全量统计。 */
    @Transactional(readOnly = true)
    public ToolAuditSummaryVO query(Long userId, int limit) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 不能为空");
        }
        if (limit < 1 || limit > 500) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "limit=" + limit + " 超出查询范围，必须在 1 ~ 500 条之间");
        }
        List<ToolAuditLog> logs = toolAuditLogMapper.selectList(Wrappers.<ToolAuditLog>lambdaQuery()
                .eq(ToolAuditLog::getUserId, userId)
                .orderByDesc(ToolAuditLog::getId)
                .last("LIMIT " + limit));

        Map<String, Object> stats = toolAuditLogMapper.selectMaps(new QueryWrapper<ToolAuditLog>()
                        .select("COUNT(*) AS total",
                                "SUM(CASE WHEN status = 'GUARDRAIL_BLOCKED' THEN 1 ELSE 0 END) AS blocked",
                                "SUM(CASE WHEN status = 'EXCEPTION' THEN 1 ELSE 0 END) AS exceptionCount",
                                "ROUND(AVG(cost_ms), 1) AS avgCostMs",
                                "MAX(cost_ms) AS maxCostMs")
                        .eq("user_id", userId))
                .stream().findFirst().orElse(Map.of());

        return new ToolAuditSummaryVO(userId,
                toLong(stats.get("total")),
                toLong(stats.get("blocked")),
                toLong(stats.get("exceptionCount")),
                toBigDecimal(stats.get("avgCostMs")),
                stats.get("maxCostMs") == null ? null : toLong(stats.get("maxCostMs")),
                limit,
                logs);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_RESULT_LENGTH ? text : text.substring(0, MAX_RESULT_LENGTH) + "...(截断)";
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
    }
}