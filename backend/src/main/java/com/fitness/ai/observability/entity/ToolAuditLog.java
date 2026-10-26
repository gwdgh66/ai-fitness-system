package com.fitness.ai.observability.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 工具调用审计（tool_audit_log）。
 *
 * <p>由 {@code ToolExecutionAuditAspect} 环绕 {@code @Tool} 方法自动写入，
 * 用独立事务落库，保证工具本身回滚也不会丢掉审计记录。</p>
 */
@Data
@TableName("tool_audit_log")
public class ToolAuditLog {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 工具归属用户（@ToolMemoryId 注入值；无用户上下文的工具为 null） */
    @TableField("user_id")
    private Long userId;

    /** 工具类全名 */
    @TableField("tool_class")
    private String toolClass;

    /** 工具方法名 */
    @TableField("tool_name")
    private String toolName;

    /** 入参 JSON */
    @TableField("args_json")
    private String argsJson;

    /** 返回值文本 */
    @TableField("result_text")
    private String resultText;

    /** SUCCESS / GUARDRAIL_BLOCKED / EXCEPTION */
    @TableField("status")
    private String status;

    /** 执行耗时（毫秒） */
    @TableField("cost_ms")
    private Long costMs;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}