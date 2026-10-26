package com.fitness.ai.profile.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户动态伤病与禁忌（表 user_injury_tag）。
 *
 * <p>body_part 的取值与 exercise_dict.avoid_injury_parts 中的标记同构，
 * 供后续 Agent 换动作时做安全过滤。</p>
 */
@Data
@TableName("user_injury_tag")
public class UserInjuryTag {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID（user_profile.id） */
    @TableField("user_id")
    private Long userId;

    /** LEFT_SHOULDER / RIGHT_SHOULDER / KNEE / LOWER_BACK / WRIST 等 */
    @TableField("body_part")
    private String bodyPart;

    /** 具体症状描述 */
    @TableField("description")
    private String description;

    /** MILD / MODERATE / SEVERE */
    @TableField("severity")
    private String severity;

    /** ACTIVE / RECOVERED */
    @TableField("status")
    private String status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}