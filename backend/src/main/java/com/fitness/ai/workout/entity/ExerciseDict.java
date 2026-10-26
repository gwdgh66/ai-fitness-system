package com.fitness.ai.workout.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 标准健身动作字典（表 exercise_dict）。
 *
 * <p>既存放系统内置动作（{@code user_id = NULL}），也存放用户自定义动作（{@code user_id = 所属用户}）。
 * 本表为纯字典表，无 created_at / updated_at 字段。</p>
 */
@Data
@TableName("exercise_dict")
public class ExerciseDict {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** NULL=系统内置动作；非空=该用户自定义的动作 */
    @TableField("user_id")
    private Long userId;

    /** 标准动作名称（全局唯一） */
    @TableField("name")
    private String name;

    /** CHEST / BACK / LEGS / SHOULDERS / ARMS / CORE */
    @TableField("target_muscle")
    private String targetMuscle;

    /** BARBELL / DUMBBELL / MACHINE / CABLE / BODYWEIGHT / OTHER */
    @TableField("equipment")
    private String equipment;

    /** BEGINNER / INTERMEDIATE / ADVANCED */
    @TableField("difficulty")
    private String difficulty;

    /** 禁忌受伤部位，逗号分隔（如 KNEE,LOWER_BACK） */
    @TableField("avoid_injury_parts")
    private String avoidInjuryParts;

    /** 动作核心要领 */
    @TableField("cues")
    private String cues;
}