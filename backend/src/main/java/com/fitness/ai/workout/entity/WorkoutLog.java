package com.fitness.ai.workout.entity;

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
 * 实际训练打卡流水（workout_log）。
 *
 * <p>estimated_1rm（Epley 公式）与 total_volume 由 Service 层确定性 Java 方法计算后写入，
 * 禁止由 LLM 估算。本表只有 created_at，无 updated_at。</p>
 */
@Data
@TableName("workout_log")
public class WorkoutLog {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID（user_profile.id） */
    @TableField("user_id")
    private Long userId;

    /** 关联课表明细 ID，自由训练时为空 */
    @TableField("plan_item_id")
    private Long planItemId;

    /** 打卡日期 */
    @TableField("log_date")
    private LocalDate logDate;

    @TableField("exercise_id")
    private Long exerciseId;

    /** 动作名称（冗余快照） */
    @TableField("exercise_name")
    private String exerciseName;

    /** 实际完成组数，业务护栏 1~30 */
    @TableField("actual_sets")
    private Integer actualSets;

    /** 每组实际完成次数 */
    @TableField("actual_reps")
    private Integer actualReps;

    /** 实际使用重量（kg） */
    @TableField("actual_weight_kg")
    private BigDecimal actualWeightKg;

    /** 实际 RPE */
    @TableField("actual_rpe")
    private BigDecimal actualRpe;

    /** Epley 公式估算极限重量 = 重量 × (1 + 次数 / 30) */
    @TableField("estimated_1rm")
    private BigDecimal estimated1rm;

    /** 总容量 = 组数 × 次数 × 重量 */
    @TableField("total_volume")
    private BigDecimal totalVolume;

    @TableField("feeling_notes")
    private String feelingNotes;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}