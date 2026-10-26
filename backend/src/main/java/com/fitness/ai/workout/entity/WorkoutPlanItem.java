package com.fitness.ai.workout.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 训练课表每日动作明细（workout_plan_item）。
 */
@Data
@TableName("workout_plan_item")
public class WorkoutPlanItem {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属计划 ID（workout_plan.id） */
    @TableField("plan_id")
    private Long planId;

    /** 用户 ID（冗余字段，便于按用户直查） */
    @TableField("user_id")
    private Long userId;

    /** 训练日：1~7 代表周一至周日 */
    @TableField("day_of_week")
    private Integer dayOfWeek;

    /** 动作 ID（exercise_dict.id） */
    @TableField("exercise_id")
    private Long exerciseId;

    /** 动作名称（冗余快照） */
    @TableField("exercise_name")
    private String exerciseName;

    /** 目标组数，业务护栏 1~30 */
    @TableField("target_sets")
    private Integer targetSets;

    /** 每组目标次数 */
    @TableField("target_reps")
    private Integer targetReps;

    /** 目标重量（kg） */
    @TableField("target_weight_kg")
    private BigDecimal targetWeightKg;

    /** 目标 RPE（6~10） */
    @TableField("target_rpe")
    private BigDecimal targetRpe;

    /** 当日课表内排序 */
    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("notes")
    private String notes;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}