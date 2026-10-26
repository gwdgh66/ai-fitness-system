package com.fitness.ai.catalog.vo;

import com.fitness.ai.nutrition.vo.DailyNutritionSummaryVO;

import java.time.LocalDate;

/**
 * 一键套餐打卡结果。
 *
 * @param addedCalories 本次套餐写入的总热量（kcal）
 */
public record MealTemplateApplyVO(
        Long templateId,
        String templateName,
        LocalDate date,
        String mealType,
        int loggedItems,
        int addedCalories,
        DailyNutritionSummaryVO summary
) {
}