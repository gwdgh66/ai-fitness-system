package com.fitness.ai.profile.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新增伤病标签入参（{@code POST /api/profile/{userId}/injuries}）。
 *
 * <p>status 由 Service 固定为 ACTIVE；若同一部位已存在 ACTIVE 记录，则更新其描述与严重程度。</p>
 */
public record InjuryTagCreateDTO(
        @NotBlank(message = "bodyPart 不能为空（如 LEFT_SHOULDER / KNEE / LOWER_BACK / WRIST）")
        String bodyPart,

        String description,

        /** MILD / MODERATE / SEVERE，缺省为 MILD */
        String severity
) {
}