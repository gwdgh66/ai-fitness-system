package com.fitness.ai.nutrition.vo;

import com.fitness.ai.nutrition.entity.DietLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 每日营养账单汇总视图。
 *
 * <p>包含：当日目标值、已摄入总热量与三大营养素、剩余缺口（目标 - 已摄入）以及饮食流水明细。
 * 若该日尚未生成 {@code daily_macro_target}，则目标值与缺口字段为 null。</p>
 */
public record DailyNutritionSummaryVO(
        Long userId,
        LocalDate date,
        String dayType,
        Integer targetCalories,
        Integer targetProteinG,
        Integer targetCarbsG,
        Integer targetFatG,
        int consumedCalories,
        BigDecimal consumedProteinG,
        BigDecimal consumedCarbsG,
        BigDecimal consumedFatG,
        Integer remainingCalories,
        BigDecimal remainingProteinG,
        BigDecimal remainingCarbsG,
        BigDecimal remainingFatG,
        List<DietLog> logs
) {
}