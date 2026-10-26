package com.fitness.ai.profile.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户体测与目标档案（表 user_profile）。
 *
 * <p>bmr / tdee 只是最近一次的计算快照，运行期必须由 Service 层确定性 Java 方法重算。</p>
 */
@Data
@TableName("user_profile")
public class UserProfile {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("nickname")
    private String nickname;

    /** MALE / FEMALE */
    @TableField("gender")
    private String gender;

    /** 年龄（岁） */
    @TableField("age")
    private Integer age;

    /** 身高（cm） */
    @TableField("height_cm")
    private BigDecimal heightCm;

    /** 体重（kg） */
    @TableField("weight_kg")
    private BigDecimal weightKg;

    /** 体脂率（%） */
    @TableField("body_fat_pct")
    private BigDecimal bodyFatPct;

    /** 基础代谢率（kcal/天，Mifflin-St Jeor 公式，由 Java 计算） */
    @TableField("bmr")
    private Integer bmr;

    /** 每日总消耗（kcal/天 = BMR × activityLevel，由 Java 计算） */
    @TableField("tdee")
    private Integer tdee;

    /** 活动系数：1.2~1.9 */
    @TableField("activity_level")
    private BigDecimal activityLevel;

    /** MUSCLE_GAIN / FAT_LOSS / STRENGTH */
    @TableField("fitness_goal")
    private String fitnessGoal;

    /** GYM / DUMBBELL / BODYWEIGHT */
    @TableField("equipment_env")
    private String equipmentEnv;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}