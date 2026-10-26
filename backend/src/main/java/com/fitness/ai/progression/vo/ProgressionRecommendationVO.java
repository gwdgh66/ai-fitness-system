package com.fitness.ai.progression.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 单个课表明细的双重渐进建议。
 *
 * @param currentTargetWeightKg 课表当前的目标重量
 * @param workingWeightKg       建议计算的基准重量（取「课表目标重量」与「最近一次实际重量」的较大值），
 *                              {@code suggestedWeightKg - workingWeightKg} 即为本次建议的加重/降重幅度
 * @param status            OVERLOAD_READY（可加重）/ MAINTAIN（维持）/ DELOAD_NEEDED（需减载）/ INSUFFICIENT_DATA（样本不足）
 * @param suggestedWeightKg 建议的下一次训练重量（kg）
 * @param suggestedReps     建议的下一次每组次数
 * @param reason            判定理由（确定性文案，便于前端直接展示与排查）
 * @param sampleSize        实际参考到的历史打卡条数
 * @param recentLogs        最近几次该动作的真实打卡（倒序）
 */
public record ProgressionRecommendationVO(
        Long itemId,
        Long exerciseId,
        String exerciseName,
        String targetMuscle,
        Integer currentTargetSets,
        Integer currentTargetReps,
        BigDecimal currentTargetWeightKg,
        BigDecimal workingWeightKg,
        String status,
        BigDecimal suggestedWeightKg,
        Integer suggestedReps,
        String reason,
        int sampleSize,
        List<RecentLogBrief> recentLogs
) {

    /** 打卡摘要。 */
    public record RecentLogBrief(
            LocalDate logDate,
            Integer actualSets,
            Integer actualReps,
            BigDecimal actualWeightKg,
            BigDecimal actualRpe,
            BigDecimal estimated1rm
    ) {
    }
}