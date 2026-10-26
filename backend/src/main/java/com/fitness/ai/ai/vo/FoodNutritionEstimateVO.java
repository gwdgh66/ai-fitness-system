package com.fitness.ai.ai.vo;

import java.math.BigDecimal;

/**
 * AI 食物营养估算结果（**建议值，未落库**）。
 *
 * <p>关键设计：AI 只给出「每 100g」的参考数值，返回给前端填入表单，用户确认或修改后才提交；
 * 提交后由 {@code NutritionService} 用 Java 完成「× 克重 ÷ 100」的折算与汇总。
 * Java 侧还会对 AI 数字做范围与量纲校验（{@code consistent} / {@code warning}），
 * 避免把明显不合理的值直接灌进数据库。</p>
 *
 * @param basis       AI 给出该估算的依据（一句话）
 * @param confidence  AI 自评把握程度：HIGH / MEDIUM / LOW
 * @param consistent  Java 侧校验：三大营养素折算热量与给出热量是否自洽
 * @param warning     不自洽或把握度低时的提示文案（前端应显式展示）
 * @param disclaimer  固定免责声明
 */
public record FoodNutritionEstimateVO(
        String foodName,
        Integer caloriesPer100g,
        BigDecimal proteinPer100g,
        BigDecimal carbsPer100g,
        BigDecimal fatPer100g,
        String basis,
        String confidence,
        boolean consistent,
        String warning,
        long elapsedMs,
        String disclaimer
) {
}