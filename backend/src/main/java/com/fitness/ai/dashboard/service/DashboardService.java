package com.fitness.ai.dashboard.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.catalog.dto.MealItemDTO;
import com.fitness.ai.catalog.dto.WorkoutTemplateApplyDTO;
import com.fitness.ai.catalog.service.CatalogService;
import com.fitness.ai.catalog.vo.WorkoutTemplateApplyVO;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.dashboard.vo.DashboardOverviewVO;
import com.fitness.ai.dashboard.vo.DemoSeedResultVO;
import com.fitness.ai.dashboard.vo.MuscleVolumeVO;
import com.fitness.ai.dashboard.vo.NutritionTrendVO;
import com.fitness.ai.dashboard.vo.StrengthTrendVO;
import com.fitness.ai.nutrition.dto.DietLogCreateDTO;
import com.fitness.ai.nutrition.dto.MacroTargetGenerateDTO;
import com.fitness.ai.nutrition.entity.DailyMacroTarget;
import com.fitness.ai.nutrition.entity.DietLog;
import com.fitness.ai.nutrition.mapper.DailyMacroTargetMapper;
import com.fitness.ai.nutrition.mapper.DietLogMapper;
import com.fitness.ai.nutrition.service.NutritionService;
import com.fitness.ai.nutrition.vo.DailyNutritionSummaryVO;
import com.fitness.ai.profile.dto.InjuryTagCreateDTO;
import com.fitness.ai.profile.entity.UserInjuryTag;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.profile.vo.UserDigitalTwinVO;
import com.fitness.ai.workout.dto.WorkoutLogCreateDTO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.entity.WorkoutLog;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.mapper.ExerciseDictMapper;
import com.fitness.ai.workout.mapper.WorkoutLogMapper;
import com.fitness.ai.workout.service.WorkoutService;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 数据可视化统计看板 + 14 天演示数据播种器。
 *
 * <p>全部为确定性 Java 聚合，不涉及大模型。所有体质/营养数值都复用 Service 层的计算与护栏逻辑。</p>
 */
@Slf4j
@Service
public class DashboardService {

    /** 力量走势默认分析窗口（天） */
    private static final int DEFAULT_STRENGTH_DAYS = 90;
    /** 肌群容量默认分析窗口（天） */
    private static final int DEFAULT_VOLUME_DAYS = 30;
    /** 营养趋势默认分析窗口（天） */
    private static final int DEFAULT_NUTRITION_DAYS = 14;
    /** 演示数据天数 */
    private static final int DEMO_DAYS = 14;
    /** 演示饮食天数 */
    private static final int DEMO_NUTRITION_DAYS = 7;

    /** 力量走势关注的核心动作关键字（三大项） */
    private static final List<String> CORE_LIFT_KEYWORDS = List.of("深蹲", "卧推", "硬拉");
    /** 演示数据中刻意制造疲劳的动作关键字（用于覆盖 DELOAD_NEEDED 分支） */
    private static final List<String> DEMO_FATIGUE_KEYWORDS = List.of("弯举");
    /** 下肢加重幅度，仅用于演示数据的重量递增 */
    private static final BigDecimal DEMO_LEG_INCREMENT = new BigDecimal("5.0");
    private static final BigDecimal DEMO_UPPER_INCREMENT = new BigDecimal("2.5");

    /** 演示用三餐（食物名全部来自本地字典，避免临时拼凑导致折算失败） */
    private static final List<DemoMeal> DEMO_MEALS = List.of(
            new DemoMeal("BREAKFAST", List.of(
                    new MealItemDTO("燕麦片（干）", new BigDecimal("60")),
                    new MealItemDTO("鸡蛋（全蛋）", new BigDecimal("100")),
                    new MealItemDTO("脱脂牛奶", new BigDecimal("250")))),
            new DemoMeal("LUNCH", List.of(
                    new MealItemDTO("生鸡胸肉", new BigDecimal("150")),
                    new MealItemDTO("熟米饭", new BigDecimal("300")),
                    new MealItemDTO("西兰花", new BigDecimal("150")))),
            new DemoMeal("DINNER", List.of(
                    new MealItemDTO("瘦牛肉（生）", new BigDecimal("150")),
                    new MealItemDTO("红薯（生）", new BigDecimal("200")),
                    new MealItemDTO("菠菜", new BigDecimal("200")))));

