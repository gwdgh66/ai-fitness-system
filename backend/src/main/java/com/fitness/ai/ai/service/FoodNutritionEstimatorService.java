package com.fitness.ai.ai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.ai.ai.dto.FoodNutritionEstimateRequest;
import com.fitness.ai.ai.vo.FoodNutritionEstimateVO;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.profile.service.ProfileService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * AI 食物营养估算（**只产出建议值，不写库**）。
 *
 * <p>为什么不让 AI 直接算并落库：AGENTS.md 第 1 条要求"数值由 Java 确定性计算"。
 * 自制菜/外卖这类食物确实没有字典数据，允许 AI 给参考值能显著降低填表成本，
 * 但必须守住三条边界：</p>
 * <ol>
 *   <li><b>AI 不算总量</b>：只给「每 100g」参考值，×克重÷100 的折算与汇总由 Java 完成；</li>
 *   <li><b>必须人工确认</b>：接口只返回值给前端填表，用户确认/修改后才走正常打卡接口落库；</li>
 *   <li><b>Java 二次校验</b>：范围（热量 0~1000 kcal/100g、单一营养素 0~100 g/100g）与
 *       量纲自洽（4P+4C+9F 与给出热量偏差 ≤ max(25, 25%)），并把来源标记为 AI_ESTIMATED。</li>
 * </ol>
 */
@Slf4j
@Service
public class FoodNutritionEstimatorService {

    private static final String DISCLAIMER =
            "AI 估算仅供填表参考，已由后端做范围与量纲校验；落库前请人工确认。折算（×克重÷100）与汇总由 Java 完成。";

    private final ChatModel chatModel;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper;

    public FoodNutritionEstimatorService(ChatModel chatModel,
                                         ProfileService profileService,
                                         ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.profileService = profileService;
        this.objectMapper = objectMapper;
    }

