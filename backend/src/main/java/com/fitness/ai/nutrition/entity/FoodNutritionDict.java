package com.fitness.ai.nutrition.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 本地食物营养成分字典（表 food_nutrition_dict，数据按每 100g 计）。
 *
 * <p>本表为纯字典表，无 created_at / updated_at 字段。</p>
 */
@Data
@TableName("food_nutrition_dict")
public class FoodNutritionDict {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 食物名称（唯一） */
    @TableField("food_name")
    private String foodName;

    /** CARB / PROTEIN / FAT / VEG_FRUIT */
    @TableField("category")
    private String category;

    /** 每 100g 热量（kcal） */
    @TableField("calories_per_100g")
    private Integer caloriesPer100g;

    /** 每 100g 蛋白质（g） */
    @TableField("protein_per_100g")
    private BigDecimal proteinPer100g;

    /** 每 100g 碳水（g） */
    @TableField("carbs_per_100g")
    private BigDecimal carbsPer100g;

    /** 每 100g 脂肪（g） */
    @TableField("fat_per_100g")
    private BigDecimal fatPer100g;

    /** 常见份量提示，如 "1个全蛋约50g，1勺乳清蛋白粉约30g" */
    @TableField("unit_hint")
    private String unitHint;
}