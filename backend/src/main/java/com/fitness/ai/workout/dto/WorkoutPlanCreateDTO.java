package com.fitness.ai.workout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 创建训练计划入参（{@code POST /api/workout/plans}）。
 *
 * <p>创建时会把该用户已有的 ACTIVE 计划置为 ARCHIVED。</p>
 */
public record WorkoutPlanCreateDTO(
        @NotNull(message = "userId 不能为空")
        Long userId,

        @NotBlank(message = "planName 不能为空")
        String planName,

        /** PPL / UPPER_LOWER / FULL_BODY */
        @NotBlank(message = "splitType 不能为空")
        String splitType,

        LocalDate startDate,

        LocalDate endDate,

        @NotEmpty(message = "items 不能为空，至少需要一个训练动作")
        @Valid
        List<WorkoutPlanItemDTO> items
) {
}