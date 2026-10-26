package com.fitness.ai.dashboard.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 营养趋势序列（供堆叠柱状图使用）：每日实际摄入 vs 当日目标。
 *
 * <p>所有列表均与 {@code dates} 一一对应；没有目标或没有打卡的日期填 null / 0。</p>
 */
public record NutritionTrendVO(
        LocalDate from,
        LocalDate to,
        List<String> dates,
        List<Integer> consumedCalories,
        List<Integer> targetCalories,
        List<BigDecimal> consumedProteinG,
        List<BigDecimal> consumedCarbsG,
        List<BigDecimal> consumedFatG,
        List<Integer> targetProteinG,
        List<Integer> targetCarbsG,
        List<Integer> targetFatG
) {
}