package com.fitness.ai.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AI 结构化排课请求（{@code POST /api/ai/plan/generate}）。
 */
public record AiPlanGenerateRequest(
        @NotNull(message = "userId 不能为空")
        Long userId,

        @NotBlank(message = "requirement 不能为空，请描述排课诉求")
        String requirement
) {
}