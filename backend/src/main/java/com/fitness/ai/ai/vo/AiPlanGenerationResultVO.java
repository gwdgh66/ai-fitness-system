package com.fitness.ai.ai.vo;

import com.fitness.ai.workout.vo.WorkoutPlanVO;

import java.util.List;

/**
 * AI 结构化排课结果。
 *
 * @param plan         最终落库并激活的完整周课表
 * @param coachReasoning AI 给出的排课理由与注意事项
 * @param attempts     实际调用大模型的次数（1 表示一次通过，>1 表示经历护栏纠错重试）
 * @param retryHistory 纠错重试轨迹（每次失败的具体原因，便于排查模型为什么被拦）
 */
public record AiPlanGenerationResultVO(
        WorkoutPlanVO plan,
        String coachReasoning,
        int attempts,
        List<String> retryHistory,
        String message
) {
}