package com.fitness.ai.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.ai.ai.dto.AiChatRequest;
import com.fitness.ai.ai.dto.AiPlanGenerateRequest;
import com.fitness.ai.ai.dto.FoodNutritionEstimateRequest;
import com.fitness.ai.ai.rag.LocalFitnessKnowledgeRetriever;
import com.fitness.ai.ai.rag.vo.RagSearchResultVO;
import com.fitness.ai.ai.service.AiChatService;
import com.fitness.ai.ai.service.AiWorkoutPlannerService;
import com.fitness.ai.ai.service.FoodNutritionEstimatorService;
import com.fitness.ai.ai.vo.AiPlanGenerationResultVO;
import com.fitness.ai.ai.vo.FoodNutritionEstimateVO;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.common.Result;
import dev.langchain4j.service.TokenStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 聊天接口。
 *
 * <h3>SSE 事件契约（{@code GET /api/ai/chat/stream}）</h3>
 * <pre>
 *   event: message   data: {"content":"增量文本"}    ← 每个 token 一帧，前端拼接即打字机效果
 *   event: done      data: {}                       ← 正常结束，随后连接关闭
 *   event: error     data: {"message":"错误原因"}    ← 出错时下发，随后连接关闭
 * </pre>
 *
 * <p>为什么 token 要包一层 JSON 而不是直接发裸文本：SSE 的 data 字段以换行为分隔，
 * 模型输出的换行（Markdown 段落、列表）如果直接发裸文本会丢帧或被截断；
 * 走 JSON 转义后换行可以原样还原，前端 {@code JSON.parse(e.data).content} 即可。</p>
 */
@Slf4j
@Tag(name = "AI Chat", description = "健身教练 Agent 对话（含 SSE 流式输出）")
@RestController
@RequestMapping("/api/ai")
@Validated
public class AiChatController {

    /** SSE 连接超时：5 分钟，避免长回答被容器默认 30s 掐断 */
    private static final long SSE_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private static final String EVENT_MESSAGE = "message";
    /** 工具执行完成后下发：告知前端丢弃此前的 token（那是模型的内部独白，不该给用户看） */
    private static final String EVENT_RESET = "reset";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";

    private final AiChatService aiChatService;
    private final AiWorkoutPlannerService aiWorkoutPlannerService;
    private final FoodNutritionEstimatorService foodNutritionEstimatorService;
    private final LocalFitnessKnowledgeRetriever localFitnessKnowledgeRetriever;
    private final ObjectMapper objectMapper;

    public AiChatController(AiChatService aiChatService,
                            AiWorkoutPlannerService aiWorkoutPlannerService,
                            FoodNutritionEstimatorService foodNutritionEstimatorService,
                            LocalFitnessKnowledgeRetriever localFitnessKnowledgeRetriever,
                            ObjectMapper objectMapper) {
        this.aiChatService = aiChatService;
        this.aiWorkoutPlannerService = aiWorkoutPlannerService;
        this.foodNutritionEstimatorService = foodNutritionEstimatorService;
        this.localFitnessKnowledgeRetriever = localFitnessKnowledgeRetriever;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "同步对话", description = "基于 Redis 会话记忆的多轮对话，返回完整回答")
    @PostMapping("/chat")
    public Result<String> chat(@Valid @RequestBody AiChatRequest request) {
        String answer = aiChatService.chat(request.userId(), request.message());
        return Result.success(answer);
    }

