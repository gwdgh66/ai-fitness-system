package com.fitness.ai.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AI 生成的结构化周训练计划（结构化输出）。
 *
 * @param planName       计划名称
 * @param splitType      分化类型：PPL / UPPER_LOWER / FULL_BODY
 * @param coachReasoning AI 的排课理由与注意事项（会一并返回给前端展示）
 * @param items          每日动作明细
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiGeneratedPlanDTO(
        String planName,
        String splitType,
        String coachReasoning,
        List<AiGeneratedPlanItemDTO> items
) {
}