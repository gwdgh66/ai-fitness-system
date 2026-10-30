package com.fitness.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.catalog.dto.MealItemDTO;
import com.fitness.ai.catalog.dto.MealTemplateCreateDTO;
import com.fitness.ai.catalog.dto.WorkoutTemplateApplyDTO;
import com.fitness.ai.catalog.entity.MealTemplate;
import com.fitness.ai.catalog.mapper.MealTemplateMapper;
import com.fitness.ai.catalog.service.CatalogService;
import com.fitness.ai.catalog.vo.MealTemplateApplyVO;
import com.fitness.ai.catalog.vo.WorkoutTemplateApplyVO;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.dashboard.service.DashboardService;
import com.fitness.ai.dashboard.vo.DashboardOverviewVO;
import com.fitness.ai.dashboard.vo.DemoSeedResultVO;
import com.fitness.ai.dashboard.vo.MuscleVolumeVO;
import com.fitness.ai.dashboard.vo.NutritionTrendVO;
import com.fitness.ai.dashboard.vo.StrengthTrendVO;
import com.fitness.ai.nutrition.dto.DietLogCreateDTO;
import com.fitness.ai.nutrition.entity.DailyMacroTarget;
import com.fitness.ai.nutrition.vo.DietLogClearVO;
import com.fitness.ai.nutrition.entity.DietLog;
import com.fitness.ai.nutrition.mapper.DailyMacroTargetMapper;
import com.fitness.ai.nutrition.mapper.DietLogMapper;
import com.fitness.ai.nutrition.service.NutritionService;
import com.fitness.ai.observability.entity.ToolAuditLog;
import com.fitness.ai.observability.mapper.ToolAuditLogMapper;
import com.fitness.ai.profile.dto.InjuryTagCreateDTO;
import com.fitness.ai.profile.entity.UserInjuryTag;
import com.fitness.ai.profile.entity.UserProfile;
import com.fitness.ai.profile.mapper.UserInjuryTagMapper;
import com.fitness.ai.profile.mapper.UserProfileMapper;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.progression.service.ProgressionService;
import com.fitness.ai.progression.vo.ProgressionApplyVO;
import com.fitness.ai.progression.vo.ProgressionRecommendationVO;
import com.fitness.ai.scheduler.DailyMacroScheduler;
import com.fitness.ai.scheduler.vo.DailyMacroScheduleResultVO;
import com.fitness.ai.workout.dto.CustomExerciseCreateDTO;
import com.fitness.ai.workout.dto.WorkoutLogCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanItemDTO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.entity.WorkoutLog;
import com.fitness.ai.workout.entity.WorkoutPlan;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.mapper.ExerciseDictMapper;
import com.fitness.ai.workout.mapper.WorkoutLogMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanItemMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanMapper;
import com.fitness.ai.workout.service.WorkoutService;
import com.fitness.ai.workout.vo.CustomExerciseDeleteVO;
import com.fitness.ai.workout.vo.PlanItemDeleteVO;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 6 步四大进阶模块的集成验证（纯 Java，不需要大模型）。
 *
 * <p>其中 {@code userId = 1} 的 14 天演示数据会<b>刻意保留</b>（供后续前端直接展示图表），
 * 其余用例一律使用一次性测试用户并在结束时彻底清理。</p>
 */
@Slf4j
@SpringBootTest
class AdvancedModulesTest {

    private static final Long DEMO_USER_ID = 1L;
    private static final Long BARBELL_SQUAT = 12L;
    private static final Long ROMANIAN_DEADLIFT = 14L;
    private static final Long LEG_PRESS = 15L;

    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private ProgressionService progressionService;
    @Autowired
    private CatalogService catalogService;
    @Autowired
    private DailyMacroScheduler dailyMacroScheduler;
    @Autowired
    private NutritionService nutritionService;
    @Autowired
    private ProfileService profileService;
    @Autowired
    private WorkoutService workoutService;
    @Autowired
    private UserProfileMapper userProfileMapper;
    @Autowired
    private UserInjuryTagMapper userInjuryTagMapper;
    @Autowired
    private WorkoutPlanMapper workoutPlanMapper;
    @Autowired
    private WorkoutPlanItemMapper workoutPlanItemMapper;
    @Autowired
    private WorkoutLogMapper workoutLogMapper;
    @Autowired
    private DietLogMapper dietLogMapper;
    @Autowired
    private DailyMacroTargetMapper dailyMacroTargetMapper;
    @Autowired
    private MealTemplateMapper mealTemplateMapper;
    @Autowired
    private ToolAuditLogMapper toolAuditLogMapper;
    @Autowired
    private ExerciseDictMapper exerciseDictMapper;

    // ==================================================================
    // 验证点 1：演示播种 + 4 个看板接口都有饱满数据
    // ==================================================================

