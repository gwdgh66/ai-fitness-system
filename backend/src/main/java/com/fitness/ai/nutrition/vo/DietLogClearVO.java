package com.fitness.ai.nutrition.vo;

import java.time.LocalDate;

/**
 * 清空某日饮食流水的执行结果。
 *
 * <p>用于「开始记录真实饮食前，把演示/试录的流水清干净」：只影响
 * <b>该用户 + 该日期</b>的 diet_log，其他日期与其他用户的数据不受影响。</p>
 *
 * @param deletedCount          实际删除的流水条数
 * @param deletedCalories       被删除流水合计热量（kcal），便于用户核对删掉了多少
 * @param consumedCaloriesAfter 清空后该日剩余已摄入热量（0 表示已清空）
 * @param message               可直接展示给用户的完成说明
 */
public record DietLogClearVO(
        Long userId,
        LocalDate logDate,
        int deletedCount,
        int deletedCalories,
        int consumedCaloriesAfter,
        String message
) {
}