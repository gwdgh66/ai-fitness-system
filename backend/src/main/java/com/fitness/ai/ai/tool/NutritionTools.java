package com.fitness.ai.ai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.nutrition.dto.DietLogCreateDTO;
import com.fitness.ai.nutrition.dto.MacroTargetGenerateDTO;
import com.fitness.ai.nutrition.entity.DailyMacroTarget;
import com.fitness.ai.nutrition.entity.DietLog;
import com.fitness.ai.nutrition.entity.FoodNutritionDict;
import com.fitness.ai.nutrition.service.NutritionService;
import com.fitness.ai.nutrition.vo.DailyNutritionSummaryVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 饮食账单与热量工具集。
 *
 * <p>刻意不提供「自报热量」参数：食物热量与三大营养素只能由本地字典 + Java 折算得出，
 * 从工具层面堵死「让大模型估算热量」这条路。</p>
 */
@Slf4j
@Component("nutritionTools")
public class NutritionTools {

    private final NutritionService nutritionService;
    private final ObjectMapper objectMapper;

    public NutritionTools(NutritionService nutritionService, ObjectMapper objectMapper) {
        this.nutritionService = nutritionService;
        this.objectMapper = objectMapper;
    }

    @Tool("""
            按名称关键字模糊查询本地食物营养字典（数据按每 100g 计）。当用户提到吃某种食物、或你不确定食物名时
            先调用本工具确认字典里有没有该食物及其标准名称。
            返回 JSON 数组，每项含：foodName、category、caloriesPer100g、proteinPer100g、carbsPer100g、fatPer100g、unitHint。""")
    public String searchFoodNutrition(
            @P("食物名称关键字，例如「鸡胸」「米饭」「蛋白粉」「西兰花」")
            String keyword) {
        log.info("[Tool] searchFoodNutrition keyword={}", keyword);
        List<FoodNutritionDict> foods = nutritionService.searchFoods(keyword);
        List<Map<String, Object>> items = new ArrayList<>();
        for (FoodNutritionDict f : foods) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("foodName", f.getFoodName());
            item.put("category", f.getCategory());
            item.put("caloriesPer100g", f.getCaloriesPer100g());
            item.put("proteinPer100g", f.getProteinPer100g());
            item.put("carbsPer100g", f.getCarbsPer100g());
            item.put("fatPer100g", f.getFatPer100g());
            item.put("unitHint", f.getUnitHint());
            items.add(item);
        }
        return json(items);
    }

    @Tool("""
            根据用户的 TDEE、健身目标与碳水日类型，生成或更新某一天的三大营养素目标（热量 / 蛋白质 / 碳水 / 脂肪）。
            当用户问「我今天该吃多少」「还剩多少热量可以吃」而当日还没有目标时，先调用本工具生成目标。
            本工具内含热量底线护栏：若算出的目标热量低于安全底线（max(1000, BMR×0.8) kcal），会返回 ERROR 提示，需要调整目标或日型。
            严禁自己口算目标热量与营养素克重，必须引用本工具返回值。
            返回：{"targetDate":"..","dayType":"..","targetCalories":..,"targetProteinG":..,"targetCarbsG":..,"targetFatG":..}。""")
    public String calculateAndSaveDailyMacroTarget(
            @ToolMemoryId Long userId,
            @P(value = "目标日期，格式 yyyy-MM-dd；用户说「今天」就留空，默认今天", required = false) String date,
            @P(value = "碳水循环日型：HIGH_CARB（高碳日，训练量大）/ MEDIUM_CARB（中碳日，默认）/ LOW_CARB（低碳日，休息日）", required = false)
            String dayType) {
        log.info("[Tool] calculateAndSaveDailyMacroTarget userId={} date={} dayType={}", userId, date, dayType);
        try {
            DailyMacroTarget target = nutritionService.generateTarget(new MacroTargetGenerateDTO(
                    userId, parseDate(date), dayType == null || dayType.isBlank() ? "MEDIUM_CARB" : dayType));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("targetDate", String.valueOf(target.getTargetDate()));
            result.put("dayType", target.getDayType());
            result.put("targetCalories", target.getTargetCalories());
            result.put("targetProteinG", target.getTargetProteinG());
            result.put("targetCarbsG", target.getTargetCarbsG());
            result.put("targetFatG", target.getTargetFatG());
            return json(result);
        } catch (BusinessException e) {
            return error(e, "可尝试把 dayType 改为 MEDIUM_CARB 或 HIGH_CARB 提高目标热量，或先与用户确认是否需要调整健身目标。");
        }
    }

    @Tool("""
            记录用户吃下的一餐（食物 + 克重），落库时按本地字典每 100g 数据用 Java 精确折算热量与三大营养素。
            仅支持字典中已有的食物：若返回以 ERROR: 开头（食物不在字典中或有多个同名候选），
            请改用 searchFoodNutrition 的结果里的标准食物名重新调用，不要自行估算热量。
            返回：{"logId":..,"foodName":"..","weightG":..,"calories":..,"proteinG":..,"carbsG":..,"fatG":..,
            "consumedCalories":..,"remainingCalories":..}。""")
    public String logDietMeal(
            @ToolMemoryId Long userId,
            @P("食物名称，尽量使用字典里的标准名，例如「生鸡胸肉」「熟米饭」「燕麦片（干）」")
            String foodName,
            @P("食用重量（克），例如 200")
            Double weightG,
            @P("餐次：BREAKFAST（早餐）/ LUNCH（午餐）/ DINNER（晚餐）/ SNACK（加餐/零食）")
            String mealType,
            @P(value = "记录日期，格式 yyyy-MM-dd；用户说「今天」就留空，默认今天", required = false) String date) {
        log.info("[Tool] logDietMeal userId={} food={} weight={}g meal={}", userId, foodName, weightG, mealType);
        try {
            LocalDate logDate = parseDate(date);
            String resolvedName = resolveFoodName(foodName);
            if (resolvedName.startsWith("ERROR: ")) {
                return resolvedName;
            }
            if (weightG == null || weightG <= 0) {
                return "ERROR: weightG（食用重量，单位克）必须大于 0，当前值=" + weightG + "。请向用户确认克重后重试。";
            }
            DietLog saved = nutritionService.addDietLog(new DietLogCreateDTO(
                    userId, logDate, mealType, resolvedName,
                    BigDecimal.valueOf(weightG), null, null, null, null));

            DailyNutritionSummaryVO summary = nutritionService.summary(userId, logDate);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("logId", saved.getId());
            result.put("foodName", saved.getFoodName());
            result.put("mealType", saved.getMealType());
            result.put("logDate", String.valueOf(saved.getLogDate()));
            result.put("weightG", saved.getWeightG());
            result.put("calories", saved.getCalories());
            result.put("proteinG", saved.getProteinG());
            result.put("carbsG", saved.getCarbsG());
            result.put("fatG", saved.getFatG());
            result.put("dailyTargetCalories", summary.targetCalories());
            result.put("consumedCalories", summary.consumedCalories());
            result.put("remainingCalories", summary.remainingCalories());
            if (summary.remainingCalories() == null) {
                result.put("hint", "【内部指引：仅用于你自己决定下一步，禁止转述给用户、禁止提及工具名】"
                        + "该日期还没有营养目标，因此无法给出剩余热量。若用户想知道还能吃多少，"
                        + "请先调用 calculateAndSaveDailyMacroTarget 生成当日目标（dayType 缺省用 MEDIUM_CARB）。");
            }
            return json(result);
        } catch (BusinessException e) {
            return error(e, "请用 searchFoodNutrition 确认食物标准名与克重后重试。");
        }
    }

    @Tool("""
            查询用户某一天的饮食账单汇总：当日目标值、已摄入热量与三大营养素、剩余缺口、以及当天所有饮食流水明细。
            当用户问「我今天吃了多少」「还剩多少热量」时调用。
            返回：{"date":"..","targetCalories":..,"consumedCalories":..,"remainingCalories":..,
            "consumedProteinG":..,"consumedCarbsG":..,"consumedFatG":..,"logs":[...]}。""")
    public String getDailyNutritionSummary(
            @ToolMemoryId Long userId,
            @P(value = "查询日期，格式 yyyy-MM-dd；用户说「今天」就留空，默认今天", required = false) String date) {
        LocalDate queryDate = parseDate(date);
        log.info("[Tool] getDailyNutritionSummary userId={} date={}", userId, queryDate);
        DailyNutritionSummaryVO summary = nutritionService.summary(userId, queryDate);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", String.valueOf(queryDate));
        result.put("dayType", summary.dayType());
        result.put("targetCalories", summary.targetCalories());
        result.put("targetProteinG", summary.targetProteinG());
        result.put("targetCarbsG", summary.targetCarbsG());
        result.put("targetFatG", summary.targetFatG());
        result.put("consumedCalories", summary.consumedCalories());
        result.put("consumedProteinG", summary.consumedProteinG());
        result.put("consumedCarbsG", summary.consumedCarbsG());
        result.put("consumedFatG", summary.consumedFatG());
        result.put("remainingCalories", summary.remainingCalories());
        result.put("remainingProteinG", summary.remainingProteinG());
        result.put("remainingCarbsG", summary.remainingCarbsG());
        result.put("remainingFatG", summary.remainingFatG());
        result.put("logs", summary.logs().stream().map(l -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("foodName", l.getFoodName());
            item.put("mealType", l.getMealType());
            item.put("weightG", l.getWeightG());
            item.put("calories", l.getCalories());
            return item;
        }).toList());
        return json(result);
    }

    /** 把用户口语化的食物名解析成字典里的标准名；歧义或不存在时返回 ERROR 指引。 */
    private String resolveFoodName(String foodName) {
        if (foodName == null || foodName.isBlank()) {
            return "ERROR: foodName 不能为空，请向用户确认食物名称。";
        }
        List<FoodNutritionDict> matches = nutritionService.searchFoods(foodName);
        if (matches.isEmpty()) {
            return "ERROR: 食物[" + foodName + "]不在本地营养字典中。请调用 searchFoodNutrition 用更通用的关键字"
                    + "（如「鸡胸」「米饭」「蛋白粉」）重新检索，或先向用户确认食物的准确名称与克重；不要自行估算热量。";
        }
        for (FoodNutritionDict match : matches) {
            if (match.getFoodName().equalsIgnoreCase(foodName.trim())) {
                return match.getFoodName();
            }
        }
        if (matches.size() > 1) {
            List<String> names = matches.stream().map(FoodNutritionDict::getFoodName).toList();
            return "ERROR: 食物名[" + foodName + "]在字典中有多个候选：" + names
                    + "。请选择最接近用户描述的一个，并用其完整名称作为 foodName 重新调用本工具。";
        }
        return matches.get(0).getFoodName();
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "日期格式非法：" + date + "，必须为 yyyy-MM-dd，例如 2026-10-25");
        }
    }

    private String error(BusinessException e, String hint) {
        String message = "ERROR: " + e.getMessage();
        return hint == null ? message : message + " 【内部指引：仅用于你自己修正本次调用，"
                + "禁止把本段文字、工具名、参数名或字段名转述给用户】修正建议：" + hint;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("工具结果序列化失败", e);
            return "ERROR: 工具结果序列化失败：" + e.getOriginalMessage();
        }
    }
}