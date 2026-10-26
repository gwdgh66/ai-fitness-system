package com.fitness.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.nutrition.dto.DietLogCreateDTO;
import com.fitness.ai.nutrition.dto.MacroTargetGenerateDTO;
import com.fitness.ai.nutrition.entity.DailyMacroTarget;
import com.fitness.ai.nutrition.entity.DietLog;
import com.fitness.ai.nutrition.service.NutritionService;
import com.fitness.ai.nutrition.vo.DailyNutritionSummaryVO;
import com.fitness.ai.profile.dto.InjuryTagCreateDTO;
import com.fitness.ai.profile.entity.UserInjuryTag;
import com.fitness.ai.profile.entity.UserProfile;
import com.fitness.ai.profile.mapper.UserInjuryTagMapper;
import com.fitness.ai.profile.mapper.UserProfileMapper;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.profile.vo.UserDigitalTwinVO;
import com.fitness.ai.workout.dto.WorkoutLogCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanItemDTO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.entity.WorkoutLog;
import com.fitness.ai.workout.mapper.ExerciseDictMapper;
import com.fitness.ai.workout.mapper.WorkoutLogMapper;
import com.fitness.ai.workout.service.WorkoutService;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 底座与护栏集成测试：连真实 MySQL（fitness_ai）跑，逐个闭环第 2 步遗留的待验证项。
 *
 * <p>整个类带 {@code @Transactional}，测试结束自动回滚，不会污染开发库里的初始数据。</p>
 */
@Slf4j
@SpringBootTest
@Transactional
class BaseVerificationIntegrationTest {

    /** 默认用户 */
    private static final Long USER_ID = 1L;
    /** 杠铃卧推（CHEST / avoid=LEFT_SHOULDER,RIGHT_SHOULDER,WRIST） */
    private static final Long BENCH_PRESS = 1L;
    /** 杠铃深蹲（LEGS / avoid=KNEE,LOWER_BACK），对仅有左肩伤病的用户 1 是安全的 */
    private static final Long BARBELL_SQUAT = 12L;
    /** 坐姿哑铃推举（SHOULDERS / avoid=LEFT_SHOULDER,RIGHT_SHOULDER），与用户 1 的左肩伤病冲突 */
    private static final Long DUMBBELL_SHOULDER_PRESS = 18L;
    /** 罗马尼亚硬拉（LEGS / avoid=LOWER_BACK），对仅有左肩伤病的用户 1 是安全的 */
    private static final Long ROMANIAN_DEADLIFT = 14L;
    /** 腿举（LEGS / avoid=KNEE），对仅有左肩伤病的用户 1 是安全的 */
    private static final Long LEG_PRESS = 15L;

    @Autowired
    private ProfileService profileService;
    @Autowired
    private WorkoutService workoutService;
    @Autowired
    private NutritionService nutritionService;
    @Autowired
    private UserProfileMapper userProfileMapper;
    @Autowired
    private UserInjuryTagMapper userInjuryTagMapper;
    @Autowired
    private ExerciseDictMapper exerciseDictMapper;
    @Autowired
    private WorkoutLogMapper workoutLogMapper;

    // ==================================================================
    // 验证 1：实体 ↔ 列映射（含中文、DECIMAL、枚举字符串）
    // ==================================================================

