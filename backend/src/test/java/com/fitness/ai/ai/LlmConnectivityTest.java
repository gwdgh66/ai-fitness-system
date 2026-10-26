package com.fitness.ai.ai;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯 LLM 连通性检查：直接打 ChatModel Bean，不经过会话记忆，因此不依赖 Redis。
 *
 * <p>用于快速判定「密钥 / base-url / model-name / 网络」这四件事是否配好。
 * 未设置 LLM_API_KEY 时自动跳过。</p>
 */
@Slf4j
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
class LlmConnectivityTest {

    @Autowired
    private ChatModel chatModel;

    @Test
    void rawChatModelResponds() {
        long start = System.currentTimeMillis();
        ChatResponse response = chatModel.chat(List.of(
                UserMessage.from("请只回复四个字：连接正常")));
        long cost = System.currentTimeMillis() - start;

        assertThat(response).isNotNull();
        assertThat(response.aiMessage()).isNotNull();
        String text = response.aiMessage().text();
        assertThat(text).as("模型必须返回非空内容").isNotBlank();
        log.info("[LLM 连通性] 密钥/base-url/model 校验通过，耗时 {} ms，模型返回：{}", cost, text);
        if (response.tokenUsage() != null) {
            log.info("[LLM 连通性] token 用量：输入 {} / 输出 {}",
                    response.tokenUsage().inputTokenCount(), response.tokenUsage().outputTokenCount());
        }
    }
}