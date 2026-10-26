package com.fitness.ai.workout.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 周期训练计划主表（workout_plan）。
 */
@Data
@TableName("workout_plan")
public class WorkoutPlan {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID（user_profile.id） */
    @TableField("user_id")
    private Long userId;

    @TableField("plan_name")
    private String planName;

    /** PPL / UPPER_LOWER / FULL_BODY */
    @TableField("split_type")
    private String splitType;

    @TableField("start_date")
    private LocalDate startDate;

    @TableField("end_date")
    private LocalDate endDate;

    /** ACTIVE / ARCHIVED */
    @TableField("status")
    private String status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}