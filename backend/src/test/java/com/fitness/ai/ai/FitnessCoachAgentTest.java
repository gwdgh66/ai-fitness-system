package com.fitness.ai.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.ai.memory.RedisChatMemoryStore;
import com.fitness.ai.ai.service.AiChatService;
import com.fitness.ai.nutrition.entity.DailyMacroTarget;
import com.fitness.ai.nutrition.entity.DietLog;
import com.fitness.ai.nutrition.mapper.DailyMacroTargetMapper;
import com.fitness.ai.nutrition.mapper.DietLogMapper;
import com.fitness.ai.observability.entity.ToolAuditLog;
import com.fitness.ai.observability.mapper.ToolAuditLogMapper;
import com.fitness.ai.profile.dto.InjuryTagCreateDTO;
import com.fitness.ai.profile.entity.UserInjuryTag;
import com.fitness.ai.profile.entity.UserProfile;
import com.fitness.ai.profile.mapper.UserInjuryTagMapper;
import com.fitness.ai.profile.mapper.UserProfileMapper;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.workout.entity.WorkoutLog;
import com.fitness.ai.workout.entity.WorkoutPlan;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.mapper.WorkoutLogMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanItemMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健身教练 Agent 端到端测试：真实调用大模型 + 真实 Redis 记忆。
 *
 * <p>需要设置环境变量 {@code LLM_API_KEY}（DeepSeek / 通义千问等 OpenAI 兼容协议密钥）；
 * 未设置时整个类自动跳过，避免把「没有密钥」伪装成「测试通过」。</p>
 *
 * <p><b>隔离策略：</b>每个用例新建一次性测试用户并在结束时删除。因为第 5 步挂载工具后，
 * 「我左膝盖有点隐隐作痛」这类对话会让 Agent 真的去写 user_injury_tag——
 * 用一次性用户可以把这类副作用完全挡在开发库的种子数据之外。</p>
 */
@Slf4j
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
class FitnessCoachAgentTest {

