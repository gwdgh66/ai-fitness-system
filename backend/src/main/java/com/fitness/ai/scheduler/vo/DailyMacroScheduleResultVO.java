package com.fitness.ai.scheduler.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日营养目标调度执行结果。
 *
 * @param usersProcessed  处理了多少个持有 ACTIVE 训练计划的用户
 * @param highCarbDays    生成 HIGH_CARB 目标的人数
 * @param mediumCarbDays  生成 MEDIUM_CARB 目标的人数
 * @param lowCarbDays     生成 LOW_CARB（休息日）目标的人数
 * @param failed          生成失败人数（例如触发热量底线护栏）
 */
public record DailyMacroScheduleResultVO(
        LocalDate date,
        int usersProcessed,
        int highCarbDays,
        int mediumCarbDays,
        int lowCarbDays,
        int failed,
        List<String> details
) {
}