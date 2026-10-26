package com.fitness.ai.nutrition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 生成 / 更新某日营养素目标入参（{@code POST /api/nutrition/targets}）。
 *
 * <p>目标热量由 Service 依据该用户档案的 TDEE + 健身目标计算，不接受外部直接指定。</p>
 */
public record MacroTargetGenerateDTO(
        @NotNull(message = "userId 不能为空")
        Long userId,

        @NotNull(message = "targetDate 不能为空（ISO 格式，如 2026-10-25）")
        LocalDate targetDate,

        /** HIGH_CARB / MEDIUM_CARB / LOW_CARB */
        @NotBlank(message = "dayType 不能为空")
        String dayType
) {
}