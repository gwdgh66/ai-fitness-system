package com.fitness.ai.nutrition.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日饮食打卡流水（diet_log）。
 *
 * <p>本条的 calories / protein_g / carbs_g / fat_g 由 Service 层根据
 * food_nutrition_dict 的每 100g 数据与 weight_g 确定性折算；自定义食物（字典未收录）则按调用方
 * 提交的每 100g 数值折算 —— 无论哪种情况，<b>乘法与汇总都发生在 Java 侧，不允许 LLM 直接产出落库数值</b>。
 * 本表只有 created_at，无 updated_at。</p>
 */
@Data
@TableName("diet_log")
public class DietLog {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID（user_profile.id） */
    @TableField("user_id")
    private Long userId;

    @TableField("log_date")
    private LocalDate logDate;

    /** BREAKFAST / LUNCH / DINNER / SNACK */
    @TableField("meal_type")
    private String mealType;

    /** 食物名称（冗余快照） */
    @TableField("food_name")
    private String foodName;

    /** 摄入重量（g） */
    @TableField("weight_g")
    private BigDecimal weightG;

    /** 本条摄入热量（kcal） */
    @TableField("calories")
    private Integer calories;

    /** 本条摄入蛋白质（g） */
    @TableField("protein_g")
    private BigDecimal proteinG;

    /** 本条摄入碳水（g） */
    @TableField("carbs_g")
    private BigDecimal carbsG;

    /** 本条摄入脂肪（g） */
    @TableField("fat_g")
    private BigDecimal fatG;

    /**
     * 数据来源：DICTIONARY（字典命中）/ CUSTOM（用户自填每 100g）/ AI_ESTIMATED（AI 估算后经人工确认）。
     * 前端据此在账单上标注，避免把估算值当成字典实测值。
     */
    @TableField("source")
    private String source;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}