    @Operation(summary = "流式对话（SSE）", description = "逐 token 推送，供前端打字机效果使用")
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam Long userId,
                                 @RequestParam @NotBlank String message) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        try {
            TokenStream tokenStream = aiChatService.chatStream(userId, message);
            tokenStream
                    .onPartialResponse(token -> sendEvent(emitter, EVENT_MESSAGE, "content", token))
                    // 关键：工具调用之前那一轮模型的输出是它的「独白」（例如“我先查一下食物字典，再写入账单”），
                    // 属于内部过程，对用户没有意义。这里在每次工具执行后下发 reset 事件，
                    // 让前端清空当前气泡，只保留工具执行完之后产出的最终答复。
                    .onToolExecuted(execution -> {
                        log.info("流式对话触发工具调用：userId={} tool={}", userId, execution.request().name());
                        sendEvent(emitter, EVENT_RESET, "reason", "tool_executed");
                    })
                    .onCompleteResponse(response -> {
                        sendEvent(emitter, EVENT_DONE, null, null);
                        emitter.complete();
                    })
                    .onError(error -> {
                        log.error("流式对话失败：userId={}", userId, error);
                        sendEvent(emitter, EVENT_ERROR, "message",
                                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                        emitter.complete();
                    })
                    .start();
        } catch (RuntimeException e) {
            // 上下文组装阶段（如用户不存在、Redis 不可用）同步抛出的异常：
            // 此时 SSE 流还没开始，若直接抛给 GlobalExceptionHandler 会因为 produces=text/event-stream
            // 产生内容协商冲突，所以统一转成 error 事件下发
            log.error("流式对话初始化失败：userId={}", userId, e);
            sendEvent(emitter, EVENT_ERROR, "message",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            emitter.complete();
        }
        return emitter;
    }

    @Operation(summary = "清空指定用户的会话记忆",
            description = "同时清除内存中的 ChatMemory 实例与 Redis 中的对话历史，供前端「重置会话」使用")
    @DeleteMapping("/chat/memory/{userId}")
    public Result<String> clearMemory(@PathVariable Long userId) {
        aiChatService.clearMemory(userId);
        return Result.success("用户 " + userId + " 的会话记忆已清空");
    }

    @Operation(summary = "RAG 知识库召回调试",
            description = "直接查看本地运动科学知识库对某个问题的 Top-K 召回切片与打分明细（BM25 / 余弦 / 关键词加分）")
    @GetMapping("/rag/search")
    public Result<RagSearchResultVO> ragSearch(@RequestParam @NotBlank String query,
                                               @RequestParam(defaultValue = "3") int topK) {
        if (topK < 1 || topK > 10) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "topK=" + topK + " 超出范围，必须在 1 ~ 10 之间");
        }
        List<LocalFitnessKnowledgeRetriever.ScoredHit> hits =
                localFitnessKnowledgeRetriever.search(query, topK);
        List<RagSearchResultVO.Hit> voHits = new ArrayList<>();
        int rank = 1;
        for (LocalFitnessKnowledgeRetriever.ScoredHit hit : hits) {
            voHits.add(new RagSearchResultVO.Hit(rank++, hit.sectionTitle(), round(hit.score()),
                    round(hit.bm25()), round(hit.cosine()), round(hit.keywordBonus()),
                    hit.matchedTerms(), hit.segment().text()));
        }
        return Result.success(new RagSearchResultVO(query, localFitnessKnowledgeRetriever.segmentCount(),
                topK, voHits));
    }

    @Operation(summary = "AI 结构化周期排课",
            description = "一句话需求 → LLM 结合数字孪生与安全动作白名单生成结构化课表 → Java 护栏校验落库（违规自动带错重试）")
    @PostMapping("/plan/generate")
    public Result<AiPlanGenerationResultVO> generatePlan(@Valid @RequestBody AiPlanGenerateRequest request) {
        return Result.success(aiWorkoutPlannerService.generatePlan(request.userId(), request.requirement()));
    }

    @Operation(summary = "AI 估算自定义食物营养（建议值，不落库）",
            description = "字典里没有的食物（自制菜/外卖）可让 AI 给出「每 100g」参考值填入表单；"
                    + "接口本身不写库，用户确认后才走打卡接口，且来源会标记为 AI_ESTIMATED")
    @PostMapping("/food-estimate")
    public Result<FoodNutritionEstimateVO> estimateFood(@Valid @RequestBody FoodNutritionEstimateRequest request) {
        return Result.success(foodNutritionEstimatorService.estimate(request));
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private void sendEvent(SseEmitter emitter, String eventName, String field, String value) {
        try {
            String payload;
            if (field == null) {
                payload = "{}";
            } else {
                payload = objectMapper.writeValueAsString(java.util.Map.of(field, value));
            }
            emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端主动断开（刷新/关闭页面）属于正常现象，不升级为错误
            log.debug("SSE 下发失败，客户端可能已断开：event={} cause={}", eventName, e.getMessage());
        }
    }
}