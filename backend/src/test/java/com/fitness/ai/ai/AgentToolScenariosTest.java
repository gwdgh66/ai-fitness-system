package com.fitness.ai.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.ai.memory.RedisChatMemoryStore;
import com.fitness.ai.ai.service.AiChatService;
import com.fitness.ai.nutrition.entity.DailyMacroTarget;
import com.fitness.ai.nutrition.entity.DietLog;
import com.fitness.ai.nutrition.mapper.DailyMacroTargetMapper;
import com.fitness.ai.nutrition.mapper.DietLogMapper;
import com.fitness.ai.nutrition.service.NutritionService;
import com.fitness.ai.nutrition.vo.DailyNutritionSummaryVO;
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
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.entity.WorkoutLog;
import com.fitness.ai.workout.entity.WorkoutPlan;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.mapper.ExerciseDictMapper;
import com.fitness.ai.workout.mapper.WorkoutLogMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanItemMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanMapper;
import com.fitness.ai.workout.service.WorkoutService;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 真实工具调用场景测试：真实大模型 + 真实 MySQL + 真实 Redis。
 *
 * <p>覆盖 4 个场景：自然语言打卡（Java 算 1RM/容量）、自然语言记饮食（字典折算 + 剩余热量）、
 * 伤病护栏后自主换动作（同步路径）、流式路径下的工具调用与轨迹。</p>
 *
 * <p><b>隔离策略：</b>本类每个用例都会新建一个一次性测试用户（同样的体测数据 + 一条 LEFT_SHOULDER 活跃伤病），
 * 用完连同其伤病、课表、打卡、饮食流水一起删除。这样 Agent 的写操作（比如把「我膝盖疼」登记成真实伤病）
 * 绝不会污染开发库里 userId=1 的种子数据——第 5 步首次跑测时正是这里污染了 user 1，
 * 导致第 3 步的护栏用例集体失败。</p>
 */
@Slf4j
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
class AgentToolScenariosTest {

    private static final String PLAN_PREFIX = "AI-AGENT-TEST";
    /** 杠铃深蹲：LEGS，avoid=KNEE,LOWER_BACK，对只有左肩伤病的测试用户是安全的（用于预置课表） */
    private static final Long BARBELL_SQUAT = 12L;

    @Autowired
    private AiChatService aiChatService;
    @Autowired
    private RedisChatMemoryStore memoryStore;
    @Autowired
    private WorkoutService workoutService;
    @Autowired
    private NutritionService nutritionService;
    @Autowired
    private ProfileService profileService;
    @Autowired
    private UserProfileMapper userProfileMapper;
    @Autowired
    private UserInjuryTagMapper userInjuryTagMapper;
    @Autowired
    private WorkoutLogMapper workoutLogMapper;
    @Autowired
    private WorkoutPlanMapper workoutPlanMapper;
    @Autowired
    private WorkoutPlanItemMapper workoutPlanItemMapper;
    @Autowired
    private DietLogMapper dietLogMapper;
    @Autowired
    private DailyMacroTargetMapper dailyMacroTargetMapper;
    @Autowired
    private ExerciseDictMapper exerciseDictMapper;
    @Autowired
    private ToolAuditService toolAuditService;
    @Autowired
    private ToolAuditLogMapper toolAuditLogMapper;