    @Test
    void verify1_entityColumnMapping() {
        UserDigitalTwinVO twin = profileService.getDigitalTwin(USER_ID);
        assertThat(twin.nickname()).isEqualTo("默认用户");
        assertThat(twin.gender()).isEqualTo("MALE");
        assertThat(twin.age()).isEqualTo(21);
        assertThat(twin.heightCm()).isEqualByComparingTo("178.00");
        assertThat(twin.weightKg()).isEqualByComparingTo("75.00");
        assertThat(twin.bodyFatPct()).isEqualByComparingTo("16.00");
        assertThat(twin.bmr()).isEqualTo(1763);
        assertThat(twin.tdee()).isEqualTo(2733);
        assertThat(twin.activityLevel()).isEqualByComparingTo("1.55");
        assertThat(twin.fitnessGoal()).isEqualTo("MUSCLE_GAIN");
        assertThat(twin.equipmentEnv()).isEqualTo("GYM");
        assertThat(twin.createdAt()).isNotNull();
        assertThat(twin.activeInjuries()).hasSize(1);
        assertThat(twin.activeInjuries().get(0).getBodyPart()).isEqualTo("LEFT_SHOULDER");
        log.info("[验证1] 数字孪生档案映射正常：nickname={} height_cm={} weight_kg={} bmr={} tdee={} ACTIVE伤病={}",
                twin.nickname(), twin.heightCm(), twin.weightKg(), twin.bmr(), twin.tdee(),
                twin.activeInjuries().size());

        List<ExerciseDict> all = exerciseDictMapper.selectList(null);
        List<ExerciseDict> safe = workoutService.findSafeExercises(USER_ID, null, null);
        // 动作字典现在同时存放用户自建的自定义动作（user_id 非空），所以断言的是
        // 「系统内置动作恒为 27 个」而不是全表行数——全表行数会随用户创建动作而增长。
        assertThat(all.stream().filter(exercise -> exercise.getUserId() == null).toList())
                .as("系统内置动作应为 27 个").hasSize(27);
        assertThat(all).as("全表包含内置动作 + 用户自定义动作").hasSizeGreaterThanOrEqualTo(27);
        assertThat(safe).isNotEmpty().hasSizeLessThan(all.size());
        assertThat(safe).noneMatch(e -> e.getAvoidInjuryParts() != null
                && e.getAvoidInjuryParts().contains("LEFT_SHOULDER"));
        // 对照组：冲突动作确实存在于字典中，是被安全过滤掉的
        assertThat(all).anyMatch(e -> DUMBBELL_SHOULDER_PRESS.equals(e.getId())
                && e.getAvoidInjuryParts().contains("LEFT_SHOULDER"));
        assertThat(safe).noneMatch(e -> DUMBBELL_SHOULDER_PRESS.equals(e.getId()));
        log.info("[验证1] 安全动作过滤正常：字典 {} 个 → 用户 {} 可用 {} 个（剔除 {} 个涉及 LEFT_SHOULDER 的动作）",
                all.size(), USER_ID, safe.size(), all.size() - safe.size());
    }

    // ==================================================================
    // 验证 2：MetaObjectHandler 自动填充 created_at / updated_at
    // ==================================================================

    @Test
    void verify2_metaObjectHandlerAutoFill() throws InterruptedException {
        UserInjuryTag created = profileService.addInjury(USER_ID,
                new InjuryTagCreateDTO("WRIST", "集成测试用：手腕轻微不适", "MILD"));
        assertThat(created.getId()).isNotNull();

        UserInjuryTag insertRow = userInjuryTagMapper.selectById(created.getId());
        assertThat(insertRow.getCreatedAt()).as("插入时 created_at 应被自动填充").isNotNull();
        assertThat(insertRow.getUpdatedAt()).as("插入时 updated_at 应被自动填充").isNotNull();
        assertThat(insertRow.getUpdatedAt()).isEqualTo(insertRow.getCreatedAt());
        log.info("[验证2] 插入自动填充成功：created_at={} updated_at={}",
                insertRow.getCreatedAt(), insertRow.getUpdatedAt());

        // DATETIME 只有秒级精度，等 1.1 秒才能观测到 updated_at 真正被刷新
        Thread.sleep(1100L);
        insertRow.setDescription("集成测试用：手腕轻微不适（已更新描述）");
        userInjuryTagMapper.updateById(insertRow);

        UserInjuryTag updateRow = userInjuryTagMapper.selectById(created.getId());
        assertThat(updateRow.getUpdatedAt()).as("更新时 updated_at 应被刷新").isAfter(updateRow.getCreatedAt());
        assertThat(updateRow.getCreatedAt()).as("更新不应改动 created_at").isEqualTo(insertRow.getCreatedAt());
        assertThat(updateRow.getDescription()).endsWith("（已更新描述）");
        log.info("[验证2] 更新自动填充成功：created_at={}（未变） updated_at={}（已刷新）",
                updateRow.getCreatedAt(), updateRow.getUpdatedAt());
    }

    // ==================================================================
    // 验证 3：BlockAttackInnerInterceptor 拦截无 WHERE 的更新/删除
    // ==================================================================

