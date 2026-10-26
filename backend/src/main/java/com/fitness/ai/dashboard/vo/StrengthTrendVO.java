package com.fitness.ai.dashboard.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 力量走势曲线（供 ECharts 折线图使用）。
 *
 * <p>{@code dates} 为横轴，每个 series 的 {@code estimated1rm} 与 dates 一一对应，
 * 当天没有该动作打卡的位置为 null（前端可 connectNulls）。</p>
 */
public record StrengthTrendVO(
        LocalDate from,
        LocalDate to,
        List<String> dates,
        List<Series> series
) {

    /** 单条动作曲线。 */
    public record Series(
            String exerciseName,
            String targetMuscle,
            List<BigDecimal> estimated1rm
    ) {
    }
}