    @Autowired
    private AiChatService aiChatService;
    @Autowired
    private RedisChatMemoryStore memoryStore;
    @Autowired
    private ChatMemoryProvider chatMemoryProvider;
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
    private ToolAuditLogMapper toolAuditLogMapper;

    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = createDisposableUser();
        memoryStore.deleteMessages(testUserId);
    }

    @AfterEach
    void tearDown() {
        memoryStore.deleteMessages(testUserId);
        // 必须把该用户的所有业务流水一并清掉：挂载工具后，Agent 可能因为「我今天该吃多少」这类提问
        // 主动写入 daily_macro_target、甚至把「我膝盖疼」登记成真实伤病，并留下工具审计记录
        int audits = toolAuditLogMapper.delete(Wrappers.<ToolAuditLog>lambdaQuery()
                .eq(ToolAuditLog::getUserId, testUserId));
        int targets = dailyMacroTargetMapper.delete(Wrappers.<DailyMacroTarget>lambdaQuery()
                .eq(DailyMacroTarget::getUserId, testUserId));
        int diets = dietLogMapper.delete(Wrappers.<DietLog>lambdaQuery()
                .eq(DietLog::getUserId, testUserId));
        int logs = workoutLogMapper.delete(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, testUserId));
        int items = workoutPlanItemMapper.delete(Wrappers.<WorkoutPlanItem>lambdaQuery()
                .eq(WorkoutPlanItem::getUserId, testUserId));
        int plans = workoutPlanMapper.delete(Wrappers.<WorkoutPlan>lambdaQuery()
                .eq(WorkoutPlan::getUserId, testUserId));
        int injuries = userInjuryTagMapper.delete(Wrappers.<UserInjuryTag>lambdaQuery()
                .eq(UserInjuryTag::getUserId, testUserId));
        userProfileMapper.deleteById(testUserId);
        log.info("[清理] 一次性测试用户 {} 已删除：audits={} injuries={} plans={} items={} logs={} diets={} targets={}",
                testUserId, audits, injuries, plans, items, logs, diets, targets);
    }

    /** 验证点 1：多轮对话 + Redis 记忆（第二问必须答出第一问里的左膝） */
    @Test
    void multiTurnConversationKeepsMemory() {
        String firstAnswer = aiChatService.chat(testUserId, "我叫小明，我左膝盖有点隐隐作痛");
        assertThat(firstAnswer).isNotBlank();
        log.info("[LLM 验证] 第 1 轮问：我叫小明，我左膝盖有点隐隐作痛\n[LLM 验证] 第 1 轮答：{}", firstAnswer);

        List<ChatMessage> afterFirst = memoryStore.getMessages(testUserId);
        assertThat(afterFirst).as("第一轮结束后 Redis 应至少存有 用户+AI 两条消息")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(afterFirst).anyMatch(m -> m instanceof UserMessage);
        assertThat(afterFirst).anyMatch(m -> m instanceof AiMessage);
        log.info("[LLM 验证] 第 1 轮后 Redis 记忆条数 = {}", afterFirst.size());

        String secondAnswer = aiChatService.chat(testUserId, "我刚才说我哪里疼？");
        assertThat(secondAnswer).isNotBlank();
        log.info("[LLM 验证] 第 2 轮问：我刚才说我哪里疼？\n[LLM 验证] 第 2 轮答：{}", secondAnswer);
        assertThat(secondAnswer)
                .as("模型必须能结合 Redis 记忆答出「膝盖」")
                .contains("膝");

        List<ChatMessage> afterSecond = memoryStore.getMessages(testUserId);
        assertThat(afterSecond.size()).as("第二轮后记忆应继续增长").isGreaterThan(afterFirst.size());
        log.info("[LLM 验证] 第 2 轮后 Redis 记忆条数 = {}（多轮记忆闭环）", afterSecond.size());
    }

    /** 验证点 2：LLM 连通性（含 SystemMessage 模板变量渲染） */
    @Test
    void llmConnectivityWithProfileInjected() {
        String answer = aiChatService.chat(testUserId, "用一句话说明我今天的目标热量大概应该是多少？");
        assertThat(answer).isNotBlank();
        log.info("[LLM 连通性] 回答：{}", answer);
        log.info("[LLM 连通性] 回答长度={} 字，密钥与 base-url 配置可用", answer.length());
    }

    /** 验证点 3：TokenStream 流式输出（SSE 打字机效果的数据源） */
    @Test
    void streamingProducesIncrementalTokens() throws InterruptedException {
        List<String> chunks = new ArrayList<>();
        StringBuilder merged = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        aiChatService.chatStream(testUserId, "用三句话讲讲深蹲时膝盖的注意事项")
                .onPartialResponse(token -> {
                    chunks.add(token);
                    merged.append(token);
                })
                .onCompleteResponse(response -> latch.countDown())
                .onError(e -> {
                    error.set(e);
                    latch.countDown();
                })
                .start();

        assertThat(latch.await(300, TimeUnit.SECONDS)).as("流式响应应在 300 秒内结束").isTrue();
        assertThat(error.get()).as("流式对话不应报错").isNull();
        assertThat(chunks).as("应收到多个增量片段，而不是一次性整段返回").hasSizeGreaterThan(3);
        assertThat(merged.toString()).isNotBlank();
        log.info("[流式验证] 收到 {} 个增量片段，合并后 {} 字：{}", chunks.size(), merged.length(),
                merged.length() > 160 ? merged.substring(0, 160) + "..." : merged.toString());
    }

    /**
     * 验证点 4：清空会话记忆（{@code DELETE /api/ai/chat/memory/{userId}} 的底层实现）。
     *
     * <p>用「一个模型不可能猜到的 4 位数字」作为探针：清空后若还能答出该数字，说明记忆没清干净。</p>
     */
    @Test
    void clearMemoryForgetsHistory() {
        aiChatService.chat(testUserId, "请记住一个信息：我的会员编号是 7391。");

        assertThat(chatMemoryProvider.get(testUserId).messages())
                .as("第一轮后内存中的 ChatMemory 应已有上下文").isNotEmpty();
        assertThat(memoryStore.getMessages(testUserId))
                .as("第一轮后 Redis 应已有持久化记忆").isNotEmpty();

        aiChatService.clearMemory(testUserId);

        assertThat(chatMemoryProvider.get(testUserId).messages())
                .as("清空后内存上下文必须为空").isEmpty();
        assertThat(memoryStore.getMessages(testUserId))
                .as("清空后 Redis 必须为空").isEmpty();
        log.info("[记忆清空验证] 内存 ChatMemory 与 Redis 均已清空");

        String answer = aiChatService.chat(testUserId, "我的会员编号是多少？");
        log.info("[记忆清空验证] 清空后再问「我的会员编号是多少」，Agent 回复：{}", answer);
        assertThat(answer).as("清空后模型不可能再答出上一轮的会员编号").doesNotContain("7391");
    }

    /** 新建一次性测试用户（体测数据与种子用户 1 等价，便于复用同样的断言）。 */
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
        log.info("[预置] 创建一次性测试用户 id={}（BMR={} TDEE={}）", profile.getId(), bmr, tdee);
        return profile.getId();
    }
}