    @Test
    void verify3_blockAttackInterceptor() {
        RuntimeException deleteEx = assertThrows(RuntimeException.class,
                () -> userProfileMapper.delete(null),
                "无 WHERE 条件的 DELETE 必须被拦截");
        log.info("[验证3] 无 WHERE 的 DELETE 已被拦截：type={} message={}",
                deleteEx.getClass().getSimpleName(), deleteEx.getMessage());
        assertThat(deleteEx.getMessage()).isNotBlank();

        RuntimeException updateEx = assertThrows(RuntimeException.class,
                () -> userProfileMapper.update(null,
                        Wrappers.<UserProfile>lambdaUpdate().set(UserProfile::getNickname, "全表更新测试")),
                "无 WHERE 条件的 UPDATE 必须被拦截");
        log.info("[验证3] 无 WHERE 的 UPDATE 已被拦截：type={} message={}",
                updateEx.getClass().getSimpleName(), updateEx.getMessage());
        assertThat(updateEx.getMessage()).isNotBlank();

        // 对照组：带 WHERE 的删除可以正常执行（证明拦截只针对「全表」操作）
        int deleted = userInjuryTagMapper.delete(Wrappers.<UserInjuryTag>lambdaQuery()
                .eq(UserInjuryTag::getUserId, 999_999L));
        assertThat(deleted).isZero();
        log.info("[验证3] 对照组通过：带 WHERE 的 DELETE 正常执行（影响 {} 行）", deleted);
    }

    // ==================================================================
    // 验证 4：伤病冲突护栏
    // ==================================================================

    @Test
    void verify4_injuryConflictGuardrail() {
        // 4a. 冲突动作必须被拒绝
        BusinessException ex = assertThrows(BusinessException.class, () -> workoutService.createPlan(
                        new WorkoutPlanCreateDTO(USER_ID, "集成测试-伤病冲突计划", "PPL",
                                LocalDate.now(), LocalDate.now().plusWeeks(8),
                                List.of(new WorkoutPlanItemDTO(1, DUMBBELL_SHOULDER_PRESS, 4, 10,
                                        new BigDecimal("20.0"), new BigDecimal("8.0"), 1, "测试用")))));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_OUT_OF_BOUNDS);
        assertThat(ex.getMessage()).contains("LEFT_SHOULDER");
        assertThat(ex.getMessage()).contains("坐姿哑铃推举");
        log.info("[验证4] 伤病冲突已拦截：{}", ex.getMessage());

