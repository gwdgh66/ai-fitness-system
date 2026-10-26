package com.fitness.ai.workout.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 训练打卡入参（{@code POST /api/workout/logs}）。
 *
 * <p>estimated1rm 与 totalVolume 不接受外部传入，一律由 Service 按 Epley 公式与容量公式计算；
 * exerciseName 也由 exerciseId 反查字典得到。</p>
 */
public record WorkoutLogCreateDTO(
        @NotNull(message = "userId 不能为空")
        Long userId,

        /** 关联的课表明细 ID，自由训练时可为空 */
        Long planItemId,

        @NotNull(message = "logDate 不能为空")
        LocalDate logDate,

        @NotNull(message = "exerciseId 不能为空")
        Long exerciseId,

        @NotNull(message = "actualSets 不能为空")
        Integer actualSets,

        @NotNull(message = "actualReps 不能为空")
        Integer actualReps,

        @NotNull(message = "actualWeightKg 不能为空")
        BigDecimal actualWeightKg,

        BigDecimal actualRpe,

        String feelingNotes
) {
}