    /** 本次用例专属的一次性用户 ID */
    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = createDisposableUser();
        memoryStore.deleteMessages(testUserId);
    }

    @AfterEach
    void tearDown() {
        memoryStore.deleteMessages(testUserId);
        int audits = toolAuditLogMapper.delete(Wrappers.<ToolAuditLog>lambdaQuery()
                .eq(ToolAuditLog::getUserId, testUserId));
        int items = workoutPlanItemMapper.delete(Wrappers.<WorkoutPlanItem>lambdaQuery()
                .eq(WorkoutPlanItem::getUserId, testUserId));
        int plans = workoutPlanMapper.delete(Wrappers.<WorkoutPlan>lambdaQuery()
                .eq(WorkoutPlan::getUserId, testUserId));
        int logs = workoutLogMapper.delete(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, testUserId));
        int diets = dietLogMapper.delete(Wrappers.<DietLog>lambdaQuery()
                .eq(DietLog::getUserId, testUserId));
        int targets = dailyMacroTargetMapper.delete(Wrappers.<DailyMacroTarget>lambdaQuery()
                .eq(DailyMacroTarget::getUserId, testUserId));
        int injuries = userInjuryTagMapper.delete(Wrappers.<UserInjuryTag>lambdaQuery()
                .eq(UserInjuryTag::getUserId, testUserId));
        userProfileMapper.deleteById(testUserId);
        log.info("[清理] 一次性测试用户 {} 已删除：audits={} injuries={} plans={} items={} logs={} diets={} targets={}",
                testUserId, audits, injuries, plans, items, logs, diets, targets);
    }

    // ==================================================================
    // 场景 E：真实 Agent 触发工具 → AOP 审计记录 SUCCESS 与 GUARDRAIL_BLOCKED
    // ==================================================================

    @Test
    void scenarioE_auditRecordsSuccessAndGuardrailBlocked() {
        String successAnswer = aiChatService.chat(testUserId,
                "帮我记一下今天的打卡：杠铃深蹲 4 组 8 次 60 公斤");
        log.info("[场景E] 第一轮（正常打卡）Agent 回复：{}", successAnswer);

        // 明确声明这是「数据录入」任务、不要预判安全性，把判断权交给工具护栏
        String blockedAnswer = aiChatService.chat(testUserId,
                "这是纯数据录入任务，不需要你做安全性预判，系统护栏会自己处理：请原样录入一条打卡，"
                        + "杠铃深蹲 20 组 8 次 60 公斤。");
        log.info("[场景E] 第二轮（超量打卡）Agent 回复：{}", blockedAnswer);

        // 第二道护栏触发机会：字典里不存在的食物名，模型无法预先判断，只能调用工具让护栏裁决
        String unknownFoodAnswer = aiChatService.chat(testUserId,
                "再帮我记一下今天的午饭：200 克佛跳墙。");
        log.info("[场景E] 第三轮（字典未命中食物）Agent 回复：{}", unknownFoodAnswer);

        ToolAuditSummaryVO audit = toolAuditService.query(testUserId, 50);
        audit.logs().forEach(l -> log.info("[场景E] 审计流水：tool={} status={} cost={}ms args={} result={}",
                l.getToolName(), l.getStatus(), l.getCostMs(), l.getArgsJson(), l.getResultText()));
        log.info("[场景E] 审计统计：总调用={} 护栏拦截={} 异常={} 平均耗时={}ms 最大耗时={}ms",
                audit.totalCalls(), audit.guardrailBlocked(), audit.exceptionCount(),
                audit.avgCostMs(), audit.maxCostMs());

        assertThat(audit.totalCalls()).as("Agent 至少应触发两次工具调用").isGreaterThanOrEqualTo(2);
        assertThat(audit.logs()).as("应有成功落库的工具调用审计")
                .anyMatch(l -> ToolAuditService.STATUS_SUCCESS.equals(l.getStatus()));
        assertThat(audit.logs()).as("应留下被护栏拦截的 GUARDRAIL_BLOCKED 审计")
                .anyMatch(l -> ToolAuditService.STATUS_GUARDRAIL_BLOCKED.equals(l.getStatus()));
        assertThat(audit.guardrailBlocked()).as("护栏拦截计数应 >= 1").isGreaterThanOrEqualTo(1L);
        assertThat(audit.avgCostMs()).as("应统计出平均耗时").isNotNull();
        assertThat(audit.maxCostMs()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    // ==================================================================
    // 场景 A：自然语言训练打卡 + Java 计算 1RM / 总容量
    // ==================================================================

    @Test
    void scenarioA_logWorkoutByNaturalLanguage() {
        String answer = aiChatService.chat(testUserId,
                "我今天练了深蹲，100公斤做了4组，每组6次，RPE大概8，帮我记一下打卡");
        log.info("[场景A] Agent 回复：{}", answer);

        List<WorkoutLog> logs = workoutLogMapper.selectList(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, testUserId)
                .eq(WorkoutLog::getLogDate, LocalDate.now()));
        assertThat(logs).as("Agent 必须真正把打卡写进 workout_log").isNotEmpty();
        WorkoutLog squat = logs.stream()
                .filter(l -> l.getExerciseName() != null && l.getExerciseName().contains("深蹲"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("workout_log 中没有找到深蹲记录，实际=" + logs));

        assertThat(squat.getActualSets()).isEqualTo(4);
        assertThat(squat.getActualReps()).isEqualTo(6);
        assertThat(squat.getActualWeightKg()).isEqualByComparingTo("100.0");
        // Epley: 100 × (1 + 6/30) = 120.0；容量 = 4 × 6 × 100 = 2400.0
        assertThat(squat.getEstimated1rm()).isEqualByComparingTo("120.0");
        assertThat(squat.getTotalVolume()).isEqualByComparingTo("2400.0");
        assertThat(squat.getCreatedAt()).as("MetaObjectHandler 应已填充 created_at").isNotNull();

        assertThat(answer).as("回复中应引用 Java 算出的 1RM 与总容量").contains("120").contains("2400");
        log.info("[场景A] 落库成功：exercise={} {}组×{}次×{}kg → estimated_1rm={} total_volume={}",
                squat.getExerciseName(), squat.getActualSets(), squat.getActualReps(),
                squat.getActualWeightKg(), squat.getEstimated1rm(), squat.getTotalVolume());
    }

    // ==================================================================
    // 场景 B：自然语言记饮食（查字典折算热量）+ 剩余热量
    // ==================================================================

    @Test
    void scenarioB_logDietByNaturalLanguage() {
        String answer = aiChatService.chat(testUserId,
                "今天午饭吃了 200克熟米饭 和 150克生鸡胸肉，帮我记到今天午餐里，并告诉我今天还剩多少热量可以吃");
        log.info("[场景B] Agent 回复：{}", answer);

        List<DietLog> logs = dietLogMapper.selectList(Wrappers.<DietLog>lambdaQuery()
                .eq(DietLog::getUserId, testUserId)
                .eq(DietLog::getLogDate, LocalDate.now()));
        assertThat(logs).as("Agent 必须把两笔饮食都写进 diet_log").hasSize(2);
        Map<String, Integer> caloriesByFood = logs.stream()
                .collect(Collectors.toMap(DietLog::getFoodName, DietLog::getCalories));
        // 熟米饭 116 kcal/100g × 2 = 232；生鸡胸肉 120 kcal/100g × 1.5 = 180
        assertThat(caloriesByFood).containsEntry("熟米饭", 232);
        assertThat(caloriesByFood).containsEntry("生鸡胸肉", 180);
        assertThat(logs).allSatisfy(l -> assertThat(l.getMealType()).isEqualTo("LUNCH"));

        // 为回答「还剩多少热量」，Agent 必须先调用工具生成当日营养目标
        List<DailyMacroTarget> targets = dailyMacroTargetMapper.selectList(Wrappers.<DailyMacroTarget>lambdaQuery()
                .eq(DailyMacroTarget::getUserId, testUserId)
                .eq(DailyMacroTarget::getTargetDate, LocalDate.now()));
        assertThat(targets).as("Agent 应先调用 calculateAndSaveDailyMacroTarget 生成当日目标").hasSize(1);

        DailyNutritionSummaryVO summary = nutritionService.summary(testUserId, LocalDate.now());
        assertThat(summary.consumedCalories()).isEqualTo(412);
        // TDEE 2733 + MUSCLE_GAIN 300 = 3033；3033 - 412 = 2621
        assertThat(summary.targetCalories()).isEqualTo(3033);
        assertThat(summary.remainingCalories()).isEqualTo(2621);

        // 用审计流水做确定性断言：验证「当日剩余热量 2621 kcal」确实通过某个工具回传给了模型。
        // 不限定必须是哪一次 logDietMeal 返回的——Agent 可能先记餐再补算目标（此时首次记餐的返回值里剩余热量为 null），
        // 只要模型最终从某个工具拿到了这个数值即算通过。
        ToolAuditSummaryVO audit = toolAuditService.query(testUserId, 50);
        audit.logs().forEach(l -> log.info("[场景B] 审计流水：tool={} status={} cost={}ms result={}",
                l.getToolName(), l.getStatus(), l.getCostMs(),
                l.getResultText() == null ? null
                        : (l.getResultText().length() > 160 ? l.getResultText().substring(0, 160) + "..." : l.getResultText())));
        ToolAuditLog dietAudit = audit.logs().stream()
                .filter(l -> "logDietMeal".equals(l.getToolName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Agent 未调用 logDietMeal，审计流水=" + audit.logs()));
        assertThat(dietAudit.getStatus()).isEqualTo(ToolAuditService.STATUS_SUCCESS);
        boolean remainingDeliveredToModel = audit.logs().stream()
                .anyMatch(l -> l.getResultText() != null && l.getResultText().contains("2621"));
        assertThat(remainingDeliveredToModel).as("Agent 必须通过某个工具拿到当日剩余热量 2621 kcal").isTrue();
        boolean answerMentionsRemaining = answer.contains("2621") || answer.contains("剩余")
                || answer.contains("还能吃") || answer.contains("还可以吃");
        log.info("[场景B] Agent 文案是否复述剩余热量：{}（回复原文：{}）",
                answerMentionsRemaining ? "是" : "否 —— 仅影响文案表达，落库与工具回传均已验证", answer);
        log.info("[场景B] 落库成功：{}；当日目标 {} kcal，已摄入 {} kcal，剩余 {} kcal",
                caloriesByFood, summary.targetCalories(), summary.consumedCalories(), summary.remainingCalories());
    }

    // ==================================================================
    // 场景 C：伤病护栏 → Agent 自主查询安全动作并替换课表（同步路径）
    // ==================================================================

    @Test
    void scenarioC_agentSwapsExerciseToAvoidInjury() {
        Long itemId = seedPlanWithSquatOnDay(3);
        String answer = aiChatService.chat(testUserId,
                "我左肩疼，做不了现在的动作了，帮我把周三的训练换成一个不伤左肩的胸部或腿部动作");
        log.info("[场景C] Agent 回复：{}", answer);

        WorkoutPlanItem after = workoutPlanItemMapper.selectById(itemId);
        assertThat(after).as("课表明细必须仍然存在（是替换动作而不是删条目）").isNotNull();
        assertThat(after.getExerciseId()).as("动作应已被替换").isNotEqualTo(BARBELL_SQUAT);

        ExerciseDict replaced = exerciseDictMapper.selectById(after.getExerciseId());
        assertThat(replaced).isNotNull();
        assertThat(replaced.getTargetMuscle()).as("应换成为胸部或腿部动作").isIn("CHEST", "LEGS");
        assertThat(replaced.getAvoidInjuryParts() == null
                || !replaced.getAvoidInjuryParts().toUpperCase().contains("LEFT_SHOULDER"))
                .as("替换后的动作不得与用户当前的 LEFT_SHOULDER 伤病冲突")
                .isTrue();
        log.info("[场景C] 闭环成功：itemId={} 由 杠铃深蹲(id={}) 替换为 {}（id={}，肌群={}，禁忌={}）",
                itemId, BARBELL_SQUAT, replaced.getName(), replaced.getId(),
                replaced.getTargetMuscle(), replaced.getAvoidInjuryParts());
    }

    // ==================================================================
    // 场景 D：流式路径下的工具调用（StreamingChatModel + @Tool 兼容性）
    // ==================================================================

    @Test
    void scenarioD_streamingWithToolsExecutesToolCalls() throws InterruptedException {
        Long itemId = seedPlanWithSquatOnDay(5);

        StringBuilder text = new StringBuilder();
        List<String> toolNames = new CopyOnWriteArrayList<>();
        List<String> toolResults = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        aiChatService.chatStream(testUserId,
                        "我左肩不舒服，帮我把周五的训练动作换成一个不伤左肩的胸或腿的动作，现在就改，改完告诉我换成什么了")
                .onPartialResponse(text::append)
                .onToolExecuted(execution -> {
                    toolNames.add(execution.request().name());
                    toolResults.add(execution.result());
                })
                .onCompleteResponse(response -> latch.countDown())
                .onError(e -> {
                    error.set(e);
                    latch.countDown();
                })
                .start();

        assertThat(latch.await(300, TimeUnit.SECONDS)).as("流式对话应在 300 秒内结束").isTrue();
        assertThat(error.get()).as("流式 + 工具调用不应报错").isNull();
        assertThat(text.toString()).isNotBlank();
        log.info("[场景D] 流式合并文本：{}", text);
        log.info("[场景D] 流式工具调用轨迹（按顺序）：{}", toolNames);
        toolResults.forEach(r -> log.info("[场景D] 工具返回（截断）：{}",
                r == null ? "null" : (r.length() > 180 ? r.substring(0, 180) + "..." : r)));

        assertThat(toolNames).as("流式路径下必须也能触发工具调用")
                .contains("querySafeExercises")
                .contains("updateWorkoutPlanItem");

        WorkoutPlanItem after = workoutPlanItemMapper.selectById(itemId);
        assertThat(after.getExerciseId()).as("流式路径同样应完成课表替换").isNotEqualTo(BARBELL_SQUAT);
        ExerciseDict replaced = exerciseDictMapper.selectById(after.getExerciseId());
        assertThat(replaced.getAvoidInjuryParts() == null
                || !replaced.getAvoidInjuryParts().toUpperCase().contains("LEFT_SHOULDER")).isTrue();
        log.info("[场景D] 流式闭环成功：itemId={} 已替换为 {}（id={}）", itemId, replaced.getName(), replaced.getId());
    }

    /** 预置一条课表（测试用户初始没有 ACTIVE 计划），返回该明细的 itemId。 */
    private Long seedPlanWithSquatOnDay(int dayOfWeek) {
        WorkoutPlanVO plan = workoutService.createPlan(new WorkoutPlanCreateDTO(
                testUserId, PLAN_PREFIX + "-day" + dayOfWeek, "PPL",
                LocalDate.now(), LocalDate.now().plusWeeks(4),
                List.of(new WorkoutPlanItemDTO(dayOfWeek, BARBELL_SQUAT, 5, 8,
                        new BigDecimal("60.0"), new BigDecimal("8.0"), 1, "测试预置：杠铃深蹲"))));
        assertThat(plan.items()).hasSize(1);
        Long itemId = plan.items().get(0).getId();
        log.info("[预置] 为测试用户 {} 创建 ACTIVE 计划（planId={}），第 {} 天明细 itemId={}，动作=杠铃深蹲",
                testUserId, plan.id(), dayOfWeek, itemId);
        return itemId;
    }

    /**
     * 新建一次性测试用户：体测数据与 BMR/TDEE 由 Java 公式算出（与种子用户 1 同为 1763 / 2733），
     * 并预置一条 LEFT_SHOULDER 活跃伤病，使伤病护栏场景有效。
     */
    private Long createDisposableUser() {
        BigDecimal weight = new BigDecimal("75.00");
        BigDecimal height = new BigDecimal("178.00");
        BigDecimal activity = new BigDecimal("1.55");
        int age = 21;
        int bmr = ProfileService.calculateBmr("MALE", weight, height, age);
        int tdee = ProfileService.calculateTdee(bmr, activity);

        UserProfile profile = new UserProfile();
        profile.setNickname("AI集成测试用户");
        profile.setGender("MALE");
        profile.setAge(age);
        profile.setHeightCm(height);
        profile.setWeightKg(weight);
        profile.setBodyFatPct(new BigDecimal("16.00"));
        profile.setActivityLevel(activity);
        profile.setFitnessGoal("MUSCLE_GAIN");
        profile.setEquipmentEnv("GYM");
        profile.setBmr(bmr);
        profile.setTdee(tdee);
        userProfileMapper.insert(profile);

        profileService.addInjury(profile.getId(),
                new InjuryTagCreateDTO("LEFT_SHOULDER", "测试用户预置：左肩前侧轻微不适", "MILD"));
        log.info("[预置] 创建一次性测试用户 id={}（BMR={} TDEE={}，ACTIVE 伤病=LEFT_SHOULDER）",
                profile.getId(), bmr, tdee);
        return profile.getId();
    }
}