        // 4b. 同一个用户排入不冲突的动作应当成功
        WorkoutPlanVO plan = workoutService.createPlan(
                new WorkoutPlanCreateDTO(USER_ID, "集成测试-安全计划", "PPL",
                        LocalDate.now(), LocalDate.now().plusWeeks(8),
                        List.of(new WorkoutPlanItemDTO(1, BARBELL_SQUAT, 5, 8,
                                new BigDecimal("60.0"), new BigDecimal("8.0"), 1, "测试用"))));
        assertThat(plan.items()).hasSize(1);
        assertThat(plan.items().get(0).getExerciseName()).isEqualTo("杠铃深蹲");
        assertThat(plan.status()).isEqualTo("ACTIVE");
        log.info("[验证4] 对照组通过：安全动作建表成功 planId={} 明细={} 条", plan.id(), plan.items().size());
    }

    // ==================================================================
    // 验证 5：Epley 1RM 与总容量公式
    // ==================================================================

    @Test
    void verify5_oneRmAndVolumeFormula() {
        WorkoutLog saved = workoutService.logWorkout(new WorkoutLogCreateDTO(USER_ID, null, LocalDate.now(),
                BENCH_PRESS, 4, 8, new BigDecimal("80.0"), new BigDecimal("8.5"), "集成测试：80kg×8×4"));
        assertThat(saved.getExerciseName()).isEqualTo("杠铃卧推");
        assertThat(saved.getEstimated1rm()).isNotNull();
        assertThat(saved.getTotalVolume()).isNotNull();

        WorkoutLog fromDb = workoutLogMapper.selectById(saved.getId());
        assertThat(fromDb.getEstimated1rm()).isEqualByComparingTo("101.3");
        assertThat(fromDb.getTotalVolume()).isEqualByComparingTo("2560.0");
        assertThat(fromDb.getCreatedAt()).as("打卡流水的 created_at 应被自动填充").isNotNull();
        log.info("[验证5] 打卡落库：exercise={} {}组×{}次×{}kg → estimated_1rm={} total_volume={} created_at={}",
                fromDb.getExerciseName(), fromDb.getActualSets(), fromDb.getActualReps(),
                fromDb.getActualWeightKg(), fromDb.getEstimated1rm(), fromDb.getTotalVolume(),
                fromDb.getCreatedAt());

        // 边界：reps == 1 时 1RM 直接等于重量
        WorkoutLog single = workoutService.logWorkout(new WorkoutLogCreateDTO(USER_ID, null, LocalDate.now(),
                BENCH_PRESS, 1, 1, new BigDecimal("120.0"), new BigDecimal("9.5"), "集成测试：单次极限"));
        assertThat(single.getEstimated1rm()).isEqualByComparingTo("120.0");
        assertThat(single.getTotalVolume()).isEqualByComparingTo("120.0");
        log.info("[验证5] reps=1 边界：estimated_1rm={} total_volume={}",
                single.getEstimated1rm(), single.getTotalVolume());
    }

    // ==================================================================
    // 附加：其余硬编码护栏（组数 / 次数 / RPE / 单日总组数 / 热量底线）
    // ==================================================================

    @Test
    void verify6_volumeGuardrails() {
        BusinessException setsEx = assertThrows(BusinessException.class, () -> workoutService.logWorkout(
                new WorkoutLogCreateDTO(USER_ID, null, LocalDate.now(), BENCH_PRESS, 20, 8,
                        new BigDecimal("80.0"), null, null)));
        assertThat(setsEx.getMessage()).contains("1 ~ 15");
        log.info("[附加护栏] 组数越界已拦截：{}", setsEx.getMessage());

        BusinessException repsEx = assertThrows(BusinessException.class, () -> workoutService.logWorkout(
                new WorkoutLogCreateDTO(USER_ID, null, LocalDate.now(), BENCH_PRESS, 4, 120,
                        new BigDecimal("80.0"), null, null)));
        assertThat(repsEx.getMessage()).contains("1 ~ 100");
        log.info("[附加护栏] 次数越界已拦截：{}", repsEx.getMessage());

        BusinessException rpeEx = assertThrows(BusinessException.class, () -> workoutService.logWorkout(
                new WorkoutLogCreateDTO(USER_ID, null, LocalDate.now(), BENCH_PRESS, 4, 8,
                        new BigDecimal("80.0"), new BigDecimal("12.0"), null)));
        assertThat(rpeEx.getMessage()).contains("1.0 ~ 10.0");
        log.info("[附加护栏] RPE 越界已拦截：{}", rpeEx.getMessage());

        // 单日总组数：单动作上限 15 组，故需要 3 个动作才能突破单日 30 组上限
        // （这里刻意全部使用对 LEFT_SHOULDER 无禁忌的动作，避免先被伤病护栏拦下）
        BusinessException dayEx = assertThrows(BusinessException.class, () -> workoutService.createPlan(
                new WorkoutPlanCreateDTO(USER_ID, "集成测试-超量计划", "PPL",
                        LocalDate.now(), LocalDate.now().plusWeeks(4),
                        List.of(new WorkoutPlanItemDTO(1, BARBELL_SQUAT, 15, 8, new BigDecimal("60.0"), null, 1, null),
                                new WorkoutPlanItemDTO(1, ROMANIAN_DEADLIFT, 15, 8, new BigDecimal("50.0"), null, 2, null),
                                new WorkoutPlanItemDTO(1, LEG_PRESS, 1, 8, new BigDecimal("100.0"), null, 3, null)))));
        assertThat(dayEx.getMessage()).contains("31").contains("30");
        log.info("[附加护栏] 单日总组数越界已拦截：{}", dayEx.getMessage());

        // 热量底线：构造一个低 BMR 用户（女 / 45kg / 155cm / 60 岁 / 活动系数 1.2 / FAT_LOSS）
        int bmr = ProfileService.calculateBmr("FEMALE", new BigDecimal("45.0"), new BigDecimal("155.0"), 60);
        int tdee = ProfileService.calculateTdee(bmr, new BigDecimal("1.2"));
        UserProfile low = new UserProfile();
        low.setNickname("低代谢测试用户");
        low.setGender("FEMALE");
        low.setAge(60);
        low.setHeightCm(new BigDecimal("155.0"));
        low.setWeightKg(new BigDecimal("45.0"));
        low.setActivityLevel(new BigDecimal("1.2"));
        low.setFitnessGoal("FAT_LOSS");
        low.setEquipmentEnv("BODYWEIGHT");
        low.setBmr(bmr);
        low.setTdee(tdee);
        userProfileMapper.insert(low);

        BusinessException calorieEx = assertThrows(BusinessException.class, () -> nutritionService.generateTarget(
                new MacroTargetGenerateDTO(low.getId(), LocalDate.now(), "MEDIUM_CARB")));
        assertThat(calorieEx.getMessage()).contains("1000");
        log.info("[附加护栏] 热量底线已拦截（BMR={} TDEE={} FAT_LOSS 目标={}）：{}",
                bmr, tdee, tdee - 400, calorieEx.getMessage());
    }

    // ==================================================================
    // 附加：营养目标的确定性计算与饮食热量自动换算
    // ==================================================================

    @Test
    void verify7_nutritionDeterministicFormula() {
        LocalDate date = LocalDate.now();
        DailyMacroTarget target = nutritionService.generateTarget(
                new MacroTargetGenerateDTO(USER_ID, date, "MEDIUM_CARB"));
        // TDEE 2733 + MUSCLE_GAIN 300 = 3033；蛋白 75×2.0=150g；脂肪 3033×25%/9=84g；碳水 (3033-600-756)/4=419g
        assertThat(target.getTargetCalories()).isEqualTo(3033);
        assertThat(target.getTargetProteinG()).isEqualTo(150);
        assertThat(target.getTargetFatG()).isEqualTo(84);
        assertThat(target.getTargetCarbsG()).isEqualTo(419);
        log.info("[附加公式] 营养目标：{} kcal / P{}g / C{}g / F{}g（dayType={}）",
                target.getTargetCalories(), target.getTargetProteinG(),
                target.getTargetCarbsG(), target.getTargetFatG(), target.getDayType());

        // 写入前先记录基线：userId=1 常驻 14 天演示数据，不能假设当天只有本次写入的流水
        DailyNutritionSummaryVO before = nutritionService.summary(USER_ID, date);

        // 字典命中：生鸡胸肉 120kcal/22.5P/0C/2.6F 每 100g，吃 200g
        DietLog log1 = nutritionService.addDietLog(new DietLogCreateDTO(USER_ID, date, "LUNCH",
                "生鸡胸肉", new BigDecimal("200"), null, null, null, null));
        assertThat(log1.getCalories()).isEqualTo(240);
        assertThat(log1.getProteinG()).isEqualByComparingTo("45.00");
        assertThat(log1.getCarbsG()).isEqualByComparingTo("0.00");
        assertThat(log1.getFatG()).isEqualByComparingTo("5.20");
        log.info("[附加公式] 字典命中折算：生鸡胸肉 200g → {} kcal / P{}g / C{}g / F{}g",
                log1.getCalories(), log1.getProteinG(), log1.getCarbsG(), log1.getFatG());

        // 字典未命中且未给估算值 → 明确报错
        BusinessException missEx = assertThrows(BusinessException.class, () -> nutritionService.addDietLog(
                new DietLogCreateDTO(USER_ID, date, "DINNER", "不存在的食物XYZ",
                        new BigDecimal("100"), null, null, null, null)));
        assertThat(missEx.getMessage()).contains("不在本地营养字典中");
        log.info("[附加公式] 字典未命中且无估算值已拦截：{}", missEx.getMessage());

        // 字典未命中但给了估算值 → 使用估算值
        DietLog log2 = nutritionService.addDietLog(new DietLogCreateDTO(USER_ID, date, "DINNER",
                "自制蛋白奶昔", new BigDecimal("300"), 210, new BigDecimal("30.0"),
                new BigDecimal("12.0"), new BigDecimal("5.0")));
        assertThat(log2.getCalories()).isEqualTo(210);

        // 注意：userId=1 现在常驻 14 天演示数据（第 6 步播种、有意保留），
        // 所以这里必须用「写入前后的增量」断言（before 已在写入前采集）。
        DailyNutritionSummaryVO summary = nutritionService.summary(USER_ID, date);
        assertThat(summary.targetCalories()).as("MEDIUM_CARB 目标热量与日型无关，恒为 3033").isEqualTo(3033);
        assertThat(summary.consumedCalories() - before.consumedCalories())
                .as("本次新增 240 + 210 kcal").isEqualTo(450);
        assertThat(summary.consumedProteinG().subtract(before.consumedProteinG()))
                .as("本次新增蛋白质 45 + 30 g").isEqualByComparingTo("75.00");
        assertThat(summary.remainingCalories()).isEqualTo(summary.targetCalories() - summary.consumedCalories());
        assertThat(summary.logs()).hasSize(before.logs().size() + 2);
        log.info("[附加公式] 账单汇总：目标 {} kcal，已摄入 {} kcal（P{}g/C{}g/F{}g），剩余 {} kcal，流水 {} 条",
                summary.targetCalories(), summary.consumedCalories(), summary.consumedProteinG(),
                summary.consumedCarbsG(), summary.consumedFatG(), summary.remainingCalories(),
                summary.logs().size());
    }
}