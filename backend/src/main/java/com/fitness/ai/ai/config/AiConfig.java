package com.fitness.ai.ai.config;

import com.fitness.ai.ai.memory.RedisChatMemoryStore;
import com.fitness.ai.ai.rag.LocalFitnessKnowledgeRetriever;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 相关 Bean 装配。
 */
@Slf4j
@Configuration
public class AiConfig {

    /** 每个 memoryId 保留的最近消息条数（20 条 ≈ 10 轮问答）。 */
    private static final int MAX_MESSAGES_PER_MEMORY = 20;

    /**
     * 会话记忆工厂：按 memoryId（此处即 userId）隔离不同用户的上下文，
     * 底层存储统一走 {@link RedisChatMemoryStore} 落 Redis。
     *
     * <p>Bean 名称必须是 chatMemoryProvider，{@code @AiService} 中按名引用它。</p>
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore redisChatMemoryStore) {
        return new CachingChatMemoryProvider(redisChatMemoryStore, MAX_MESSAGES_PER_MEMORY);
    }

    /**
     * RAG 检索增强器：把本地运动科学知识库包装成 LangChain4j 标准的 {@link RetrievalAugmentor}，
     * 由 {@code @AiService(retrievalAugmentor = "retrievalAugmentor")} 挂载给健身教练 Agent。
     *
     * <p>每轮对话会先把用户问题投给 {@link LocalFitnessKnowledgeRetriever} 召回 Top-3 知识切片，
     * 再把切片拼进用户消息一并发给模型，因此模型的建议有循证依据而不是自由发挥。</p>
     */
    @Bean
    public RetrievalAugmentor retrievalAugmentor(LocalFitnessKnowledgeRetriever localFitnessKnowledgeRetriever) {
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(localFitnessKnowledgeRetriever)
                .build();
    }

    /**
     * 按 memoryId 缓存 {@link ChatMemory} 实例的记忆工厂。
     *
     * <p><b>为什么必须自己缓存：</b>{@code @AiService} 内部会为每个 memoryId 缓存一份 ChatMemory 实例。
     * 如果 provider 每次都 new 一个新对象，那么「清空会话」时拿到的会是另一个对象——
     * 内存里的旧消息清不掉，还会在下一轮 add() 时被整份写回 Redis，等于清空失效。
     * 自己缓存后，{@code provider.get(userId).clear()} 清的就是 Agent 正在使用的同一个实例，
     * 内存列表与 Redis 会一起被清干净。</p>
     */
    static final class CachingChatMemoryProvider implements ChatMemoryProvider {

        private final Map<Object, ChatMemory> memories = new ConcurrentHashMap<>();
        private final RedisChatMemoryStore chatMemoryStore;
        private final int maxMessages;

        CachingChatMemoryProvider(RedisChatMemoryStore chatMemoryStore, int maxMessages) {
            this.chatMemoryStore = chatMemoryStore;
            this.maxMessages = maxMessages;
        }

        @Override
        public ChatMemory get(Object memoryId) {
            return memories.computeIfAbsent(memoryId, id -> {
                log.debug("为 memoryId={} 创建新的会话记忆实例", id);
                return MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(maxMessages)
                        .chatMemoryStore(chatMemoryStore)
                        .build();
            });
        }
    }
}