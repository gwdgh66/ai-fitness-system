package com.fitness.ai.dashboard.vo;

import java.math.BigDecimal;

/**
 * 看板顶部核心指标卡。
 */
public record DashboardOverviewVO(
        BigDecimal weightKg,
        BigDecimal bodyFatPct,
        Integer bmr,
        Integer tdee,
        String fitnessGoal,
        int consumedCaloriesToday,
        Integer targetCaloriesToday,
        Integer remainingCaloriesToday,
        int completedWorkoutsThisWeek,
        int plannedTrainingDaysThisWeek,
        int activeInjuries,
        BigDecimal totalVolumeLast30Days,
        int workoutLogCountLast30Days
) {
}