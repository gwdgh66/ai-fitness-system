package com.fitness.ai.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.ai.ai.agent.FitnessCoachAgent;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.profile.entity.UserInjuryTag;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.profile.vo.UserDigitalTwinVO;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话服务：负责组装「用户上下文」并驱动 {@link FitnessCoachAgent}。
 *
 * <p>关键设计：用户档案与伤病永不接受前端传入，每轮都由本服务从数据库实时查询后注入 SystemMessage，
 * 保证模型看到的身体状态始终是最新的、可信的。</p>
 */
@Slf4j
@Service
public class AiChatService {

    private final FitnessCoachAgent fitnessCoachAgent;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper;
    private final ChatMemoryProvider chatMemoryProvider;

    public AiChatService(FitnessCoachAgent fitnessCoachAgent,
                         ProfileService profileService,
                         ObjectMapper objectMapper,
                         ChatMemoryProvider chatMemoryProvider) {
        this.fitnessCoachAgent = fitnessCoachAgent;
        this.profileService = profileService;
        this.objectMapper = objectMapper;
        this.chatMemoryProvider = chatMemoryProvider;
    }

    /**
     * 同步对话。
     *
     * <p>刻意不加 {@code @Transactional}：数据库读取已由 ProfileService 自己的只读事务包住，
     * 而真正的 LLM 调用要耗时数秒到数十秒，若把它套在事务里会长时间占用数据库连接。</p>
     */
    public String chat(Long userId, String userMessage) {
        AiContext context = buildContext(userId);
        log.info("同步对话开始：userId={} 消息长度={}", userId, userMessage.length());
        return fitnessCoachAgent.chat(userId, userMessage, context.profileJson(), context.injuryJson());
    }

    /** 流式对话，返回尚未 start() 的 TokenStream，由 Controller 负责注册回调。 */
    public TokenStream chatStream(Long userId, String userMessage) {
        AiContext context = buildContext(userId);
        log.info("流式对话开始：userId={} 消息长度={}", userId, userMessage.length());
        return fitnessCoachAgent.chatStream(userId, userMessage, context.profileJson(), context.injuryJson());
    }

    /**
     * 清空指定用户的会话记忆（内存中的 ChatMemory 实例 + Redis 持久化）。
     *
     * <p>注意这里不能只删 Redis key：仍需清掉 Agent 正在使用的那个 ChatMemory 实例的内存列表，
     * 否则下一轮对话会带着旧上下文继续，并把旧消息整份写回 Redis（详见 AiConfig 的说明）。</p>
     */
    public void clearMemory(Long userId) {
        chatMemoryProvider.get(userId).clear();
        log.info("已清空用户 {} 的会话记忆（内存实例 + Redis）", userId);
    }

    /** 组装 SystemMessage 所需的两段 JSON（档案 + 活跃伤病）。 */
    private AiContext buildContext(Long userId) {
        UserDigitalTwinVO twin = profileService.getDigitalTwin(userId);
        String profileJson = toJson(twin);
        // 伤病只保留模型真正需要的字段，去掉 id/userId/时间戳等噪音
        List<Map<String, Object>> injuryBrief = twin.activeInjuries().stream()
                .map(AiChatService::toInjuryBrief)
                .toList();
        String injuryJson = toJson(injuryBrief);
        log.debug("用户 {} 上下文已组装：活跃伤病 {} 条", userId, injuryBrief.size());
        return new AiContext(profileJson, injuryJson);
    }

    private static Map<String, Object> toInjuryBrief(UserInjuryTag tag) {
        Map<String, Object> brief = new LinkedHashMap<>();
        // 带上 injuryId：markInjuryRecovered 工具需要它来定位要标记为康复的记录
        brief.put("injuryId", tag.getId());
        brief.put("bodyPart", tag.getBodyPart());
        brief.put("severity", tag.getSeverity());
        brief.put("status", tag.getStatus());
        brief.put("description", tag.getDescription());
        return brief;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "用户上下文序列化失败：" + e.getOriginalMessage(), e);
        }
    }

    /** 组装好的上下文载体。 */
    private record AiContext(String profileJson, String injuryJson) {
    }
}