package com.fitness.ai.dashboard.vo;

import java.time.LocalDate;

/**
 * 14 天演示数据播种结果。
 */
public record DemoSeedResultVO(
        Long userId,
        int days,
        LocalDate from,
        LocalDate to,
        Long planId,
        String planName,
        int planItems,
        int workoutLogs,
        int dietLogs,
        int macroTargets,
        String note
) {
}