    private final ProfileService profileService;
    private final WorkoutService workoutService;
    private final NutritionService nutritionService;
    private final CatalogService catalogService;
    private final WorkoutLogMapper workoutLogMapper;
    private final DietLogMapper dietLogMapper;
    private final DailyMacroTargetMapper dailyMacroTargetMapper;
    private final ExerciseDictMapper exerciseDictMapper;
    private final com.fitness.ai.workout.mapper.WorkoutPlanMapper workoutPlanMapper;
    private final com.fitness.ai.workout.mapper.WorkoutPlanItemMapper workoutPlanItemMapper;

    public DashboardService(ProfileService profileService,
                            WorkoutService workoutService,
                            NutritionService nutritionService,
                            CatalogService catalogService,
                            WorkoutLogMapper workoutLogMapper,
                            DietLogMapper dietLogMapper,
                            DailyMacroTargetMapper dailyMacroTargetMapper,
                            ExerciseDictMapper exerciseDictMapper,
                            com.fitness.ai.workout.mapper.WorkoutPlanMapper workoutPlanMapper,
                            com.fitness.ai.workout.mapper.WorkoutPlanItemMapper workoutPlanItemMapper) {
        this.profileService = profileService;
        this.workoutService = workoutService;
        this.nutritionService = nutritionService;
        this.catalogService = catalogService;
        this.workoutLogMapper = workoutLogMapper;
        this.dietLogMapper = dietLogMapper;
        this.dailyMacroTargetMapper = dailyMacroTargetMapper;
        this.exerciseDictMapper = exerciseDictMapper;
        this.workoutPlanMapper = workoutPlanMapper;
        this.workoutPlanItemMapper = workoutPlanItemMapper;
    }

    // ==================================================================
    // 顶部核心指标
    // ==================================================================

    @Transactional(readOnly = true)
    public DashboardOverviewVO overview(Long userId) {
        UserDigitalTwinVO twin = profileService.getDigitalTwin(userId);
        LocalDate today = LocalDate.now();

        DailyNutritionSummaryVO nutrition = nutritionService.summary(userId, today);

        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        List<WorkoutLog> weekLogs = workoutLogMapper.selectList(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, userId)
                .ge(WorkoutLog::getLogDate, weekStart)
                .le(WorkoutLog::getLogDate, weekEnd));
        int completedWorkouts = (int) weekLogs.stream()
                .map(WorkoutLog::getLogDate).filter(Objects::nonNull).distinct().count();

        int plannedDays = 0;
        try {
            WorkoutPlanVO plan = workoutService.getActivePlan(userId);
            plannedDays = (int) plan.items().stream()
                    .map(WorkoutPlanItem::getDayOfWeek).filter(Objects::nonNull).distinct().count();
        } catch (BusinessException e) {
            log.debug("用户 {} 暂无 ACTIVE 计划，计划训练天数按 0 计", userId);
        }

