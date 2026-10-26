package com.fitness.ai.observability;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.ai.tool.ProfileTools;
import com.fitness.ai.ai.tool.WorkoutTools;
import com.fitness.ai.catalog.entity.MealTemplate;
import com.fitness.ai.catalog.mapper.MealTemplateMapper;
import com.fitness.ai.nutrition.entity.DailyMacroTarget;
import com.fitness.ai.nutrition.entity.DietLog;
import com.fitness.ai.nutrition.mapper.DailyMacroTargetMapper;
import com.fitness.ai.nutrition.mapper.DietLogMapper;
import com.fitness.ai.observability.entity.ToolAuditLog;
import com.fitness.ai.observability.mapper.ToolAuditLogMapper;
import com.fitness.ai.observability.service.ToolAuditService;
import com.fitness.ai.observability.vo.ToolAuditSummaryVO;
import com.fitness.ai.profile.dto.InjuryTagCreateDTO;
import com.fitness.ai.profile.entity.UserInjuryTag;
import com.fitness.ai.profile.entity.UserProfile;
import com.fitness.ai.profile.mapper.UserInjuryTagMapper;
import com.fitness.ai.profile.mapper.UserProfileMapper;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.workout.dto.WorkoutPlanCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanItemDTO;
import com.fitness.ai.workout.entity.WorkoutLog;
import com.fitness.ai.workout.entity.WorkoutPlan;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.mapper.WorkoutLogMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanItemMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanMapper;
import com.fitness.ai.workout.service.WorkoutService;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AOP 工具调用审计切面的确定性验证：不调用大模型，直接通过 Spring 代理调用工具 Bean。
 *
 * <p>覆盖三种状态判定：正常返回 → {@code SUCCESS}；返回 {@code ERROR:} 开头 → {@code GUARDRAIL_BLOCKED}；
 * 抛出未捕获异常 → {@code EXCEPTION}；并校验统计聚合（总次数 / 拦截次数 / 平均耗时）。</p>
 */
@Slf4j
@SpringBootTest
class ToolAuditAspectTest {

    /** 杠铃深蹲：对只有左肩伤病的用户安全 */
    private static final Long BARBELL_SQUAT = 12L;
    /** 坐姿哑铃推举：与 LEFT_SHOULDER 冲突，用于触发伤病护栏 */
    private static final Long DUMBBELL_SHOULDER_PRESS = 18L;

    @Autowired
    private ProfileTools profileTools;
    @Autowired
    private WorkoutTools workoutTools;
    @Autowired
    private ToolAuditService toolAuditService;
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

    private Long userId;
    private Long itemId;

    @BeforeEach
    void setUp() {
        userId = createDisposableUser();
        WorkoutPlanVO plan = workoutService.createPlan(new WorkoutPlanCreateDTO(userId, "AUDIT-TEST", "PPL",
                LocalDate.now(), LocalDate.now().plusWeeks(4),
                List.of(new WorkoutPlanItemDTO(1, BARBELL_SQUAT, 5, 8, new BigDecimal("60.0"),
                        new BigDecimal("8.0"), 1, "审计测试预置"))));
        itemId = plan.items().get(0).getId();
    }

    @AfterEach
    void tearDown() {
        int audits = toolAuditLogMapper.delete(Wrappers.<ToolAuditLog>lambdaQuery()
                .eq(ToolAuditLog::getUserId, userId));
        workoutLogMapper.delete(Wrappers.<WorkoutLog>lambdaQuery().eq(WorkoutLog::getUserId, userId));
        dietLogMapper.delete(Wrappers.<DietLog>lambdaQuery().eq(DietLog::getUserId, userId));
        dailyMacroTargetMapper.delete(Wrappers.<DailyMacroTarget>lambdaQuery()
                .eq(DailyMacroTarget::getUserId, userId));
        mealTemplateMapper.delete(Wrappers.<MealTemplate>lambdaQuery().eq(MealTemplate::getUserId, userId));
        workoutPlanItemMapper.delete(Wrappers.<WorkoutPlanItem>lambdaQuery()
                .eq(WorkoutPlanItem::getUserId, userId));
        workoutPlanMapper.delete(Wrappers.<WorkoutPlan>lambdaQuery().eq(WorkoutPlan::getUserId, userId));
        userInjuryTagMapper.delete(Wrappers.<UserInjuryTag>lambdaQuery().eq(UserInjuryTag::getUserId, userId));
        userProfileMapper.deleteById(userId);
        log.info("[清理] 审计测试用户 {} 及其 {} 条审计记录已删除", userId, audits);
    }

