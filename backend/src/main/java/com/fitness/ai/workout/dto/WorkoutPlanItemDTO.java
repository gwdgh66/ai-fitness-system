package com.fitness.ai.workout.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 课表动作明细入参（新建计划时作为 items 元素，或用于替换某条明细）。
 *
 * <p>注意：这里只做「非空」结构校验；组数 / 次数 / RPE 的**业务安全边界**一律由
 * {@code WorkoutService} 以 Java 硬编码判定并抛出 {@link com.fitness.ai.common.BusinessException}，
 * 这样错误消息才能带上具体数值与修正建议，供后续大模型读取后自我修正。</p>
 */
public record WorkoutPlanItemDTO(
        @NotNull(message = "dayOfWeek 不能为空（1~7 代表周一至周日）")
        Integer dayOfWeek,

        @NotNull(message = "exerciseId 不能为空")
        Long exerciseId,

        @NotNull(message = "targetSets 不能为空")
        Integer targetSets,

        @NotNull(message = "targetReps 不能为空")
        Integer targetReps,

        BigDecimal targetWeightKg,

        BigDecimal targetRpe,

        Integer sortOrder,

        String notes
) {
}