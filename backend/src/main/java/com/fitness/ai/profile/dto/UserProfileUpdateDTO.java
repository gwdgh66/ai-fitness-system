package com.fitness.ai.profile.dto;

import java.math.BigDecimal;

/**
 * 用户体测档案更新入参（{@code PUT /api/profile/{userId}}）。
 *
 * <p>语义为「部分更新」：只有非 null 的字段会被写入；只要更新后
 * gender / age / heightCm / weightKg / activityLevel 五者齐全，Service 就会重算 BMR 与 TDEE。</p>
 */
public record UserProfileUpdateDTO(
        String nickname,
        String gender,
        Integer age,
        BigDecimal heightCm,
        BigDecimal weightKg,
        BigDecimal bodyFatPct,
        BigDecimal activityLevel,
        String fitnessGoal,
        String equipmentEnv
) {
}