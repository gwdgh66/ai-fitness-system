package com.fitness.ai.workout.vo;

/**
 * 删除自定义动作的结果。
 *
 * @param referencedPlanItems 仍被课表引用的明细数（>0 时不允许删除，保证课表不被破坏）
 * @param referencedLogs      仍被打卡记录引用的条数（>0 时不允许删除，保证训练历史可追溯）
 */
public record CustomExerciseDeleteVO(
        Long exerciseId,
        String name,
        int referencedPlanItems,
        long referencedLogs,
        String message
) {
}