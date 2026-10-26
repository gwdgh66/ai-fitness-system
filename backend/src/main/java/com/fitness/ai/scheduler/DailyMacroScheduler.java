package com.fitness.ai.scheduler;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.nutrition.dto.MacroTargetGenerateDTO;
import com.fitness.ai.nutrition.service.NutritionService;
import com.fitness.ai.scheduler.vo.DailyMacroScheduleResultVO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.entity.WorkoutPlan;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.mapper.ExerciseDictMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanItemMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 每日营养目标定时调度：根据「今天练不练、练什么」自动决定碳水日型。
 *
 * <p>判定规则（全部硬编码在 Java 中）：</p>
 * <ul>
 *   <li>当日有腿部/背部等大肌群训练，或当日计划总组数 &gt;= 16 组 → {@code HIGH_CARB}</li>
 *   <li>当日有训练但强度一般 → {@code MEDIUM_CARB}</li>
 *   <li>当日无任何训练安排（休息日）→ {@code LOW_CARB}</li>
 * </ul>
 *
 * <p>真正的热量与营养素换算仍然只发生在 {@link NutritionService}（含热量底线护栏），
 * 调度器只负责挑日型。</p>
 */
@Slf4j
@Component
public class DailyMacroScheduler {

    /** 大肌群训练日判定阈值：当日计划总组数 */
    private static final int HIGH_CARB_SET_THRESHOLD = 16;
    /** 视为「大肌群」的目标肌群 */
    private static final Set<String> BIG_MUSCLES = Set.of("LEGS", "BACK");

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String HIGH_CARB = "HIGH_CARB";
    private static final String MEDIUM_CARB = "MEDIUM_CARB";
    private static final String LOW_CARB = "LOW_CARB";

    private final WorkoutPlanMapper workoutPlanMapper;
    private final WorkoutPlanItemMapper workoutPlanItemMapper;
    private final ExerciseDictMapper exerciseDictMapper;
    private final NutritionService nutritionService;

    public DailyMacroScheduler(WorkoutPlanMapper workoutPlanMapper,
                               WorkoutPlanItemMapper workoutPlanItemMapper,
                               ExerciseDictMapper exerciseDictMapper,
                               NutritionService nutritionService) {
        this.workoutPlanMapper = workoutPlanMapper;
        this.workoutPlanItemMapper = workoutPlanItemMapper;
        this.exerciseDictMapper = exerciseDictMapper;
        this.nutritionService = nutritionService;
    }

    /** 每日 00:05 自动执行（cron 可通过 fitness.scheduler.daily-macro-cron 覆盖）。 */
    @Scheduled(cron = "${fitness.scheduler.daily-macro-cron:0 5 0 * * ?}")
    public void scheduleDailyMacroTargets() {
        DailyMacroScheduleResultVO result = runOnce();
        log.info("每日营养目标调度完成：日期={} 处理用户={} HIGH={} MEDIUM={} LOW={} 失败={}",
                result.date(), result.usersProcessed(), result.highCarbDays(),
                result.mediumCarbDays(), result.lowCarbDays(), result.failed());
    }

    /**
     * 立即执行一次调度（供手动触发与测试使用，与定时任务共用同一段逻辑）。
     */
    @Transactional
    public DailyMacroScheduleResultVO runOnce() {
        LocalDate today = LocalDate.now();
        int dayOfWeek = today.getDayOfWeek().getValue();
        List<WorkoutPlan> activePlans = workoutPlanMapper.selectList(Wrappers.<WorkoutPlan>lambdaQuery()
                .eq(WorkoutPlan::getStatus, STATUS_ACTIVE));

        List<String> details = new ArrayList<>();
        int high = 0;
        int medium = 0;
        int low = 0;
        int failed = 0;

        for (WorkoutPlan plan : activePlans) {
            Long userId = plan.getUserId();
            List<WorkoutPlanItem> items = workoutPlanItemMapper.selectList(Wrappers.<WorkoutPlanItem>lambdaQuery()
                    .eq(WorkoutPlanItem::getPlanId, plan.getId())
                    .eq(WorkoutPlanItem::getDayOfWeek, dayOfWeek));

            String dayType = decideDayType(items);
            try {
                nutritionService.generateTarget(new MacroTargetGenerateDTO(userId, today, dayType));
                switch (dayType) {
                    case HIGH_CARB -> high++;
                    case MEDIUM_CARB -> medium++;
                    default -> low++;
                }
                String detail = "用户 " + userId + "（计划 " + plan.getPlanName() + "）：当日 "
                        + items.size() + " 个动作 / " + totalSets(items) + " 组 → " + dayType;
                details.add(detail);
                log.info("[每日调度] {}", detail);
            } catch (BusinessException e) {
                failed++;
                details.add("用户 " + userId + " 生成失败：" + e.getMessage());
                log.warn("[每日调度] 用户 {} 生成 {} 目标失败：{}", userId, dayType, e.getMessage());
            }
        }
        return new DailyMacroScheduleResultVO(today, activePlans.size(), high, medium, low, failed, details);
    }

    /** 依据当日课表决定碳水日型。 */
    private String decideDayType(List<WorkoutPlanItem> items) {
        if (items.isEmpty()) {
            return LOW_CARB;
        }
        if (totalSets(items) >= HIGH_CARB_SET_THRESHOLD || hitsBigMuscle(items)) {
            return HIGH_CARB;
        }
        return MEDIUM_CARB;
    }

    private int totalSets(List<WorkoutPlanItem> items) {
        return items.stream()
                .map(WorkoutPlanItem::getTargetSets)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private boolean hitsBigMuscle(List<WorkoutPlanItem> items) {
        Set<Long> exerciseIds = new LinkedHashSet<>();
        for (WorkoutPlanItem item : items) {
            if (item.getExerciseId() != null) {
                exerciseIds.add(item.getExerciseId());
            }
        }
        if (exerciseIds.isEmpty()) {
            return false;
        }
        return exerciseDictMapper.selectBatchIds(exerciseIds).stream()
                .map(ExerciseDict::getTargetMuscle)
                .filter(Objects::nonNull)
                .anyMatch(BIG_MUSCLES::contains);
    }
}