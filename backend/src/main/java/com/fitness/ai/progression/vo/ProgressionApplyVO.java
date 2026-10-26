package com.fitness.ai.progression.vo;

import java.math.BigDecimal;

/**
 * 「采纳进阶建议」的执行结果。
 */
public record ProgressionApplyVO(
        Long itemId,
        String exerciseName,
        String status,
        BigDecimal previousWeightKg,
        BigDecimal appliedWeightKg,
        Integer previousTargetReps,
        Integer appliedTargetReps,
        String message
) {
}