    @Test
    void demoSeedThenAllDashboardChartsArePopulated() {
        long activeInjuriesBefore = userInjuryTagMapper.selectCount(Wrappers.<UserInjuryTag>lambdaQuery()
                .eq(UserInjuryTag::getUserId, DEMO_USER_ID)
                .eq(UserInjuryTag::getStatus, "ACTIVE"));
        DemoSeedResultVO seed = dashboardService.demoSeed(DEMO_USER_ID);
        log.info("[验证1] 播种结果：课表 {}（{} 个动作）训练打卡 {} 条 饮食打卡 {} 条 营养目标 {} 天",
                seed.planName(), seed.planItems(), seed.workoutLogs(), seed.dietLogs(), seed.macroTargets());
        assertThat(seed.workoutLogs()).isGreaterThan(0);
        // 7 天 × 3 餐 × 3 种食物 = 63 条 diet_log
        assertThat(seed.dietLogs()).isEqualTo(63);
        assertThat(seed.macroTargets()).isEqualTo(7);

        DashboardOverviewVO overview = dashboardService.overview(DEMO_USER_ID);
        assertThat(overview.bmr()).isNotNull();
        assertThat(overview.tdee()).isNotNull();
        assertThat(overview.totalVolumeLast30Days()).isGreaterThan(BigDecimal.ZERO);
        assertThat(overview.workoutLogCountLast30Days()).isGreaterThan(0);
        assertThat(overview.plannedTrainingDaysThisWeek()).isGreaterThan(0);
        // 播种不得改变伤病状态：用户可能已经把左肩标记为 RECOVERED，
        // 播种只在「从未有过该部位记录」时才补建，绝不能把已恢复的伤病重新激活。
        long activeInjuriesAfter = userInjuryTagMapper.selectCount(Wrappers.<UserInjuryTag>lambdaQuery()
                .eq(UserInjuryTag::getUserId, DEMO_USER_ID)
                .eq(UserInjuryTag::getStatus, "ACTIVE"));
        assertThat(activeInjuriesAfter)
                .as("播种前后活跃伤病数量必须一致（不得复活用户已标记恢复的伤病）")
                .isEqualTo(activeInjuriesBefore);
        log.info("[验证1] 指标卡：体重={}kg BMR={} TDEE={} 今日摄入={}/{} kcal 本周完成={}/{} 天 活跃伤病={} 近30天容量={}kg（{} 条打卡）",
                overview.weightKg(), overview.bmr(), overview.tdee(), overview.consumedCaloriesToday(),
                overview.targetCaloriesToday(), overview.completedWorkoutsThisWeek(),
                overview.plannedTrainingDaysThisWeek(), overview.activeInjuries(),
                overview.totalVolumeLast30Days(), overview.workoutLogCountLast30Days());

        StrengthTrendVO strength = dashboardService.strengthTrend(DEMO_USER_ID, null);
        assertThat(strength.dates()).isNotEmpty();
        assertThat(strength.series()).isNotEmpty();
        assertThat(strength.series()).anyMatch(s -> s.estimated1rm().stream().anyMatch(Objects::nonNull));
        strength.series().forEach(s -> log.info("[验证1] 力量曲线：{}（{}）1RM 序列={}", s.exerciseName(),
                s.targetMuscle(), s.estimated1rm()));
        log.info("[验证1] 力量走势横轴日期：{}", strength.dates());

        MuscleVolumeVO volume = dashboardService.muscleVolume(DEMO_USER_ID, null);
        assertThat(volume.muscles()).isNotEmpty();
        assertThat(volume.totalVolume()).isGreaterThan(BigDecimal.ZERO);
        assertThat(volume.totalSets()).isGreaterThan(0);
        volume.muscles().forEach(m -> log.info("[验证1] 肌群容量：{} 总容量={}kg 总组数={} 次数={} 占比={}%",
                m.targetMuscle(), m.totalVolume(), m.totalSets(), m.logCount(), m.volumePercent()));

        NutritionTrendVO nutrition = dashboardService.nutritionTrend(DEMO_USER_ID, null);
        assertThat(nutrition.dates()).hasSize(14);
        assertThat(nutrition.consumedCalories()).anyMatch(c -> c > 0);
        assertThat(nutrition.targetCalories()).anyMatch(Objects::nonNull);
        for (int i = 0; i < nutrition.dates().size(); i++) {
            log.info("[验证1] 营养趋势 {}：摄入 {} kcal / 目标 {} kcal",
                    nutrition.dates().get(i), nutrition.consumedCalories().get(i), nutrition.targetCalories().get(i));
        }
    }

    // ==================================================================
    // 验证点 2：渐进算法识别 OVERLOAD_READY
    // ==================================================================

