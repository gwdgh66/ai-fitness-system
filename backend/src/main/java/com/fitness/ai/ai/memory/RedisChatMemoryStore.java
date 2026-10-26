package com.fitness.ai.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的持久化会话记忆实现。
 *
 * <p>Key 设计：{@code fitness:chat:memory:{memoryId}}，TTL 7 天，避免长期累积撑爆 Redis。</p>
 *
 * <p>序列化直接复用 LangChain4j 自带的 {@link ChatMessageSerializer} /
 * {@link ChatMessageDeserializer}（内部是基于 Jackson 的多态编解码，能正确区分
 * UserMessage / AiMessage / SystemMessage / ToolExecutionResultMessage），
 * 这样后续第 5 步挂载 Tool 之后仍然可以直接落库，无需改动存储层。</p>
 */
@Slf4j
@Repository
public class RedisChatMemoryStore implements ChatMemoryStore {

    /** Redis Key 前缀 */
    public static final String KEY_PREFIX = "fitness:chat:memory:";

    /** 会话记忆过期时间：7 天 */
    public static final Duration MEMORY_TTL = Duration.ofDays(7);

    private final StringRedisTemplate stringRedisTemplate;

    public RedisChatMemoryStore(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 统一 Key 拼装，便于排查与测试断言。 */
    public static String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            log.debug("会话记忆为空（首次对话）：key={}", key);
            return new ArrayList<>();
        }
        try {
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
            log.debug("会话记忆命中：key={} 历史消息 {} 条", key, messages.size());
            return messages;
        } catch (RuntimeException e) {
            // 反序列化失败不能让整轮对话崩掉：记警告并按空记忆处理，避免脏数据永久卡死该用户
            log.warn("会话记忆反序列化失败，已按空记忆处理：key={}，可调用 deleteMessages 清理", key, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        stringRedisTemplate.opsForValue()
                .set(key, ChatMessageSerializer.messagesToJson(messages), MEMORY_TTL);
        log.debug("会话记忆已写入：key={} 消息 {} 条 TTL={}", key, messages.size(), MEMORY_TTL);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = buildKey(memoryId);
        Boolean deleted = stringRedisTemplate.delete(key);
        log.info("会话记忆已清除：key={} deleted={}", key, deleted);
    }

    /** 剩余存活时间（秒），-2 表示 key 不存在；供测试与运维排查使用。 */
    public Long getTtlSeconds(Object memoryId) {
        return stringRedisTemplate.getExpire(buildKey(memoryId));
    }
}