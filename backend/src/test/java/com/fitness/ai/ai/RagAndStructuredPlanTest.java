package com.fitness.ai.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.ai.memory.RedisChatMemoryStore;
import com.fitness.ai.ai.rag.LocalFitnessKnowledgeRetriever;
import com.fitness.ai.ai.service.AiChatService;
import com.fitness.ai.ai.service.AiWorkoutPlannerService;
import com.fitness.ai.ai.vo.AiPlanGenerationResultVO;
import com.fitness.ai.dashboard.service.DashboardService;
import com.fitness.ai.dashboard.vo.DemoSeedResultVO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.mapper.ExerciseDictMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 7 步验证：RAG 召回精度、RAG + Agent 问答、AI 结构化排课落库。
 *
 * <p>用 {@code @Order} 固定执行顺序，最后一个用例重新播种 user 1 的 14 天演示数据，
 * 保证交付给前端时数据是饱满状态。仅涉及大模型的用例带 LLM_API_KEY 条件，
 * 纯 Java 的检索与播种用例在无密钥时也能独立验证。</p>
 */
@Slf4j
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagAndStructuredPlanTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private LocalFitnessKnowledgeRetriever retriever;
    @Autowired
    private AiChatService aiChatService;
    @Autowired
    private AiWorkoutPlannerService aiWorkoutPlannerService;
    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private RedisChatMemoryStore memoryStore;
    @Autowired
    private ExerciseDictMapper exerciseDictMapper;
    @Autowired
    private WorkoutPlanItemMapper workoutPlanItemMapper;

    // ==================================================================
    // 验证点 1：RAG 召回精度（纯 Java，不需要大模型）
    // ==================================================================

    @Test
    @Order(1)
    void ragRetrievalHitsExpectedSections() {
        assertThat(retriever.segmentCount()).as("知识库应切分为 8 个二级标题切片").isEqualTo(8);

        assertTopHit("左肩疼怎么练胸", "肩");
        assertTopHit("什么时候需要减载周", "减载");
        assertTopHit("RPE 10 每次都练到力竭怎么办", "RPE");
        assertTopHit("膝关节疼痛怎么练腿", "膝");
        assertTopHit("下背痛还能做俯身划船吗", "下背");
        assertTopHit("增肌每周需要多少组", "容量");
        assertTopHit("碳水循环怎么安排", "碳水");
        assertTopHit("双渐进法怎么加重", "双渐进");
    }

    private void assertTopHit(String query, String expectedTitleKeyword) {
        List<LocalFitnessKnowledgeRetriever.ScoredHit> hits = retriever.search(query, 3);
        assertThat(hits).as("查询「" + query + "」不应为空").isNotEmpty();
        log.info("[验证1] 查询「{}」Top-3：", query);
        hits.forEach(h -> log.info("[验证1]    《{}》 score={} (bm25={} cosine={} keywordBonus={}) 命中词={}",
                h.sectionTitle(), round(h.score()), round(h.bm25()), round(h.cosine()),
                round(h.keywordBonus()), h.matchedTerms()));
        assertThat(hits.get(0).sectionTitle())
                .as("查询「" + query + "」的 Top-1 应命中包含「" + expectedTitleKeyword + "」的章节")
                .contains(expectedTitleKeyword);
    }

    // ==================================================================
    // 验证点 2：RAG + Agent 问答（需要大模型）
    // ==================================================================

    @Test
    @Order(2)
    @EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
    void agentAnswerBlendsKnowledgeBase() {
        memoryStore.deleteMessages(USER_ID);
        String answer = aiChatService.chat(USER_ID,
                "我目前卧推卡平台期了，而且每次都练到力竭 RPE 10，按运动科学我该怎么调整？");
        log.info("[验证2] Agent 回答：{}", answer);
        assertThat(answer).isNotBlank();
        assertThat(answer).as("应体现知识库中的双渐进法")
                .containsAnyOf("双渐进", "Double Progression", "渐进超负荷");
        assertThat(answer).as("应体现知识库中的减载周建议")
                .containsAnyOf("减载", "Deload", "deload");
        assertThat(answer).as("应体现知识库中的 RPE 控制区间")
                .containsAnyOf("7.5", "8.5", "RIR");
        memoryStore.deleteMessages(USER_ID);
    }

    // ==================================================================
    // 验证点 3：AI 结构化排课落库 + 护栏（需要大模型）
    // ==================================================================

    @Test
    @Order(3)
    @EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
    void structuredPlanIsPersistedAndRespectsAllGuardrails() {
        AiPlanGenerationResultVO result = aiWorkoutPlannerService.generatePlan(USER_ID,
                "帮我排一套每周4练的上下肢分化增肌计划，重点照顾我的左肩伤病");
        log.info("[验证3] 排课结果：{} planId={} 名称={} 分化={} 动作 {} 个",
                result.message(), result.plan().id(), result.plan().planName(),
                result.plan().splitType(), result.plan().items().size());
        log.info("[验证3] AI 排课理由：{}", result.coachReasoning());
        result.retryHistory().forEach(h -> log.info("[验证3] 纠错重试轨迹：{}", h));

        assertThat(result.plan().items()).as("必须真正落库动作").isNotEmpty();
        assertThat(result.attempts()).isGreaterThanOrEqualTo(1);
        assertThat(result.plan().status()).isEqualTo("ACTIVE");
        assertThat(result.plan().splitType()).isIn("PPL", "UPPER_LOWER", "FULL_BODY");

        int leftShoulderConflicts = 0;
        Map<Integer, Integer> setsByDay = new LinkedHashMap<>();
        for (WorkoutPlanItem item : result.plan().items()) {
            ExerciseDict exercise = exerciseDictMapper.selectById(item.getExerciseId());
            assertThat(exercise).as("exerciseId 必须真实存在：" + item.getExerciseId()).isNotNull();
            String avoid = exercise.getAvoidInjuryParts();
            boolean conflicts = avoid != null && avoid.toUpperCase().contains("LEFT_SHOULDER");
            if (conflicts) {
                leftShoulderConflicts++;
            }
            assertThat(item.getTargetSets()).isBetween(1, 15);
            assertThat(item.getTargetReps()).isBetween(1, 100);
            setsByDay.merge(item.getDayOfWeek(), item.getTargetSets(), Integer::sum);
            log.info("[验证3] 动作：周{} {} (id={}) {}组×{}次 {}kg RPE={} 禁忌={}",
                    item.getDayOfWeek(), item.getExerciseName(), item.getExerciseId(),
                    item.getTargetSets(), item.getTargetReps(), item.getTargetWeightKg(),
                    item.getTargetRpe(), avoid);
        }
        setsByDay.forEach((day, sets) -> log.info("[验证3] 周{} 单日总组数 = {}", day, sets));

        assertThat(leftShoulderConflicts).as("落库动作中不得包含 LEFT_SHOULDER 禁忌动作").isZero();
        setsByDay.values().forEach(sets ->
                assertThat(sets).as("单日总组数不得超过 30").isLessThanOrEqualTo(30));

        // 再从数据库复核一次（防止只信内存对象）
        List<WorkoutPlanItem> persisted = workoutPlanItemMapper.selectList(Wrappers.<WorkoutPlanItem>lambdaQuery()
                .eq(WorkoutPlanItem::getPlanId, result.plan().id()));
        assertThat(persisted).hasSameSizeAs(result.plan().items());
        for (WorkoutPlanItem item : persisted) {
            ExerciseDict exercise = exerciseDictMapper.selectById(item.getExerciseId());
            assertThat(exercise.getAvoidInjuryParts() == null
                    || !exercise.getAvoidInjuryParts().toUpperCase().contains("LEFT_SHOULDER"))
                    .as("库中动作不得含 LEFT_SHOULDER 禁忌：" + exercise.getName())
                    .isTrue();
        }
        log.info("[验证3] 数据库复核通过：planId={} 共 {} 条明细，LEFT_SHOULDER 冲突 0 个，单日组数 {} 全部 ≤30",
                result.plan().id(), persisted.size(), setsByDay);
    }

    // ==================================================================
    // 收尾：重新播种演示数据（保证前端演示时数据饱满）
    // ==================================================================

    @Test
    @Order(4)
    void reseedDemoDataForFrontend() {
        DemoSeedResultVO seed = dashboardService.demoSeed(USER_ID);
        log.info("[收尾] 演示数据已重新播种：课表 {}（{} 个动作）训练打卡 {} 条 饮食打卡 {} 条 营养目标 {} 天",
                seed.planName(), seed.planItems(), seed.workoutLogs(), seed.dietLogs(), seed.macroTargets());
        assertThat(seed.workoutLogs()).isGreaterThan(0);
        assertThat(seed.dietLogs()).isEqualTo(63);
        assertThat(seed.macroTargets()).isEqualTo(7);
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}