    @Test
    void progressionDetectsOverloadReadyAndDeloadFromSeededLogs() {
        dashboardService.demoSeed(DEMO_USER_ID);
        List<ProgressionRecommendationVO> recommendations = progressionService.recommend(DEMO_USER_ID);
        assertThat(recommendations).isNotEmpty();
        recommendations.forEach(r -> log.info("[验证2] 动作={} 状态={} 基准={}kg → 建议={}kg（{} 次）理由={}",
                r.exerciseName(), r.status(), r.workingWeightKg(), r.suggestedWeightKg(),
                r.suggestedReps(), r.reason()));

        List<ProgressionRecommendationVO> overloads = recommendations.stream()
                .filter(r -> ProgressionService.STATUS_OVERLOAD_READY.equals(r.status()))
                .toList();
        assertThat(overloads).as("应识别出可加重的动作").isNotEmpty();
        overloads.forEach(r -> {
            BigDecimal delta = r.suggestedWeightKg().subtract(r.workingWeightKg());
            BigDecimal expected = "LEGS".equalsIgnoreCase(r.targetMuscle())
                    ? new BigDecimal("5.0") : new BigDecimal("2.5");
            assertThat(delta).as("下肢 +5.0kg / 其他 +2.5kg").isEqualByComparingTo(expected);
            assertThat(r.sampleSize()).isGreaterThanOrEqualTo(2);
        });
        long overloadCount = overloads.size();

        List<ProgressionRecommendationVO> deloads = recommendations.stream()
                .filter(r -> ProgressionService.STATUS_DELOAD_NEEDED.equals(r.status()))
                .toList();
        assertThat(deloads).as("高 RPE 的弯举动作应触发 DELOAD_NEEDED").isNotEmpty();
        log.info("[验证2] 共 {} 条建议：OVERLOAD_READY={} DELOAD_NEEDED={} MAINTAIN={} INSUFFICIENT_DATA={}",
                recommendations.size(), overloadCount, deloads.size(),
                recommendations.stream().filter(r -> ProgressionService.STATUS_MAINTAIN.equals(r.status())).count(),
                recommendations.stream().filter(r -> ProgressionService.STATUS_INSUFFICIENT_DATA.equals(r.status())).count());
    }

    // ==================================================================
    // 验证点 11：清空某日饮食流水（只删该用户该日期；幂等）
    // ==================================================================

