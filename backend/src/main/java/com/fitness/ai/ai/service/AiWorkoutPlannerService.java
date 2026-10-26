package com.fitness.ai.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.ai.ai.dto.AiGeneratedPlanDTO;
import com.fitness.ai.ai.dto.AiGeneratedPlanItemDTO;
import com.fitness.ai.ai.rag.LocalFitnessKnowledgeRetriever;
import com.fitness.ai.ai.vo.AiPlanGenerationResultVO;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.profile.vo.UserDigitalTwinVO;
import com.fitness.ai.workout.dto.WorkoutPlanCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanItemDTO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.service.WorkoutService;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 结构化周期排课服务：一句话需求 →（LLM + 安全白名单）→ 结构化 JSON → Java 护栏落库 → 违规则带错重试。
 *
 * <p>闭环设计（AGENTS.md 第 1、4 条的落地）：</p>
 * <ol>
 *   <li><b>前置上下文</b>：由 Java 查出数字孪生档案与「已过滤活跃伤病」的安全动作白名单，
 *       白名单里带精确 exerciseId，模型只能从中选；</li>
 *   <li><b>强约束 Prompt</b>：要求只输出纯 JSON，并给出结构示例与全部硬性护栏数值；</li>
 *   <li><b>护栏自检</b>：解析后直接交给 {@link WorkoutService#createPlan} 落库，伤病冲突、单动作组数、
 *       单日总组数等护栏在这里生效；</li>
 *   <li><b>带错重试</b>：任何 {@link BusinessException}（含 JSON 解析失败）都会把原始错误信息
 *       拼回提示词让模型立即重新生成，最多重试 2 次（合计最多 3 次调用）。</li>
 * </ol>
 */
@Slf4j
@Service
public class AiWorkoutPlannerService {

    /** 除首次生成外，最多再重试 2 次 */
    private static final int MAX_RETRIES = 2;
    /** 生成计划的默认周期长度（周） */
    private static final int PLAN_WEEKS = 8;
    private static final String SPLIT_TYPES = "PPL / UPPER_LOWER / FULL_BODY";
    /** 拼入排课 Prompt 的知识库检索意图（周容量 / 每组次数区间 / RPE / 减载 / 双渐进） */
    private static final String KB_QUERY = "肌肥大周容量黄金区间 每组次数区间 RPE 8.5 减载周 双渐进法加重";
    /** 知识摘要每段截断长度 */
    private static final int KB_DIGEST_CHARS = 340;

    private final ChatModel chatModel;
    private final WorkoutService workoutService;
    private final ProfileService profileService;
    private final LocalFitnessKnowledgeRetriever knowledgeRetriever;
    private final ObjectMapper objectMapper;

    public AiWorkoutPlannerService(ChatModel chatModel,
                                   WorkoutService workoutService,
                                   ProfileService profileService,
                                   LocalFitnessKnowledgeRetriever knowledgeRetriever,
                                   ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.workoutService = workoutService;
        this.profileService = profileService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成并激活一份新的周训练计划。
     *
     * @param userId          目标用户
     * @param userRequirement 用户的自然语言排课诉求
     */
    public AiPlanGenerationResultVO generatePlan(Long userId, String userRequirement) {
        UserDigitalTwinVO twin = profileService.getDigitalTwin(userId);
        List<ExerciseDict> safeExercises = workoutService.findSafeExercises(userId, null, null);
        if (safeExercises.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "用户 " + userId + " 没有任何可用的安全动作，无法生成课表");
        }
        String profileJson = toJson(twin);
        String safeExercisesJson = toJson(safeExercises.stream().map(this::toWhitelistEntry).toList());
        // 从内置运动科学知识库检索「周容量 / 每组次数 / RPE / 减载 / 双渐进」原文摘要，作为排课的循证依据
        String knowledgeDigest = knowledgeRetriever.digest(KB_QUERY, 4, KB_DIGEST_CHARS);
        log.info("[AI 排课] 用户 {} 前置上下文：安全动作白名单 {} 个（已过滤活跃伤病）；知识库摘要 {} 字",
                userId, safeExercises.size(), knowledgeDigest.length());

        List<String> retryHistory = new ArrayList<>();
        String previousError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            String prompt = buildPrompt(userRequirement, profileJson, safeExercisesJson, knowledgeDigest, previousError);
            AiGeneratedPlanDTO generated;
            try {
                generated = callAndParse(prompt);
            } catch (BusinessException e) {
                previousError = "上一次输出无法解析为合法 JSON（" + e.getMessage()
                        + "）。请只输出一个纯 JSON 对象，不要包含任何解释文字、前后缀或 Markdown 代码块。";
                retryHistory.add("第 " + attempt + " 次尝试：JSON 解析失败 → " + e.getMessage());
                log.warn("[AI 排课] 第 {} 次尝试解析失败：{}", attempt, e.getMessage());
                continue;
            }

            try {
                WorkoutPlanVO plan = createPlan(userId, generated);
                log.info("[AI 排课] 第 {} 次尝试即通过护栏校验并落库：planId={} 动作 {} 个",
                        attempt, plan.id(), plan.items().size());
                String message = attempt == 1
                        ? "AI 排课一次通过护栏校验并已激活。"
                        : "AI 排课经过 " + attempt + " 次尝试（前 " + (attempt - 1) + " 次被护栏拦截后自我修正）并已激活。";
                return new AiPlanGenerationResultVO(plan, generated.coachReasoning(), attempt,
                        List.copyOf(retryHistory), message);
            } catch (BusinessException e) {
                previousError = "上一次生成的课表被 Java 安全护栏拒绝，原因：" + e.getMessage()
                        + " 请严格修正后重新输出完整 JSON。";
                retryHistory.add("第 " + attempt + " 次尝试：护栏拦截 → " + e.getMessage());
                log.warn("[AI 排课] 第 {} 次尝试被护栏拦截：{}", attempt, e.getMessage());
            }
        }
        throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                "AI 排课连续 " + (MAX_RETRIES + 1) + " 次未通过护栏校验，最后一次原因：" + previousError
                        + "；重试轨迹：" + retryHistory);
    }

    // ------------------------------------------------------------------
    // Prompt 与解析
    // ------------------------------------------------------------------

    private String buildPrompt(String requirement, String profileJson, String safeExercisesJson,
                              String knowledgeDigest, String previousError) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一名持证体能教练（CSCS）。请根据用户诉求设计一份周期化的周训练计划，并只输出一个 JSON 对象。\n\n");
        prompt.append("【用户数字孪生档案】\n").append(profileJson).append("\n\n");
        prompt.append("【可用安全动作白名单】\n")
                .append("以下动作已经过滤掉该用户当前的活跃伤病禁忌，你只能从这份白名单里选择 exerciseId：\n")
                .append(safeExercisesJson).append("\n\n");
        prompt.append("【运动科学循证依据（摘自项目内置知识库，排课时必须遵循）】\n")
                .append(knowledgeDigest).append("\n\n");
        prompt.append("【用户诉求】\n").append(requirement).append("\n\n");
        prompt.append("""
                【硬性约束（违反会被 Java 护栏拒绝并打回重做）】
                1. exerciseId 必须来自上面的白名单，exerciseName 必须与白名单中的 name 完全一致；
                2. 单个动作的 targetSets 必须在 1~15 之间；
                3. 同一天（dayOfWeek 相同）所有动作的 targetSets 之和不得超过 30；
                4. targetReps 在 1~100 之间，建议落在增肌区间 6~15 次；targetRpe 在 1.0~10.0 之间，
                   按运动科学建议复合动作控制在 7.0~8.5；
                5. dayOfWeek 用 1~7 表示周一至周日（例如三分化可排 1、3、5）；
                6. splitType 只能是 %s 之一；
                7. 每个训练日安排 3~6 个动作；如果用户诉求里提到的训练频率与分化方式冲突，以用户诉求为准；
                8. 每周每个大肌群的有效组数控制在 10~20 组（腿部/背部可到 20 组上限，手臂等小肌群 8~12 组），
                   不要出现某个肌群一周超过 20 组、或某个大肌群一周不足 10 组的情况；
                9. coachReasoning 里必须明确写出你这套计划如何体现循证依据（周容量分配、每组次数区间、
                   RPE 控制与减载/渐进策略），并说明针对用户伤病的动作取舍理由；
                10. 只输出一个 JSON 对象，不要输出任何解释文字、Markdown 代码块或前后缀。
                """.formatted(SPLIT_TYPES));
        prompt.append("""
                【输出 JSON 结构示例】
                {"planName":"推拉腿三分化·增肌强化","splitType":"PPL","coachReasoning":"为什么这样排、有哪些注意事项",
                 "items":[{"dayOfWeek":1,"exerciseId":5,"exerciseName":"器械推胸","targetSets":4,"targetReps":8,
                 "targetWeightKg":40.0,"targetRpe":8.0,"notes":"肩胛后缩下沉，全程控制离心"}]}
                """);
        if (previousError != null) {
            prompt.append("\n【上一次尝试失败的原因，请务必修正】\n").append(previousError).append("\n");
        }
        return prompt.toString();
    }

    private AiGeneratedPlanDTO callAndParse(String prompt) {
        ChatResponse response = chatModel.chat(List.of(UserMessage.from(prompt)));
        String raw = response.aiMessage().text();
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "模型返回内容为空");
        }
        String json = extractJson(raw);
        try {
            AiGeneratedPlanDTO dto = objectMapper.readValue(json, AiGeneratedPlanDTO.class);
            if (dto.items() == null || dto.items().isEmpty()) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "JSON 中 items 为空");
            }
            return dto;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "JSON 解析失败：" + e.getMessage());
        }
    }

    /** 容错提取 JSON 主体：兼容模型误加 ```json 代码块或前后解释文字的情况。 */
    private String extractJson(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstBrace = text.indexOf('{');
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                text = text.substring(firstBrace, lastBrace + 1);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "返回内容中找不到 JSON 对象：" + abbreviate(raw));
        }
        return text.substring(start, end + 1);
    }

    // ------------------------------------------------------------------
    // 落库
    // ------------------------------------------------------------------

    /** 把结构化输出转成课表创建请求并落库（护栏在 WorkoutService 中生效）。 */
    private WorkoutPlanVO createPlan(Long userId, AiGeneratedPlanDTO generated) {
        if (generated.items() == null || generated.items().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED, "AI 生成的课表 items 为空，无法落库");
        }
        List<WorkoutPlanItemDTO> items = new ArrayList<>();
        Map<Integer, Integer> sortOrderByDay = new LinkedHashMap<>();
        for (AiGeneratedPlanItemDTO item : generated.items()) {
            if (item.dayOfWeek() == null || item.exerciseId() == null
                    || item.targetSets() == null || item.targetReps() == null) {
                throw new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED,
                        "AI 生成的明细缺少必填字段（dayOfWeek / exerciseId / targetSets / targetReps）：" + item);
            }
            int order = sortOrderByDay.merge(item.dayOfWeek(), 1, Integer::sum);
            items.add(new WorkoutPlanItemDTO(item.dayOfWeek(), item.exerciseId(), item.targetSets(),
                    item.targetReps(), item.targetWeightKg(), item.targetRpe(), order, item.notes()));
        }
        String planName = generated.planName() == null || generated.planName().isBlank()
                ? "AI 生成周计划" : generated.planName().trim();
        String splitType = generated.splitType() == null || generated.splitType().isBlank()
                ? "PPL" : generated.splitType().trim();
        LocalDate today = LocalDate.now();
        return workoutService.createPlan(new WorkoutPlanCreateDTO(userId, planName, splitType,
                today, today.plusWeeks(PLAN_WEEKS), items));
    }

    private Map<String, Object> toWhitelistEntry(ExerciseDict exercise) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("exerciseId", exercise.getId());
        entry.put("name", exercise.getName());
        entry.put("targetMuscle", exercise.getTargetMuscle());
        entry.put("equipment", exercise.getEquipment());
        entry.put("difficulty", exercise.getDifficulty());
        entry.put("avoidInjuryParts", exercise.getAvoidInjuryParts());
        return entry;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上下文序列化失败：" + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}