    @Test
    void successStatusIsRecorded() {
        String result = profileTools.recordOrUpdateInjury(userId, "KNEE", "审计测试：膝盖轻微不适", "MILD");
        assertThat(result).doesNotContain("ERROR:");

        ToolAuditSummaryVO summary = toolAuditService.query(userId, 20);
        List<ToolAuditLog> logs = summary.logs();
        assertThat(logs).hasSize(1);
        ToolAuditLog audit = logs.get(0);
        assertThat(audit.getToolName()).isEqualTo("recordOrUpdateInjury");
        assertThat(audit.getToolClass()).isEqualTo("ProfileTools");
        assertThat(audit.getStatus()).isEqualTo(ToolAuditService.STATUS_SUCCESS);
        assertThat(audit.getUserId()).isEqualTo(userId);
        assertThat(audit.getArgsJson()).contains("KNEE").doesNotContain("userId");
        assertThat(audit.getResultText()).contains("KNEE");
        assertThat(audit.getCostMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(audit.getCreatedAt()).as("MetaObjectHandler 应填充 created_at").isNotNull();
        log.info("[审计-SUCCESS] tool={} status={} cost={}ms args={} result={}", audit.getToolName(),
                audit.getStatus(), audit.getCostMs(), audit.getArgsJson(), audit.getResultText());
    }

    @Test
    void guardrailBlockedStatusIsRecorded() {
        // 故意把课表动作换成与 LEFT_SHOULDER 冲突的坐姿哑铃推举
        String result = workoutTools.updateWorkoutPlanItem(userId, itemId, DUMBBELL_SHOULDER_PRESS,
                null, null, null, null, null);
        assertThat(result).startsWith("ERROR:").contains("LEFT_SHOULDER");

        ToolAuditSummaryVO summary = toolAuditService.query(userId, 20);
        ToolAuditLog audit = summary.logs().get(0);
        assertThat(audit.getToolName()).isEqualTo("updateWorkoutPlanItem");
        assertThat(audit.getStatus()).isEqualTo(ToolAuditService.STATUS_GUARDRAIL_BLOCKED);
        assertThat(audit.getArgsJson()).contains("exerciseId");
        assertThat(audit.getResultText()).startsWith("ERROR:");
        assertThat(summary.guardrailBlocked()).isEqualTo(1L);
        log.info("[审计-GUARDRAIL_BLOCKED] tool={} cost={}ms result={}", audit.getToolName(),
                audit.getCostMs(), audit.getResultText());

        // 对照组：不冲突的动作可以正常改
        String ok = workoutTools.updateWorkoutPlanItem(userId, itemId, BARBELL_SQUAT, 6, 8, 65.0, 8.5, "对照组");
        assertThat(ok).doesNotContain("ERROR:");
        assertThat(toolAuditService.query(userId, 20).logs().get(0).getStatus())
                .isEqualTo(ToolAuditService.STATUS_SUCCESS);
    }

    @Test
    void exceptionStatusIsRecorded() {
        // actualWeightKg 传 null → 工具内部 BigDecimal.valueOf(null) 抛 NPE，属于「未捕获异常」
        assertThrows(NullPointerException.class, () ->
                workoutTools.logWorkoutExecution(userId, BARBELL_SQUAT, 4, 8, null, null, null, null));

        ToolAuditSummaryVO summary = toolAuditService.query(userId, 20);
        ToolAuditLog audit = summary.logs().get(0);
        assertThat(audit.getToolName()).isEqualTo("logWorkoutExecution");
        assertThat(audit.getStatus()).isEqualTo(ToolAuditService.STATUS_EXCEPTION);
        assertThat(audit.getResultText()).contains("NullPointerException");
        assertThat(summary.exceptionCount()).isEqualTo(1L);
        log.info("[审计-EXCEPTION] tool={} cost={}ms result={}", audit.getToolName(),
                audit.getCostMs(), audit.getResultText());
    }

    @Test
    void summaryAggregatesStatistics() {
        profileTools.recordOrUpdateInjury(userId, "KNEE", "统计测试", "MILD");
        workoutTools.updateWorkoutPlanItem(userId, itemId, DUMBBELL_SHOULDER_PRESS, null, null, null, null, null);
        assertThrows(NullPointerException.class, () ->
                workoutTools.logWorkoutExecution(userId, BARBELL_SQUAT, 4, 8, null, null, null, null));

        ToolAuditSummaryVO summary = toolAuditService.query(userId, 20);
        assertThat(summary.totalCalls()).isEqualTo(3L);
        assertThat(summary.guardrailBlocked()).isEqualTo(1L);
        assertThat(summary.exceptionCount()).isEqualTo(1L);
        assertThat(summary.avgCostMs()).isNotNull();
        assertThat(summary.maxCostMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(summary.logs()).hasSize(3);
        log.info("[审计-统计] 总调用={} 护栏拦截={} 异常={} 平均耗时={}ms 最大耗时={}ms", summary.totalCalls(),
                summary.guardrailBlocked(), summary.exceptionCount(), summary.avgCostMs(), summary.maxCostMs());
    }

    private Long createDisposableUser() {
        BigDecimal weight = new BigDecimal("75.00");
        BigDecimal height = new BigDecimal("178.00");
        BigDecimal activity = new BigDecimal("1.55");
        int bmr = ProfileService.calculateBmr("MALE", weight, height, 21);
        UserProfile profile = new UserProfile();
        profile.setNickname("审计测试用户");
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
                new InjuryTagCreateDTO("LEFT_SHOULDER", "审计测试预置：左肩不适", "MILD"));
        return profile.getId();
    }
}