    @Test
    void clearDietLogsOnlyRemovesTargetUserAndDate() {
        Long userId = createDisposableUser();
        Long otherUserId = null;
        try {
            LocalDate today = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);
            // 目标用户：今天 2 条 + 昨天 1 条
            nutritionService.addDietLog(new DietLogCreateDTO(userId, today, "BREAKFAST",
                    "燕麦片（干）", new BigDecimal("50"), null, null, null, null));
            nutritionService.addDietLog(new DietLogCreateDTO(userId, today, "LUNCH",
                    "熟米饭", new BigDecimal("200"), null, null, null, null));
            nutritionService.addDietLog(new DietLogCreateDTO(userId, yesterday, "DINNER",
                    "生鸡胸肉", new BigDecimal("150"), null, null, null, null));
            // 另一个用户：今天 1 条，不应被删
            otherUserId = createDisposableUser();
            nutritionService.addDietLog(new DietLogCreateDTO(otherUserId, today, "BREAKFAST",
                    "燕麦片（干）", new BigDecimal("40"), null, null, null, null));

            long countToday = dietLogMapper.selectCount(Wrappers.<DietLog>lambdaQuery()
                    .eq(DietLog::getUserId, userId).eq(DietLog::getLogDate, today));
            assertThat(countToday).isEqualTo(2L);

            DietLogClearVO cleared = nutritionService.clearDietLogs(userId, today);
            assertThat(cleared.deletedCount()).isEqualTo(2);
            assertThat(cleared.deletedCalories()).isPositive();
            assertThat(cleared.consumedCaloriesAfter()).isZero();
            assertThat(cleared.logDate()).isEqualTo(today);

            // 今天已清空、昨天保留、其他用户不受影响
            assertThat(dietLogMapper.selectCount(Wrappers.<DietLog>lambdaQuery()
                    .eq(DietLog::getUserId, userId).eq(DietLog::getLogDate, today))).isZero();
            assertThat(dietLogMapper.selectCount(Wrappers.<DietLog>lambdaQuery()
                    .eq(DietLog::getUserId, userId).eq(DietLog::getLogDate, yesterday))).isEqualTo(1L);
            assertThat(dietLogMapper.selectCount(Wrappers.<DietLog>lambdaQuery()
                    .eq(DietLog::getUserId, otherUserId).eq(DietLog::getLogDate, today))).isEqualTo(1L);

            // 幂等：再清一次返回 0 条而不是报错
            DietLogClearVO again = nutritionService.clearDietLogs(userId, today);
            assertThat(again.deletedCount()).isZero();
            log.info("[验证11] 清空今日饮食流水：{}", cleared.message());
        } finally {
            if (otherUserId != null) {
                cleanup(otherUserId);
            }
            cleanup(userId);
        }
    }

    // ==================================================================
    // 验证点 2b：一键采纳进阶建议写回课表
    // ==================================================================

    @Test
    void applyProgressionRecommendationWritesBackToPlan() {
        Long userId = createDisposableUser();
        try {
            Long itemId = seedLegPlanItem(userId, LocalDate.now().getDayOfWeek().getValue());
            // 两次都完成 8 次、RPE 7.5、重量 60kg → 应判定 OVERLOAD_READY
            createLog(userId, itemId, new BigDecimal("60.0"), 8, new BigDecimal("7.5"));
            createLog(userId, itemId, new BigDecimal("60.0"), 8, new BigDecimal("7.5"));

            ProgressionRecommendationVO recommendation = progressionService.recommend(userId).get(0);
            assertThat(recommendation.status()).isEqualTo(ProgressionService.STATUS_OVERLOAD_READY);
            assertThat(recommendation.suggestedWeightKg()).isEqualByComparingTo("65.0");
            log.info("[验证2b] 采纳前：{} {} → 建议 {}kg", recommendation.exerciseName(),
                    recommendation.status(), recommendation.suggestedWeightKg());

            ProgressionApplyVO applied = progressionService.apply(itemId);
            assertThat(applied.appliedWeightKg()).isEqualByComparingTo("65.0");
            assertThat(workoutService.getPlanItem(itemId).getTargetWeightKg()).isEqualByComparingTo("65.0");
            log.info("[验证2b] 采纳后：{}（{}kg → {}kg）", applied.message(),
                    applied.previousWeightKg(), applied.appliedWeightKg());
        } finally {
            cleanup(userId);
        }
    }

    // ==================================================================
    // 验证点 3：套餐模板（新增自动汇总 + 一键打卡）
    // ==================================================================

    @Test
    void mealTemplateCreateAndApply() {
        Long userId = createDisposableUser();
        try {
            MealTemplate template = catalogService.createMealTemplate(new MealTemplateCreateDTO(userId,
                    "测试增肌早餐", "BREAKFAST", List.of(
                    new MealItemDTO("燕麦片（干）", new BigDecimal("60")),
                    new MealItemDTO("鸡蛋（全蛋）", new BigDecimal("100")),
                    new MealItemDTO("脱脂牛奶", new BigDecimal("250")))));
            assertThat(template.getTotalCalories()).isEqualTo(464);
            assertThat(template.getTotalProteinG()).isEqualByComparingTo("31.24");
            assertThat(template.getTotalCarbsG()).isEqualByComparingTo("52.98");
            assertThat(template.getTotalFatG()).isEqualByComparingTo("13.89");
            log.info("[验证3] 新增套餐模板自动汇总：{} = {} kcal / P{}g / C{}g / F{}g", template.getTemplateName(),
                    template.getTotalCalories(), template.getTotalProteinG(),
                    template.getTotalCarbsG(), template.getTotalFatG());

            MealTemplateApplyVO applied = catalogService.applyMealTemplate(template.getId(), LocalDate.now(), null);
            assertThat(applied.loggedItems()).isEqualTo(3);
            assertThat(applied.addedCalories()).isEqualTo(464);
            List<DietLog> logs = dietLogMapper.selectList(Wrappers.<DietLog>lambdaQuery()
                    .eq(DietLog::getUserId, userId).eq(DietLog::getLogDate, LocalDate.now()));
            assertThat(logs).hasSize(3);
            assertThat(logs).allSatisfy(l -> assertThat(l.getMealType()).isEqualTo("BREAKFAST"));
            log.info("[验证3] 一键套餐打卡写入 {} 条 diet_log，合计 {} kcal，当日已摄入 {} kcal / 目标 {} kcal",
                    logs.size(), applied.addedCalories(), applied.summary().consumedCalories(),
                    applied.summary().targetCalories());
        } finally {
            cleanup(userId);
        }
    }

    // ==================================================================
    // 验证点 4：训练模板一键套用（自动避开伤病）
    // ==================================================================

    @Test
    void workoutTemplateApplySkipsInjuryConflictingSlots() {
        Long userId = createDisposableUser();
        try {
            WorkoutTemplateApplyVO applied = catalogService.applyWorkoutTemplate(
                    new WorkoutTemplateApplyDTO(userId, "PPL_3DAY"));
            assertThat(applied.itemCount()).isGreaterThan(0);
            assertThat(applied.skippedSlots()).as("肩部槽位应因 LEFT_SHOULDER 被跳过")
                    .anyMatch(s -> s.contains("SHOULDERS"));
            log.info("[验证4] 模板排课：{} 生成 {} 个动作，跳过 {} 个槽位", applied.planName(),
                    applied.itemCount(), applied.skippedSlots().size());
            applied.generatedItems().forEach(s -> log.info("[验证4] 生成：{}", s));
            applied.skippedSlots().forEach(s -> log.info("[验证4] 跳过：{}", s));

            List<WorkoutPlanItem> items = workoutPlanItemMapper.selectList(Wrappers.<WorkoutPlanItem>lambdaQuery()
                    .eq(WorkoutPlanItem::getUserId, userId));
            assertThat(items).isNotEmpty();
            for (WorkoutPlanItem item : items) {
                ExerciseDict exercise = exerciseDictMapper.selectById(item.getExerciseId());
                assertThat(exercise).isNotNull();
                assertThat(exercise.getAvoidInjuryParts() == null
                        || !exercise.getAvoidInjuryParts().toUpperCase().contains("LEFT_SHOULDER"))
                        .as("排入的动作不得与 LEFT_SHOULDER 冲突：" + exercise.getName())
                        .isTrue();
            }
        } finally {
            cleanup(userId);
        }
    }

    // ==================================================================
    // 验证点 5：每日调度的碳水日型判定
    // ==================================================================

    @Test
    void schedulerChoosesDayTypeFromTodayTrainingLoad() {
        Long trainingUser = createDisposableUser();
        Long restUser = createDisposableUser();
        try {
            // 今天有大肌群训练 → HIGH_CARB
            seedLegPlanItem(trainingUser, LocalDate.now().getDayOfWeek().getValue());
            // 课表安排在明天，今天没有任何训练 → LOW_CARB
            int tomorrow = LocalDate.now().plusDays(1).getDayOfWeek().getValue();
            seedLegPlanItem(restUser, tomorrow);

            DailyMacroScheduleResultVO result = dailyMacroScheduler.runOnce();
            assertThat(result.usersProcessed()).isGreaterThanOrEqualTo(2);
            result.details().forEach(d -> log.info("[验证5] {}", d));

            DailyMacroTarget trainingTarget = targetOf(trainingUser, LocalDate.now());
            assertThat(trainingTarget.getDayType()).isEqualTo("HIGH_CARB");
            DailyMacroTarget restTarget = targetOf(restUser, LocalDate.now());
            assertThat(restTarget.getDayType()).isEqualTo("LOW_CARB");
            log.info("[验证5] 训练日用户 {} → {}（{} kcal）；休息日用户 {} → {}（{} kcal）",
                    trainingUser, trainingTarget.getDayType(), trainingTarget.getTargetCalories(),
                    restUser, restTarget.getDayType(), restTarget.getTargetCalories());
        } finally {
            cleanup(trainingUser);
            cleanup(restUser);
        }
    }

    // ==================================================================
    // 验证点 6：删除课表明细（顺序重排 / 历史打卡保留 / 归档计划保护 / 404）
    // ==================================================================

    @Test
    void deletePlanItemRemovesItemRenumbersSortOrderAndKeepsLogs() {
        Long userId = createDisposableUser();
        try {
            WorkoutPlanVO plan = workoutService.createPlan(new WorkoutPlanCreateDTO(userId, "DELETE-TEST", "PPL",
                    LocalDate.now(), LocalDate.now().plusWeeks(4),
                    List.of(new WorkoutPlanItemDTO(1, BARBELL_SQUAT, 4, 8, new BigDecimal("60.0"),
                                    new BigDecimal("8.0"), 1, "保留-第1"),
                            new WorkoutPlanItemDTO(1, ROMANIAN_DEADLIFT, 4, 10, new BigDecimal("50.0"),
                                    new BigDecimal("8.0"), 2, "待删除"),
                            new WorkoutPlanItemDTO(1, LEG_PRESS, 4, 12, new BigDecimal("100.0"),
                                    new BigDecimal("8.0"), 3, "保留-第3"))));
            Long firstItemId = plan.items().get(0).getId();
            Long targetItemId = plan.items().get(1).getId();

            // 先给待删动作打一次卡：删除课表后这条历史必须还在
            workoutService.logWorkout(new WorkoutLogCreateDTO(userId, targetItemId, LocalDate.now(),
                    ROMANIAN_DEADLIFT, 4, 10, new BigDecimal("50.0"), new BigDecimal("8.0"), "删除前打卡"));

            PlanItemDeleteVO deleted = workoutService.deletePlanItem(targetItemId);
            log.info("[验证6] 删除结果：{}", deleted.message());
            assertThat(deleted.exerciseName()).isEqualTo("罗马尼亚硬拉");
            assertThat(deleted.dayLabel()).isEqualTo("周一");
            assertThat(deleted.removedSets()).isEqualTo(4);
            assertThat(deleted.remainingItemsOnDay()).isEqualTo(2);
            assertThat(deleted.remainingSetsOnDay()).isEqualTo(8);
            assertThat(deleted.preservedWorkoutLogs()).isEqualTo(1L);
            assertThat(deleted.planEmptied()).isFalse();

            assertThat(workoutPlanItemMapper.selectById(targetItemId)).as("明细应已从库中删除").isNull();
            List<WorkoutPlanItem> rest = workoutPlanItemMapper.selectList(Wrappers.<WorkoutPlanItem>lambdaQuery()
                    .eq(WorkoutPlanItem::getPlanId, plan.id())
                    .orderByAsc(WorkoutPlanItem::getSortOrder));
            assertThat(rest).hasSize(2);
            assertThat(rest).extracting(WorkoutPlanItem::getExerciseName)
                    .containsExactly("杠铃深蹲", "腿举");
            assertThat(rest).extracting(WorkoutPlanItem::getSortOrder)
                    .as("删除后当日顺序应重排为 1..N，不留断号").containsExactly(1, 2);
            assertThat(workoutLogMapper.selectCount(Wrappers.<WorkoutLog>lambdaQuery()
                    .eq(WorkoutLog::getPlanItemId, targetItemId)))
                    .as("训练打卡历史必须保留").isEqualTo(1L);

            // 不存在的明细 → 404
            assertThatThrownBy(() -> workoutService.deletePlanItem(999999999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("课表明细不存在");

            // 新建计划后旧计划被归档 → 归档计划里的动作不允许删除
            workoutService.createPlan(new WorkoutPlanCreateDTO(userId, "DELETE-TEST-2", "PPL",
                    LocalDate.now(), LocalDate.now().plusWeeks(4),
                    List.of(new WorkoutPlanItemDTO(2, LEG_PRESS, 3, 12, new BigDecimal("100.0"),
                            new BigDecimal("8.0"), 1, "新计划"))));
            assertThatThrownBy(() -> workoutService.deletePlanItem(firstItemId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已归档");
            log.info("[验证6] 顺序重排、历史保留、归档保护、404 校验全部通过");
        } finally {
            cleanup(userId);
        }
    }

    // ==================================================================
    // 验证点 7：自定义周课表 —— 同一动作多重量组 + 每个重量组都能打卡
    // ==================================================================

    @Test
    void customPlanSupportsMultipleWeightGroupsPerExerciseAndCanBeLogged() {
        Long userId = createDisposableUser();
        try {
            // 同一动作（杠铃深蹲）排 3 个重量组：80kg×1 组、90kg×2 组、100kg×3 组 → 当日共 6 组
            WorkoutPlanVO plan = workoutService.createPlan(new WorkoutPlanCreateDTO(userId, "自定义三重量组", "FULL_BODY",
                    LocalDate.now(), LocalDate.now().plusWeeks(4),
                    List.of(new WorkoutPlanItemDTO(2, BARBELL_SQUAT, 1, 6, new BigDecimal("80.0"),
                                    new BigDecimal("7.0"), 1, "热身组"),
                            new WorkoutPlanItemDTO(2, BARBELL_SQUAT, 2, 6, new BigDecimal("90.0"),
                                    new BigDecimal("8.0"), 2, "主力组"),
                            new WorkoutPlanItemDTO(2, BARBELL_SQUAT, 3, 6, new BigDecimal("100.0"),
                                    new BigDecimal("8.5"), 3, "顶组"))));

            assertThat(plan.items()).hasSize(3);
            assertThat(plan.items()).allSatisfy(item ->
                    assertThat(item.getExerciseId()).isEqualTo(BARBELL_SQUAT));
            assertThat(plan.items()).extracting(WorkoutPlanItem::getTargetWeightKg)
                    .containsExactly(new BigDecimal("80.00"), new BigDecimal("90.00"), new BigDecimal("100.00"));
            assertThat(plan.items()).extracting(WorkoutPlanItem::getSortOrder).containsExactly(1, 2, 3);
            int daySets = plan.items().stream().mapToInt(WorkoutPlanItem::getTargetSets).sum();
            assertThat(daySets).isEqualTo(6);
            log.info("[验证7] 自定义计划：同一动作 {} 个重量组，当日共 {} 组", plan.items().size(), daySets);

            // 每个重量组都能独立打卡，1RM 由后端 Epley 公式计算（6 次 → 系数 1.2）
            BigDecimal[] weights = {new BigDecimal("80.0"), new BigDecimal("90.0"), new BigDecimal("100.0")};
            BigDecimal[] expected1rm = {new BigDecimal("96.00"), new BigDecimal("108.00"), new BigDecimal("120.00")};
            BigDecimal totalVolume = BigDecimal.ZERO;
            for (int i = 0; i < plan.items().size(); i++) {
                WorkoutPlanItem item = plan.items().get(i);
                WorkoutLog logged = workoutService.logWorkout(new WorkoutLogCreateDTO(userId, item.getId(),
                        LocalDate.now(), BARBELL_SQUAT, item.getTargetSets(), 6, weights[i],
                        new BigDecimal("8.0"), "自定义计划打卡"));
                assertThat(logged.getEstimated1rm()).isEqualByComparingTo(expected1rm[i]);
                totalVolume = totalVolume.add(logged.getTotalVolume());
                log.info("[验证7] 打卡 {}kg × {} 组 × 6 次 → 1RM {}kg / 容量 {}kg",
                        weights[i], item.getTargetSets(), logged.getEstimated1rm(), logged.getTotalVolume());
            }
            // 1×6×80 + 2×6×90 + 3×6×100 = 480 + 1080 + 1800 = 3360
            assertThat(totalVolume).isEqualByComparingTo("3360.00");
            assertThat(workoutLogMapper.selectCount(Wrappers.<WorkoutLog>lambdaQuery()
                    .eq(WorkoutLog::getUserId, userId))).isEqualTo(3L);

            // 多重量组同样受「单日 ≤30 组」护栏约束：11+11+11 = 33 组 → 拦截
            assertThatThrownBy(() -> workoutService.createPlan(new WorkoutPlanCreateDTO(userId, "超量自定义", "FULL_BODY",
                    LocalDate.now(), LocalDate.now().plusWeeks(4),
                    List.of(new WorkoutPlanItemDTO(3, BARBELL_SQUAT, 11, 6, new BigDecimal("80.0"),
                                    new BigDecimal("8.0"), 1, "a"),
                            new WorkoutPlanItemDTO(3, ROMANIAN_DEADLIFT, 11, 8, new BigDecimal("60.0"),
                                    new BigDecimal("8.0"), 2, "b"),
                            new WorkoutPlanItemDTO(3, LEG_PRESS, 11, 10, new BigDecimal("100.0"),
                                    new BigDecimal("8.0"), 3, "c")))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("单日安全上限");
            log.info("[验证7] 自定义计划的多个重量组同样受单日 30 组护栏约束");
        } finally {
            cleanup(userId);
        }
    }

    // ==================================================================
    // 验证点 8：自定义动作（自己起名字）—— 创建 / 隔离 / 排课打卡 / 删除保护
    // ==================================================================

    @Test
    void customExerciseCanBeCreatedUsedInPlanAndIsProtected() {
        Long userId = createDisposableUser();
        Long otherUserId = null;
        try {
            // ① 创建自定义动作（声明禁忌 LOWER_BACK，用于确认伤病护栏字段仍然生效）
            ExerciseDict custom = workoutService.createCustomExercise(new CustomExerciseCreateDTO(userId,
                    "自创：单腿罗马尼亚硬拉", "LEGS", "DUMBBELL", "INTERMEDIATE", "LOWER_BACK", "髋主导，脊柱中立"));
            assertThat(custom.getId()).isNotNull();
            assertThat(custom.getUserId()).isEqualTo(userId);
            assertThat(custom.getAvoidInjuryParts()).isEqualTo("LOWER_BACK");
            log.info("[验证8] 自定义动作已创建：{}（id={} 肌群={} 禁忌={}）",
                    custom.getName(), custom.getId(), custom.getTargetMuscle(), custom.getAvoidInjuryParts());

            // ② 出现在自己的「全量动作」里
            assertThat(workoutService.findAllExercises(userId, null, null))
                    .extracting(ExerciseDict::getName).contains("自创：单腿罗马尼亚硬拉");

            // ③ 其他用户看不到别人的自定义动作（归属隔离）
            otherUserId = createDisposableUser();
            assertThat(workoutService.findAllExercises(otherUserId, null, null))
                    .extracting(ExerciseDict::getName).doesNotContain("自创：单腿罗马尼亚硬拉");

            // ④ 同名（自定义/系统内置）都拒绝重复创建
            assertThatThrownBy(() -> workoutService.createCustomExercise(new CustomExerciseCreateDTO(userId,
                    "自创：单腿罗马尼亚硬拉", "LEGS", null, null, null, null)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("已存在同名动作");
            assertThatThrownBy(() -> workoutService.createCustomExercise(new CustomExerciseCreateDTO(userId,
                    "杠铃深蹲", "LEGS", null, null, null, null)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("已存在同名动作");

            // ⑤ 禁忌部位拼错 → 严格拒绝（否则护栏会静默失效）
            assertThatThrownBy(() -> workoutService.createCustomExercise(new CustomExerciseCreateDTO(userId,
                    "自创：乱填部位", "LEGS", null, null, "KNEES", null)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("非法部位");
            // ⑥ 肌群取值非法 → 拒绝
            assertThatThrownBy(() -> workoutService.createCustomExercise(new CustomExerciseCreateDTO(userId,
                    "自创：乱填肌群", "BICEPS", null, null, null, null)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("取值非法");

            // ⑦ 用自定义动作建课表并打卡
            WorkoutPlanVO plan = workoutService.createPlan(new WorkoutPlanCreateDTO(userId, "自定义动作课表", "FULL_BODY",
                    LocalDate.now(), LocalDate.now().plusWeeks(4),
                    List.of(new WorkoutPlanItemDTO(4, custom.getId(), 3, 8, new BigDecimal("24.0"),
                            new BigDecimal("8.0"), 1, "自定义动作"))));
            assertThat(plan.items()).hasSize(1);
            assertThat(plan.items().get(0).getExerciseName()).isEqualTo("自创：单腿罗马尼亚硬拉");
            WorkoutLog logged = workoutService.logWorkout(new WorkoutLogCreateDTO(userId,
                    plan.items().get(0).getId(), LocalDate.now(), custom.getId(), 3, 8,
                    new BigDecimal("24.0"), new BigDecimal("8.0"), "自定义动作打卡"));
            // 24 × (1 + 8/30) = 30.4
            assertThat(logged.getEstimated1rm()).isEqualByComparingTo("30.40");
            log.info("[验证8] 自定义动作已排课并打卡：1RM={}kg 总容量={}kg",
                    logged.getEstimated1rm(), logged.getTotalVolume());

            // ⑧ 被课表/打卡引用时不允许删除
            assertThatThrownBy(() -> workoutService.deleteCustomExercise(custom.getId(), userId))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("仍被引用");
            // ⑨ 系统内置动作不允许删除
            assertThatThrownBy(() -> workoutService.deleteCustomExercise(BARBELL_SQUAT, userId))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("系统内置动作");
            // ⑩ 未被引用的自定义动作可以删除
            ExerciseDict temp = workoutService.createCustomExercise(new CustomExerciseCreateDTO(userId,
                    "自创：待删除动作", "CORE", null, null, null, null));
            CustomExerciseDeleteVO deleted = workoutService.deleteCustomExercise(temp.getId(), userId);
            assertThat(deleted.name()).isEqualTo("自创：待删除动作");
            assertThat(workoutService.findAllExercises(userId, null, null))
                    .extracting(ExerciseDict::getName).doesNotContain("自创：待删除动作");
            log.info("[验证8] 删除保护与清理符合预期：{}", deleted.message());
        } finally {
            if (otherUserId != null) {
                cleanup(otherUserId);
            }
            cleanup(userId);
        }
    }

    // ==================================================================
    // 测试夹具
    // ==================================================================

    private Long seedLegPlanItem(Long userId, int dayOfWeek) {
        var plan = workoutService.createPlan(new WorkoutPlanCreateDTO(userId, "ADV-TEST", "PPL",
                LocalDate.now(), LocalDate.now().plusWeeks(4),
                List.of(new WorkoutPlanItemDTO(dayOfWeek, BARBELL_SQUAT, 4, 8,
                        new BigDecimal("60.0"), new BigDecimal("8.0"), 1, "进阶模块测试预置"))));
        return plan.items().get(0).getId();
    }

    private void createLog(Long userId, Long planItemId, BigDecimal weight, int reps, BigDecimal rpe) {
        workoutService.logWorkout(new WorkoutLogCreateDTO(userId, planItemId, LocalDate.now(),
                BARBELL_SQUAT, 4, reps, weight, rpe, "进阶模块测试打卡"));
    }

    private DailyMacroTarget targetOf(Long userId, LocalDate date) {
        DailyMacroTarget target = dailyMacroTargetMapper.selectOne(Wrappers.<DailyMacroTarget>lambdaQuery()
                .eq(DailyMacroTarget::getUserId, userId)
                .eq(DailyMacroTarget::getTargetDate, date)
                .last("LIMIT 1"));
        assertThat(target).as("调度器应为用户 " + userId + " 生成 " + date + " 的目标").isNotNull();
        return target;
    }

    private Long createDisposableUser() {
        BigDecimal weight = new BigDecimal("75.00");
        BigDecimal height = new BigDecimal("178.00");
        BigDecimal activity = new BigDecimal("1.55");
        int bmr = ProfileService.calculateBmr("MALE", weight, height, 21);
        UserProfile profile = new UserProfile();
        profile.setNickname("进阶模块测试用户");
        profile.setGender("MALE");
        profile.setAge(21);
        profile.setHeightCm(height);
        profile.setWeightKg(weight);
        profile.setActivityLevel(activity);
        profile.setFitnessGoal("MUSCLE_GAIN");
        profile.setEquipmentEnv("GYM");
        profile.setBmr(bmr);
        profile.setTdee(ProfileService.calculateTdee(bmr, activity));
        userProfileMapper.insert(profile);
        profileService.addInjury(profile.getId(),
                new InjuryTagCreateDTO("LEFT_SHOULDER", "进阶模块测试预置：左肩不适", "MILD"));
        return profile.getId();
    }

    private void cleanup(Long userId) {
        toolAuditLogMapper.delete(Wrappers.<ToolAuditLog>lambdaQuery().eq(ToolAuditLog::getUserId, userId));
        workoutLogMapper.delete(Wrappers.<WorkoutLog>lambdaQuery().eq(WorkoutLog::getUserId, userId));
        dietLogMapper.delete(Wrappers.<DietLog>lambdaQuery().eq(DietLog::getUserId, userId));
        dailyMacroTargetMapper.delete(Wrappers.<DailyMacroTarget>lambdaQuery()
                .eq(DailyMacroTarget::getUserId, userId));
        mealTemplateMapper.delete(Wrappers.<MealTemplate>lambdaQuery().eq(MealTemplate::getUserId, userId));
        workoutPlanItemMapper.delete(Wrappers.<WorkoutPlanItem>lambdaQuery()
                .eq(WorkoutPlanItem::getUserId, userId));
        workoutPlanMapper.delete(Wrappers.<WorkoutPlan>lambdaQuery().eq(WorkoutPlan::getUserId, userId));
        userInjuryTagMapper.delete(Wrappers.<UserInjuryTag>lambdaQuery().eq(UserInjuryTag::getUserId, userId));
        // 自定义动作（exercise_dict 里 user_id 非空的行）同样必须清理，
        // 否则下次运行会因为「同名动作已存在」而失败（测试污染）
        exerciseDictMapper.delete(Wrappers.<ExerciseDict>lambdaQuery().eq(ExerciseDict::getUserId, userId));
        userProfileMapper.deleteById(userId);
        log.info("[清理] 测试用户 {} 及其全部流水已删除", userId);
    }
}