package com.fitness.ai.ai;

import com.fitness.ai.ai.memory.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话记忆持久化测试：直接打真实 Redis，验证 ChatMemoryStore 的读写、Key 设计与 TTL。
 *
 * <p>不需要 LLM API Key，但需要本机 6379 有可用 Redis；Redis 不可达时整个类自动跳过
 * （会在测试报告里明确标记为 skipped，不会假装通过）。</p>
 */
@Slf4j
@SpringBootTest
@EnabledIf("redisAvailable")
class RedisChatMemoryStoreTest {

    private static final Long MEMORY_ID = 987_654_321L;

    /** Redis 可达性探测（默认按 application.yml 的 localhost:6379） */
    static boolean redisAvailable() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, 6379), 800);
            return true;
        } catch (Exception e) {
            log.warn("Redis({}:6379) 不可达，跳过会话记忆测试：{}", host, e.getMessage());
            return false;
        }
    }

    @Autowired
    private RedisChatMemoryStore memoryStore;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void memoryStoreRoundTripWithTtl() {
        memoryStore.deleteMessages(MEMORY_ID);
        assertThat(memoryStore.getMessages(MEMORY_ID)).as("未写入时应返回空列表").isEmpty();

        List<ChatMessage> history = List.of(
                UserMessage.from("我叫小明，我左膝盖有点隐隐作痛"),
                AiMessage.from("收到，训练时请避开高冲击的腿部动作。"));
        memoryStore.updateMessages(MEMORY_ID, history);

        // 1) Key 设计
        String expectedKey = "fitness:chat:memory:" + MEMORY_ID;
        assertThat(RedisChatMemoryStore.buildKey(MEMORY_ID)).isEqualTo(expectedKey);
        assertThat(stringRedisTemplate.hasKey(expectedKey)).isTrue();

        // 2) 落库的是 JSON 数组，且内容可读
        String raw = stringRedisTemplate.opsForValue().get(expectedKey);
        assertThat(raw).startsWith("[").contains("小明").contains("膝盖");
        log.info("[记忆存储] Redis 原始值（截断）：{}", raw.length() > 220 ? raw.substring(0, 220) + "..." : raw);

        // 3) 读回后角色与内容完整还原
        List<ChatMessage> loaded = memoryStore.getMessages(MEMORY_ID);
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0)).isInstanceOf(UserMessage.class);
        assertThat(loaded.get(1)).isInstanceOf(AiMessage.class);
        assertThat(((UserMessage) loaded.get(0)).singleText()).contains("我叫小明");
        assertThat(((AiMessage) loaded.get(1)).text()).contains("高冲击");

        // 4) TTL 应为 7 天（允许中间有几秒误差）
        Long ttl = memoryStore.getTtlSeconds(MEMORY_ID);
        assertThat(ttl).isNotNull().isBetween(7 * 24 * 3600L - 60, 7 * 24 * 3600L);
        log.info("[记忆存储] 往返成功：消息 {} 条，角色还原正常，TTL={} 秒", loaded.size(), ttl);

        // 5) 清除
        memoryStore.deleteMessages(MEMORY_ID);
        assertThat(memoryStore.getMessages(MEMORY_ID)).isEmpty();
        assertThat(stringRedisTemplate.hasKey(expectedKey)).isFalse();
        log.info("[记忆存储] 清除成功：key={} 已不存在", expectedKey);
    }

    @Test
    void memoryIsolatedByMemoryId() {
        Long other = MEMORY_ID + 1;
        memoryStore.deleteMessages(MEMORY_ID);
        memoryStore.deleteMessages(other);
        try {
            memoryStore.updateMessages(MEMORY_ID, List.of(UserMessage.from("用户A的消息")));
            memoryStore.updateMessages(other, List.of(UserMessage.from("用户B的消息")));

            assertThat(((UserMessage) memoryStore.getMessages(MEMORY_ID).get(0)).singleText()).isEqualTo("用户A的消息");
            assertThat(((UserMessage) memoryStore.getMessages(other).get(0)).singleText()).isEqualTo("用户B的消息");
            log.info("[记忆存储] 多用户隔离正常：memoryId={} 与 {} 内容互不影响", MEMORY_ID, other);
        } finally {
            memoryStore.deleteMessages(MEMORY_ID);
            memoryStore.deleteMessages(other);
        }
    }
}