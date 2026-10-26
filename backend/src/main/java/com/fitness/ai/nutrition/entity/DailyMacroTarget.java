package com.fitness.ai.nutrition.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日营养素目标（表 daily_macro_target，支持碳水循环）。
 *
 * <p>业务唯一键为 (user_id, target_date)。</p>
 */
@Data
@TableName("daily_macro_target")
public class DailyMacroTarget {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID（user_profile.id） */
    @TableField("user_id")
    private Long userId;

    @TableField("target_date")
    private LocalDate targetDate;

    /** HIGH_CARB / MEDIUM_CARB / LOW_CARB */
    @TableField("day_type")
    private String dayType;

    /** 目标热量（kcal），业务护栏 >= 1000 */
    @TableField("target_calories")
    private Integer targetCalories;

    /** 目标蛋白质（g） */
    @TableField("target_protein_g")
    private Integer targetProteinG;

    /** 目标碳水（g） */
    @TableField("target_carbs_g")
    private Integer targetCarbsG;

    /** 目标脂肪（g） */
    @TableField("target_fat_g")
    private Integer targetFatG;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}