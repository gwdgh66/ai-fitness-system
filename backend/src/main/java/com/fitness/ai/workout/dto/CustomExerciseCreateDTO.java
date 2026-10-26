package com.fitness.ai.workout.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 新建自定义动作。
 *
 * <p><b>安全提醒</b>：伤病护栏完全依赖 {@code avoidInjuryParts}。自定义动作若留空，
 * 系统会把它当成「对所有伤病都安全」，护栏将无法替你拦截 —— 因此这里显式声明该参数，
 * 并且只接受系统已知的部位取值（防止拼错导致护栏静默失效）。</p>
 *
 * @param avoidInjuryParts 禁忌受伤部位，逗号分隔；可选值：LEFT_SHOULDER / RIGHT_SHOULDER / KNEE / LOWER_BACK / WRIST
 */
public record CustomExerciseCreateDTO(
        @NotNull(message = "userId 不能为空")
        Long userId,

        @NotBlank(message = "动作名称不能为空")
        String name,

        @NotBlank(message = "targetMuscle 不能为空（CHEST / BACK / LEGS / SHOULDERS / ARMS / CORE）")
        String targetMuscle,

        /** 可选：BARBELL / DUMBBELL / MACHINE / CABLE / BODYWEIGHT / OTHER，默认 OTHER */
        String equipment,

        /** 可选：BEGINNER / INTERMEDIATE / ADVANCED，默认 INTERMEDIATE */
        String difficulty,

        String avoidInjuryParts,

        String cues
) {
}