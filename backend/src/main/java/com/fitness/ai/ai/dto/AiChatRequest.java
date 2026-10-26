package com.fitness.ai.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 同步对话入参（{@code POST /api/ai/chat}）。
 *
 * <p>用户档案与伤病信息不从这里传入，一律由 Service 依据 userId 从数据库实时查询，
 * 防止前端伪造「我没有伤病」绕过安全前提。</p>
 */
public record AiChatRequest(
        @NotNull(message = "userId 不能为空")
        Long userId,

        @NotBlank(message = "message 不能为空")
        String message
) {
}