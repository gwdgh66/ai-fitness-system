package com.fitness.ai.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 一键套用训练模板入参（{@code POST /api/catalog/workout-templates/apply}）。
 */
public record WorkoutTemplateApplyDTO(
        @NotNull(message = "userId 不能为空")
        Long userId,

        @NotBlank(message = "templateCode 不能为空（PPL_3DAY / UPPER_LOWER_4DAY）")
        String templateCode
) {
}