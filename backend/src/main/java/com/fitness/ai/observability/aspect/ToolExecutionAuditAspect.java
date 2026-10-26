package com.fitness.ai.observability.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.ai.observability.service.ToolAuditService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 工具调用审计切面。
 *
 * <p>环绕拦截 {@code com.fitness.ai.ai.tool} 包下所有标注 {@link Tool} 的方法，自动记录：
 * 归属用户（{@link ToolMemoryId} 注入值）、工具类与方法名、入参 JSON、返回值文本、耗时，
 * 并按返回值智能判定状态：</p>
 * <ul>
 *   <li>{@code ERROR:} 开头 → {@code GUARDRAIL_BLOCKED}（被 Java 护栏拦截，模型应自我修正）</li>
 *   <li>正常返回 → {@code SUCCESS}</li>
 *   <li>抛出未捕获异常 → {@code EXCEPTION}（记录后原样抛出，不吞异常）</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
public class ToolExecutionAuditAspect {

    /** 护栏拦截约定的错误前缀，与各 Tool 类的 error() 保持一致 */
    private static final String ERROR_PREFIX = "ERROR:";

    private final ToolAuditService toolAuditService;
    private final ObjectMapper objectMapper;

    public ToolExecutionAuditAspect(ToolAuditService toolAuditService, ObjectMapper objectMapper) {
        this.toolAuditService = toolAuditService;
        this.objectMapper = objectMapper;
    }

    @Around("execution(* com.fitness.ai.ai.tool..*(..)) && @annotation(dev.langchain4j.agent.tool.Tool)")
    public Object auditToolExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object target = joinPoint.getTarget();
        String toolName = method.getName();
        String toolClass = target == null ? method.getDeclaringClass().getSimpleName() : target.getClass().getSimpleName();

        Long userId = extractUserId(method, joinPoint.getArgs());
        String argsJson = buildArgsJson(method, joinPoint.getArgs());

        String status;
        String resultText;
        Object result = null;
        Throwable thrown = null;
        try {
            result = joinPoint.proceed();
            resultText = String.valueOf(result);
            status = resultText.startsWith(ERROR_PREFIX)
                    ? ToolAuditService.STATUS_GUARDRAIL_BLOCKED
                    : ToolAuditService.STATUS_SUCCESS;
        } catch (Throwable t) {
            thrown = t;
            status = ToolAuditService.STATUS_EXCEPTION;
            resultText = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        long costMs = System.currentTimeMillis() - start;

        // 审计写失败绝不能影响工具本身
        try {
            toolAuditService.record(userId, toolClass, toolName, argsJson, resultText, status, costMs);
        } catch (Exception e) {
            log.warn("工具审计落库失败（不影响工具执行）：tool={} status={} cause={}", toolName, status, e.getMessage());
        }

        if (thrown != null) {
            throw thrown;
        }
        return result;
    }

    /** 取出 @ToolMemoryId 标注参数的值作为归属用户。 */
    private Long extractUserId(Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(ToolMemoryId.class) && args[i] instanceof Long value) {
                return value;
            }
        }
        return null;
    }

    /** 组装入参 JSON，剔除由框架注入的 @ToolMemoryId 参数（它已单独作为 userId 记录）。 */
    private String buildArgsJson(Method method, Object[] args) {
        try {
            Parameter[] parameters = method.getParameters();
            Map<String, Object> argMap = new LinkedHashMap<>();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].isAnnotationPresent(ToolMemoryId.class)) {
                    continue;
                }
                argMap.put(parameters[i].getName(), args[i]);
            }
            return objectMapper.writeValueAsString(argMap);
        } catch (Exception e) {
            log.debug("工具入参序列化失败，退化为 toString：{}", e.getMessage());
            return String.valueOf(java.util.Arrays.toString(args));
        }
    }
}