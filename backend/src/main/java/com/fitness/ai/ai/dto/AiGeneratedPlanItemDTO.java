package com.fitness.ai.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * AI 生成的单条课表明细（结构化输出）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiGeneratedPlanItemDTO(
        Integer dayOfWeek,
        Long exerciseId,
        String exerciseName,
        Integer targetSets,
        Integer targetReps,
        BigDecimal targetWeightKg,
        BigDecimal targetRpe,
        String notes
) {
}