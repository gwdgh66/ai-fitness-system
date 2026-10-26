package com.fitness.ai.workout.vo;

/**
 * 删除课表明细的结果。
 *
 * @param removedSets            被删除动作原本的组数
 * @param remainingItemsOnDay    该日剩余动作数
 * @param remainingSetsOnDay     该日剩余总组数（用于确认仍在 30 组护栏内）
 * @param preservedWorkoutLogs   该动作已产生的打卡记录条数（**删除课表不会删历史**）
 * @param planEmptied            删完之后整份计划是否已没有任何动作
 * @param message                可直接展示给用户的完成说明（明确复述落库结果）
 */
public record PlanItemDeleteVO(
        Long itemId,
        String exerciseName,
        Integer dayOfWeek,
        String dayLabel,
        int removedSets,
        int remainingItemsOnDay,
        int remainingSetsOnDay,
        long preservedWorkoutLogs,
        boolean planEmptied,
        String message
) {
}