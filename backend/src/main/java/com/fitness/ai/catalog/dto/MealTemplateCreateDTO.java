package com.fitness.ai.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 新增常吃套餐模板入参（{@code POST /api/catalog/meal-templates}）。
 *
 * <p>合计热量与三大营养素由 Java 依 food_nutrition_dict 逐项折算后求和，不接受外部传入。</p>
 */
public record MealTemplateCreateDTO(
        @NotNull(message = "userId 不能为空")
        Long userId,

        @NotBlank(message = "templateName 不能为空")
        String templateName,

        @NotBlank(message = "mealType 不能为空（BREAKFAST / LUNCH / DINNER / SNACK）")
        String mealType,

        @NotEmpty(message = "items 不能为空，至少包含一种食物")
        @Valid
        List<MealItemDTO> items
) {
}