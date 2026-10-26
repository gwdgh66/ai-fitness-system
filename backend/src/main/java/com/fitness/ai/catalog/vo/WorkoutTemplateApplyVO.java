package com.fitness.ai.catalog.vo;

import java.util.List;

/**
 * 一键套用训练模板的结果。
 *
 * @param generatedItems 实际排入课表的动作说明
 * @param skippedSlots   因伤病安全过滤而没有可用动作的槽位说明
 */
public record WorkoutTemplateApplyVO(
        Long planId,
        String templateCode,
        String planName,
        String splitType,
        int itemCount,
        List<String> generatedItems,
        List<String> skippedSlots
) {
}