package com.fitness.ai.common.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局序列化约定：**Long / long 一律序列化为 JSON 字符串**。
 *
 * <p>为什么必须这样做：本项目主键用 MyBatis-Plus 雪花算法（{@code IdType.ASSIGN_ID}），
 * 数值量级约 2.1 × 10^18，远超 JavaScript {@code Number.MAX_SAFE_INTEGER}（2^53-1 ≈ 9.007 × 10^15）。</p>
 *
 * <p>浏览器 {@code JSON.parse} 会把超限整数四舍五入成最近的可表示 double，
 * 于是「前端拿到的 id」与「库里的 id」差几十甚至上百；再把这个 id 拼回 URL 或请求体，
 * 后端自然查不到记录 —— 表现为莫名其妙的「套餐模板不存在 / 动作不存在 / itemId 无效」。</p>
 *
 * <p>序列化成字符串后：前端拿到的 id 是精确文本，原样回传（Jackson 反序列化时把字符串
 * 精确解析为 Long，不经过 double），雪花 ID 全链路无损。
 * 注意：反序列化方向不受影响，请求体里传数字或字符串都能正常绑定。</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}