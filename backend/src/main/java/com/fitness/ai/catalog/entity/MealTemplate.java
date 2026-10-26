package com.fitness.ai.catalog.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 常吃饮食套餐模板（meal_template）。
 *
 * <p>items_json 里存 {@code [{"foodName":"生鸡胸肉","weightG":150}]}，
 * 四个合计字段在新增模板时由 Java 依据 food_nutrition_dict 逐项折算后求和，禁止由模型估算。</p>
 */
@Data
@TableName("meal_template")
public class MealTemplate {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID（user_profile.id） */
    @TableField("user_id")
    private Long userId;

    /** 模板名称，如「高蛋白增肌早餐」 */
    @TableField("template_name")
    private String templateName;

    /** BREAKFAST / LUNCH / DINNER / SNACK */
    @TableField("meal_type")
    private String mealType;

    /** 套餐明细 JSON */
    @TableField("items_json")
    private String itemsJson;

    /** 合计热量（kcal） */
    @TableField("total_calories")
    private Integer totalCalories;

    /** 合计蛋白质（g） */
    @TableField("total_protein_g")
    private BigDecimal totalProteinG;

    /** 合计碳水（g） */
    @TableField("total_carbs_g")
    private BigDecimal totalCarbsG;

    /** 合计脂肪（g） */
    @TableField("total_fat_g")
    private BigDecimal totalFatG;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}