package com.fitness.ai.workout.vo;

import com.fitness.ai.workout.entity.WorkoutPlanItem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 完整周课表视图：主表信息 + 按 dayOfWeek、sortOrder 升序排列的动作明细。
 */
public record WorkoutPlanVO(
        Long id,
        Long userId,
        String planName,
        String splitType,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<WorkoutPlanItem> items
) {
}