    /** 估算某食物「每 100g」营养，返回建议值（不落库）。 */
    public FoodNutritionEstimateVO estimate(FoodNutritionEstimateRequest request) {
        profileService.getDigitalTwin(request.userId());
        long startedAt = System.currentTimeMillis();

        String prompt = """
                你是运动营养数据助手。请估算「%s」每 100 克（固体）或每 100 毫升（液体）的热量与三大营养素。
                补充说明：%s

                【硬性要求】
                1. 只输出一个 JSON 对象，不要任何解释文字、前后缀或 Markdown 代码块；
                2. caloriesPer100g 为整数 kcal；proteinPer100g / carbsPer100g / fatPer100g 为每 100g 的克数（保留 1 位小数）；
                3. 若是成品菜 / 外卖 / 自制料理，按常见配方给出「代表值」，并在 basis 里写清依据
                   （例如「按家常红烧做法，含烹调油约 10g/100g」）；
                4. confidence 只能是 HIGH / MEDIUM / LOW；信息不足以判断时必须给 LOW，并在 basis 里说明缺什么；
                5. 若食物名无意义或完全无法识别，输出 {"error":"无法识别该食物"}。

                【输出结构示例】
                {"caloriesPer100g":120,"proteinPer100g":22.5,"carbsPer100g":0.0,"fatPer100g":2.6,
                 "basis":"参考 USDA 生鸡胸肉数据","confidence":"HIGH"}
                """.formatted(request.foodName().trim(),
                request.note() == null || request.note().isBlank()
                        ? "（无）" + (request.weightG() == null ? "" : "，本次计划摄入 " + request.weightG() + " g")
                        : request.note().trim());

        ChatResponse response = chatModel.chat(List.of(UserMessage.from(prompt)));
        String raw = response.aiMessage().text();
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "模型未返回任何内容，请重试或手动填写营养数值");
        }
        ParsedEstimate parsed = parse(raw);
        long elapsed = System.currentTimeMillis() - startedAt;
        log.info("[AI 营养估算] 食物={} 每100g: {} kcal / P{} / C{} / F{} · 把握={} · 自洽={} · 耗时={}ms",
                request.foodName(), parsed.caloriesPer100g(), parsed.proteinPer100g(),
                parsed.carbsPer100g(), parsed.fatPer100g(), parsed.confidence(), parsed.consistent(), elapsed);

        return new FoodNutritionEstimateVO(request.foodName().trim(), parsed.caloriesPer100g(),
                parsed.proteinPer100g(), parsed.carbsPer100g(), parsed.fatPer100g(),
                parsed.basis(), parsed.confidence(), parsed.consistent(), parsed.warning(),
                elapsed, DISCLAIMER);
    }

    // ------------------------------------------------------------------
    // 解析 + Java 侧校验
    // ------------------------------------------------------------------

    private ParsedEstimate parse(String raw) {
        String json = extractJson(raw);
        EstimatePayload payload;
        try {
            payload = objectMapper.readValue(json, EstimatePayload.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "AI 返回内容无法解析为营养 JSON（" + e.getMessage() + "），请重试或手动填写");
        }
        if (payload.error() != null && !payload.error().isBlank()) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "AI 无法识别该食物：" + payload.error() + "，请手动填写每 100g 营养数值");
        }
        if (payload.caloriesPer100g() == null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "AI 未给出热量数值，请重试或手动填写");
        }

        int calories = payload.caloriesPer100g();
        BigDecimal protein = orZero(payload.proteinPer100g());
        BigDecimal carbs = orZero(payload.carbsPer100g());
        BigDecimal fat = orZero(payload.fatPer100g());

        // ① 范围护栏
        if (calories < 0 || calories > 1000) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "AI 给出的每 100g 热量 " + calories + " kcal 超出合理范围（0 ~ 1000），请手动填写");
        }
        for (String[] pair : new String[][]{{"蛋白质", protein.toPlainString()}, {"碳水", carbs.toPlainString()}, {"脂肪", fat.toPlainString()}}) {
            BigDecimal value = new BigDecimal(pair[1]);
            if (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                        "AI 给出的每 100g " + pair[0] + " " + value + " g 超出合理范围（0 ~ 100），请手动填写");
            }
        }

        // ② 量纲自洽：4P + 4C + 9F 与给出热量的偏差
        BigDecimal derived = protein.multiply(new BigDecimal("4"))
                .add(carbs.multiply(new BigDecimal("4")))
                .add(fat.multiply(new BigDecimal("9")));
        BigDecimal tolerance = BigDecimal.valueOf(Math.max(25, Math.round(calories * 0.25)));
        BigDecimal deviation = derived.subtract(BigDecimal.valueOf(calories)).abs();
        boolean consistent = deviation.compareTo(tolerance) <= 0;

        String confidence = payload.confidence() == null ? "LOW"
                : payload.confidence().trim().toUpperCase(Locale.ROOT);
        if (!List.of("HIGH", "MEDIUM", "LOW").contains(confidence)) {
            confidence = "LOW";
        }

        StringBuilder warning = new StringBuilder();
        if (!consistent) {
            warning.append("三大营养素折算热量 ").append(derived.setScale(1, RoundingMode.HALF_UP))
                    .append(" kcal 与给出热量 ").append(calories)
                    .append(" kcal 偏差较大（容差 ±").append(tolerance).append(" kcal），请核对后再落库。");
        }
        if ("LOW".equals(confidence)) {
            if (warning.length() > 0) {
                warning.append(' ');
            }
            warning.append("AI 自评把握度较低，建议按实际配方手动修正。");
        }

        return new ParsedEstimate(calories, protein, carbs, fat,
                payload.basis() == null ? "AI 未给出依据" : payload.basis().trim(),
                confidence, consistent, warning.length() == 0 ? null : warning.toString());
    }

    /** 容错提取 JSON 主体（兼容模型误加代码块或前后解释文字）。 */
    private String extractJson(String raw) {
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "AI 返回内容中没有 JSON 对象：" + (text.length() > 160 ? text.substring(0, 160) + "…" : text));
        }
        return text.substring(start, end + 1);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EstimatePayload(
            Integer caloriesPer100g,
            BigDecimal proteinPer100g,
            BigDecimal carbsPer100g,
            BigDecimal fatPer100g,
            String basis,
            String confidence,
            String error
    ) {
    }

    private record ParsedEstimate(
            Integer caloriesPer100g,
            BigDecimal proteinPer100g,
            BigDecimal carbsPer100g,
            BigDecimal fatPer100g,
            String basis,
            String confidence,
            boolean consistent,
            String warning
    ) {
    }
}