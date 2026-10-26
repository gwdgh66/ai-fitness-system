package com.fitness.ai.profile.vo;

import com.fitness.ai.profile.entity.UserInjuryTag;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户数字孪生视图：基础体测档案 + 当前所有 ACTIVE 状态的伤病标签。
 *
 * <p>该视图是后续 AI Agent 的「用户上下文」快照，其中的 activeInjuries 会被
 * WorkoutService 用于动作安全过滤。</p>
 */
public record UserDigitalTwinVO(
        Long userId,
        String nickname,
        String gender,
        Integer age,
        BigDecimal heightCm,
        BigDecimal weightKg,
        BigDecimal bodyFatPct,
        Integer bmr,
        Integer tdee,
        BigDecimal activityLevel,
        String fitnessGoal,
        String equipmentEnv,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<UserInjuryTag> activeInjuries
) {
}