package com.fitness.ai.dashboard.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 肌群容量分布（供雷达图 / 饼图使用，直观展示是否练偏科）。
 */
public record MuscleVolumeVO(
        LocalDate from,
        LocalDate to,
        BigDecimal totalVolume,
        int totalSets,
        List<MuscleStat> muscles
) {

    /** 单个肌群统计。 */
    public record MuscleStat(
            String targetMuscle,
            BigDecimal totalVolume,
            int totalSets,
            int logCount,
            BigDecimal volumePercent
    ) {
    }
}