        LocalDate from30 = today.minusDays(29);
        List<WorkoutLog> logs30 = workoutLogMapper.selectList(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, userId)
                .ge(WorkoutLog::getLogDate, from30)
                .le(WorkoutLog::getLogDate, today));
        BigDecimal volume30 = logs30.stream()
                .map(WorkoutLog::getTotalVolume)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(1, RoundingMode.HALF_UP);

        return new DashboardOverviewVO(twin.weightKg(), twin.bodyFatPct(), twin.bmr(), twin.tdee(),
                twin.fitnessGoal(), nutrition.consumedCalories(), nutrition.targetCalories(),
                nutrition.remainingCalories(), completedWorkouts, plannedDays,
                twin.activeInjuries() == null ? 0 : twin.activeInjuries().size(),
                volume30, logs30.size());
    }

    // ==================================================================
    // 力量走势
    // ==================================================================

    @Transactional(readOnly = true)
    public StrengthTrendVO strengthTrend(Long userId, Integer days) {
        profileService.getDigitalTwin(userId);
        int window = normalizeDays(days, DEFAULT_STRENGTH_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(window - 1L);

        List<WorkoutLog> rawLogs = workoutLogMapper.selectList(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, userId)
                .ge(WorkoutLog::getLogDate, from)
                .isNotNull(WorkoutLog::getExerciseName));
        List<WorkoutLog> logs = rawLogs.stream().filter(l -> l.getEstimated1rm() != null).toList();

        // 优先展示三大项；若没有三大项记录，退化为打卡次数最多的前 3 个动作
        List<String> coreLiftNames = logs.stream()
                .map(WorkoutLog::getExerciseName)
                .filter(Objects::nonNull)
                .filter(n -> CORE_LIFT_KEYWORDS.stream().anyMatch(n::contains))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        List<String> names = coreLiftNames.isEmpty()
                ? logs.stream()
                .collect(Collectors.groupingBy(WorkoutLog::getExerciseName, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList())
                : coreLiftNames;

        List<String> dates = logs.stream()
                .filter(l -> names.contains(l.getExerciseName()))
                .map(l -> l.getLogDate().toString())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        Map<Long, String> muscleByExercise = muscleMap(logs.stream()
                .map(WorkoutLog::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet()));

        List<StrengthTrendVO.Series> series = new ArrayList<>();
        for (String name : names) {
            Map<String, BigDecimal> maxByDate = new HashMap<>();
            String muscle = null;
            for (WorkoutLog log : logs) {
                if (!name.equals(log.getExerciseName())) {
                    continue;
                }
                String date = log.getLogDate().toString();
                BigDecimal value = log.getEstimated1rm();
                maxByDate.merge(date, value, (a, b) -> a.compareTo(b) >= 0 ? a : b);
                if (muscle == null && log.getExerciseId() != null) {
                    muscle = muscleByExercise.get(log.getExerciseId());
                }
            }
            List<BigDecimal> values = dates.stream().map(maxByDate::get).toList();
            series.add(new StrengthTrendVO.Series(name, muscle, values));
        }
        log.info("用户 {} 力量走势：窗口 {} 天，日期点 {} 个，曲线 {} 条", userId, window, dates.size(), series.size());
        return new StrengthTrendVO(from, today, dates, series);
    }

    // ==================================================================
    // 肌群容量分布
    // ==================================================================

    @Transactional(readOnly = true)
    public MuscleVolumeVO muscleVolume(Long userId, Integer days) {
        profileService.getDigitalTwin(userId);
        int window = normalizeDays(days, DEFAULT_VOLUME_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(window - 1L);

        List<WorkoutLog> logs = workoutLogMapper.selectList(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, userId)
                .ge(WorkoutLog::getLogDate, from)
                .isNotNull(WorkoutLog::getExerciseId));

        Map<Long, String> muscleByExercise = muscleMap(logs.stream()
                .map(WorkoutLog::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet()));

        Map<String, BigDecimal> volumeByMuscle = new LinkedHashMap<>();
        Map<String, Integer> setsByMuscle = new LinkedHashMap<>();
        Map<String, Integer> countByMuscle = new LinkedHashMap<>();
        for (WorkoutLog log : logs) {
            String muscle = muscleByExercise.getOrDefault(log.getExerciseId(), "UNKNOWN");
            volumeByMuscle.merge(muscle, log.getTotalVolume() == null ? BigDecimal.ZERO : log.getTotalVolume(),
                    BigDecimal::add);
            setsByMuscle.merge(muscle, log.getActualSets() == null ? 0 : log.getActualSets(), Integer::sum);
            countByMuscle.merge(muscle, 1, Integer::sum);
        }
        BigDecimal totalVolume = volumeByMuscle.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalSets = setsByMuscle.values().stream().mapToInt(Integer::intValue).sum();

        List<MuscleVolumeVO.MuscleStat> muscles = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : volumeByMuscle.entrySet()) {
            BigDecimal volume = entry.getValue().setScale(1, RoundingMode.HALF_UP);
            BigDecimal percent = totalVolume.signum() == 0 ? BigDecimal.ZERO
                    : entry.getValue().multiply(new BigDecimal("100"))
                    .divide(totalVolume, 1, RoundingMode.HALF_UP);
            muscles.add(new MuscleVolumeVO.MuscleStat(entry.getKey(), volume,
                    setsByMuscle.getOrDefault(entry.getKey(), 0),
                    countByMuscle.getOrDefault(entry.getKey(), 0), percent));
        }
        muscles.sort(Comparator.comparing(MuscleVolumeVO.MuscleStat::totalVolume).reversed());
        log.info("用户 {} 肌群容量分布：窗口 {} 天，总容量 {} kg，覆盖 {} 个肌群", userId, window, totalVolume, muscles.size());
        return new MuscleVolumeVO(from, today, totalVolume.setScale(1, RoundingMode.HALF_UP), totalSets, muscles);
    }

    // ==================================================================
    // 营养趋势
    // ==================================================================

    @Transactional(readOnly = true)
    public NutritionTrendVO nutritionTrend(Long userId, Integer days) {
        profileService.getDigitalTwin(userId);
        int window = normalizeDays(days, DEFAULT_NUTRITION_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(window - 1L);

        List<DietLog> logs = dietLogMapper.selectList(Wrappers.<DietLog>lambdaQuery()
                .eq(DietLog::getUserId, userId)
                .ge(DietLog::getLogDate, from)
                .le(DietLog::getLogDate, today));
        List<DailyMacroTarget> targets = dailyMacroTargetMapper.selectList(Wrappers.<DailyMacroTarget>lambdaQuery()
                .eq(DailyMacroTarget::getUserId, userId)
                .ge(DailyMacroTarget::getTargetDate, from)
                .le(DailyMacroTarget::getTargetDate, today));
        Map<LocalDate, DailyMacroTarget> targetByDate = targets.stream()
                .collect(Collectors.toMap(DailyMacroTarget::getTargetDate, t -> t, (a, b) -> a));

        List<String> dates = new ArrayList<>();
        List<Integer> consumedCalories = new ArrayList<>();
        List<Integer> targetCalories = new ArrayList<>();
        List<BigDecimal> consumedProtein = new ArrayList<>();
        List<BigDecimal> consumedCarbs = new ArrayList<>();
        List<BigDecimal> consumedFat = new ArrayList<>();
        List<Integer> targetProtein = new ArrayList<>();
        List<Integer> targetCarbs = new ArrayList<>();
        List<Integer> targetFat = new ArrayList<>();

        for (int offset = window - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            List<DietLog> dayLogs = logs.stream().filter(l -> date.equals(l.getLogDate())).toList();
            DailyMacroTarget target = targetByDate.get(date);

            dates.add(date.toString());
            consumedCalories.add(dayLogs.stream().map(DietLog::getCalories).filter(Objects::nonNull)
                    .mapToInt(Integer::intValue).sum());
            consumedProtein.add(sumMacro(dayLogs.stream().map(DietLog::getProteinG).toList()));
            consumedCarbs.add(sumMacro(dayLogs.stream().map(DietLog::getCarbsG).toList()));
            consumedFat.add(sumMacro(dayLogs.stream().map(DietLog::getFatG).toList()));
            targetCalories.add(target == null ? null : target.getTargetCalories());
            targetProtein.add(target == null ? null : target.getTargetProteinG());
            targetCarbs.add(target == null ? null : target.getTargetCarbsG());
            targetFat.add(target == null ? null : target.getTargetFatG());
        }
        log.info("用户 {} 营养趋势：窗口 {} 天，有打卡天数 {} 天",
                userId, window, consumedCalories.stream().filter(c -> c > 0).count());
        return new NutritionTrendVO(from, today, dates, consumedCalories, targetCalories,
                consumedProtein, consumedCarbs, consumedFat, targetProtein, targetCarbs, targetFat);
    }

    // ==================================================================
    // 14 天演示数据播种（幂等）
    // ==================================================================

    /**
     * 一键播种 14 天演示数据：先清空该用户的业务流水，再重建一套安全课表 + 训练打卡 + 饮食账单。
     *
     * <p>幂等：重复调用会先删除上一次播种的流水再重建，最终状态一致（记录 ID 会变化）。
     * 工具审计日志（tool_audit_log）属于可观测性历史，不会被清理。</p>
     */
    @Transactional
    public DemoSeedResultVO demoSeed(Long userId) {
        UserDigitalTwinVO twin = profileService.getDigitalTwin(userId);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(DEMO_DAYS - 1L);

        // 1) 清理旧演示流水
        int removedLogs = workoutLogMapper.delete(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, userId));
        int removedDiets = dietLogMapper.delete(Wrappers.<DietLog>lambdaQuery()
                .eq(DietLog::getUserId, userId));
        int removedTargets = dailyMacroTargetMapper.delete(Wrappers.<DailyMacroTarget>lambdaQuery()
                .eq(DailyMacroTarget::getUserId, userId));
        int removedItems = workoutPlanItemMapper.delete(Wrappers.<WorkoutPlanItem>lambdaQuery()
                .eq(WorkoutPlanItem::getUserId, userId));
        int removedPlans = workoutPlanMapper.delete(Wrappers.<com.fitness.ai.workout.entity.WorkoutPlan>lambdaQuery()
                .eq(com.fitness.ai.workout.entity.WorkoutPlan::getUserId, userId));
        log.info("[演示播种] 已清理用户 {} 旧流水：workout_log={} diet_log={} macro_target={} plan_item={} plan={}",
                userId, removedLogs, removedDiets, removedTargets, removedItems, removedPlans);

        // 2) 生成一套自动避开当前伤病的激活周课表
        //    systemExercisesOnly=true：演示课表只用系统内置动作，避免用户后来新建的自定义动作
        //    让演示场景（动作数 / 打卡数）不可复现
        WorkoutTemplateApplyVO applied = catalogService.applyWorkoutTemplate(
                new WorkoutTemplateApplyDTO(userId, "PPL_3DAY"), true);
        WorkoutPlanVO plan = workoutService.getActivePlan(userId);
        Map<Long, ExerciseDict> exerciseById = muscleMapEntities(plan.items());

        // 3) 倒推 14 天训练打卡（重量逐周递增、RPE 温和，满足 OVERLOAD_READY 条件）
        Set<LocalDate> trainingDates = new TreeSet<>();
        int workoutLogs = 0;
        for (int offset = DEMO_DAYS - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            int dayOfWeek = date.getDayOfWeek().getValue();
            List<WorkoutPlanItem> dayItems = plan.items().stream()
                    .filter(i -> i.getDayOfWeek() != null && i.getDayOfWeek() == dayOfWeek)
                    .toList();
            if (dayItems.isEmpty()) {
                continue;
            }
            boolean newerWeek = offset < DEMO_NUTRITION_DAYS;
            for (WorkoutPlanItem item : dayItems) {
                ExerciseDict exercise = exerciseById.get(item.getExerciseId());
                String muscle = exercise == null ? null : exercise.getTargetMuscle();
                BigDecimal base = item.getTargetWeightKg() == null ? BigDecimal.ZERO : item.getTargetWeightKg();
                BigDecimal increment = "LEGS".equalsIgnoreCase(muscle) ? DEMO_LEG_INCREMENT : DEMO_UPPER_INCREMENT;
                BigDecimal weight = newerWeek ? base.add(increment) : base;

                boolean fatigued = item.getExerciseName() != null
                        && DEMO_FATIGUE_KEYWORDS.stream().anyMatch(item.getExerciseName()::contains);
                int reps = fatigued ? Math.max(1, (item.getTargetReps() == null ? 10 : item.getTargetReps()) - 2)
                        : (item.getTargetReps() == null ? 10 : item.getTargetReps());
                BigDecimal rpe = fatigued ? new BigDecimal("9.6") : (newerWeek ? new BigDecimal("7.5") : new BigDecimal("7.0"));

                workoutService.logWorkout(new WorkoutLogCreateDTO(userId, item.getId(), date, item.getExerciseId(),
                        item.getTargetSets(), reps, weight, rpe, "演示数据：" + item.getExerciseName()));
                workoutLogs++;
                trainingDates.add(date);
            }
        }

        // 4) 倒推 7 天饮食账单（训练日 HIGH_CARB / 休息日 LOW_CARB）
        int dietLogs = 0;
        int macroTargets = 0;
        for (int offset = DEMO_NUTRITION_DAYS - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            String dayType = trainingDates.contains(date) ? "HIGH_CARB" : "LOW_CARB";
            nutritionService.generateTarget(new MacroTargetGenerateDTO(userId, date, dayType));
            macroTargets++;
            for (DemoMeal meal : DEMO_MEALS) {
                for (MealItemDTO item : meal.items()) {
                    nutritionService.addDietLog(new DietLogCreateDTO(userId, date, meal.mealType(),
                            item.foodName(), item.weightG(), null, null, null, null));
                    dietLogs++;
                }
            }
        }

        // 5) 确保演示伤病夹具处于 ACTIVE：整套「避开伤病」的演示（安全动作过滤、模板跳槽位、
//    排课白名单、护栏拦截）都依赖它。addInjury 天然幂等：已有 ACTIVE 记录则更新描述，否则新建。
        boolean hadActiveShoulder = (twin.activeInjuries() == null ? List.<UserInjuryTag>of() : twin.activeInjuries())
                .stream()
                .anyMatch(injury -> "LEFT_SHOULDER".equalsIgnoreCase(injury.getBodyPart())
                        && "ACTIVE".equalsIgnoreCase(injury.getStatus()));
        profileService.addInjury(userId, new InjuryTagCreateDTO("LEFT_SHOULDER",
                "卧推与推举时左肩前侧有轻微不适，无活动受限，暂不建议大重量过头推举", "MILD"));
        boolean injuryEnsured = !hadActiveShoulder;

        String note = "已清理旧流水并重建：课表 " + applied.itemCount() + " 个动作（跳过 "
                + applied.skippedSlots().size() + " 个伤病冲突槽位）、训练打卡 " + workoutLogs
                + " 条、饮食打卡 " + dietLogs + " 条、营养目标 " + macroTargets + " 天。"
                + "其中「弯举」类动作刻意记录了高 RPE 疲劳样本，用于覆盖 DELOAD_NEEDED 分支。"
                + (injuryEnsured
                        ? "另已恢复演示伤病标签 LEFT_SHOULDER（ACTIVE）——伤病护栏演示依赖它处于活跃状态。"
                        : "演示伤病标签 LEFT_SHOULDER 已处于 ACTIVE。");
        log.info("[演示播种] 用户 {} 完成：{}", userId, note);
        return new DemoSeedResultVO(userId, DEMO_DAYS, from, today, plan.id(), plan.planName(),
                applied.itemCount(), workoutLogs, dietLogs, macroTargets, note);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private int normalizeDays(Integer days, int defaultValue) {
        int value = days == null ? defaultValue : days;
        if (value < 1 || value > 365) {
            throw new BusinessException(com.fitness.ai.common.ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "days=" + value + " 超出分析窗口，必须在 1 ~ 365 天之间");
        }
        return value;
    }

    private Map<Long, String> muscleMap(Set<Long> exerciseIds) {
        if (exerciseIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new LinkedHashMap<>();
        for (ExerciseDict exercise : exerciseDictMapper.selectBatchIds(exerciseIds)) {
            map.put(exercise.getId(), exercise.getTargetMuscle());
        }
        return map;
    }

    private Map<Long, ExerciseDict> muscleMapEntities(List<WorkoutPlanItem> items) {
        Set<Long> ids = items.stream().map(WorkoutPlanItem::getExerciseId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ExerciseDict> map = new LinkedHashMap<>();
        for (ExerciseDict exercise : exerciseDictMapper.selectBatchIds(ids)) {
            map.put(exercise.getId(), exercise);
        }
        return map;
    }

    private static BigDecimal sumMacro(List<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 演示用的一餐。 */
    private record DemoMeal(String mealType, List<MealItemDTO> items) {
    }
}