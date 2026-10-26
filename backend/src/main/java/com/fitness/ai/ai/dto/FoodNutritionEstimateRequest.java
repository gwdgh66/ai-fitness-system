package com.fitness.ai.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * AI 食物营养估算请求（供「自定义食物」填表参考，**不写库**）。
 *
 * @param userId   用户 ID（用于档案校验与日志追踪）
 * @param foodName 食物名称，例如「佛跳墙」「自制蛋白奶昔」
 * @param weightG  计划摄入克重（仅用于回显与提示，估算结果统一按每 100g 返回）
 * @param note     补充说明，例如「家里做的，一碗约 300g」「外卖，偏油」
 */
public record FoodNutritionEstimateRequest(
        @NotNull(message = "userId 不能为空")
        Long userId,

        @NotBlank(message = "foodName 不能为空")
        String foodName,

        BigDecimal weightG,

        String note
) {
}