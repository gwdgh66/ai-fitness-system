package com.fitness.ai.catalog.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.ai.catalog.dto.MealItemDTO;
import com.fitness.ai.catalog.dto.MealTemplateCreateDTO;
import com.fitness.ai.catalog.dto.WorkoutTemplateApplyDTO;
import com.fitness.ai.catalog.entity.MealTemplate;
import com.fitness.ai.catalog.mapper.MealTemplateMapper;
import com.fitness.ai.catalog.vo.MealTemplateApplyVO;
import com.fitness.ai.catalog.vo.WorkoutTemplateApplyVO;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.nutrition.dto.DietLogCreateDTO;
import com.fitness.ai.nutrition.entity.DietLog;
import com.fitness.ai.nutrition.entity.FoodNutritionDict;
import com.fitness.ai.nutrition.mapper.FoodNutritionDictMapper;
import com.fitness.ai.nutrition.service.NutritionService;
import com.fitness.ai.nutrition.vo.DailyNutritionSummaryVO;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.workout.dto.WorkoutPlanCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanItemDTO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.service.WorkoutService;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 预设模板库与一键打卡中心。
 *
 * <ul>
 *   <li>饮食套餐模板：新增时由 Java 依食物字典逐项折算并汇总总热量/三大营养素；一键打卡按餐次批量写入 diet_log。</li>
 *   <li>训练模板：内置 PPL_3DAY / UPPER_LOWER_4DAY 两套经典分化，毫秒级纯 Java 排课，
 *       每个槽位都从「已过滤该用户 ACTIVE 伤病」的安全动作里挑代表动作。</li>
 * </ul>
 */
@Slf4j
@Service
public class CatalogService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Set<String> MEAL_TYPES = Set.of("BREAKFAST", "LUNCH", "DINNER", "SNACK");

    /** 各肌群排课时的初始目标重量（kg），供渐进算法做基准 */
    private static final Map<String, BigDecimal> BASE_WEIGHTS = Map.of(
            "CHEST", new BigDecimal("40.0"),
            "BACK", new BigDecimal("50.0"),
            "LEGS", new BigDecimal("60.0"),
            "SHOULDERS", new BigDecimal("15.0"),
            "ARMS", new BigDecimal("15.0"),
            "CORE", BigDecimal.ZERO);

    private final MealTemplateMapper mealTemplateMapper;
    private final FoodNutritionDictMapper foodNutritionDictMapper;
    private final NutritionService nutritionService;
    private final WorkoutService workoutService;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper;

    public CatalogService(MealTemplateMapper mealTemplateMapper,
                          FoodNutritionDictMapper foodNutritionDictMapper,
                          NutritionService nutritionService,
                          WorkoutService workoutService,
                          ProfileService profileService,
                          ObjectMapper objectMapper) {
        this.mealTemplateMapper = mealTemplateMapper;
        this.foodNutritionDictMapper = foodNutritionDictMapper;
        this.nutritionService = nutritionService;
        this.workoutService = workoutService;
        this.profileService = profileService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 饮食套餐模板
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MealTemplate> listMealTemplates(Long userId) {
        profileService.getDigitalTwin(userId);
        return mealTemplateMapper.selectList(Wrappers.<MealTemplate>lambdaQuery()
                .eq(MealTemplate::getUserId, userId)
                .orderByAsc(MealTemplate::getId));
    }

    /** 新增套餐模板：逐项查字典折算后汇总，字典里没有的食物直接拒绝。 */
    @Transactional
    public MealTemplate createMealTemplate(MealTemplateCreateDTO dto) {
        profileService.getDigitalTwin(dto.userId());
        String mealType = requireInSet("mealType", dto.mealType(), MEAL_TYPES);

        List<MealItemDTO> normalizedItems = new ArrayList<>();
        int totalCalories = 0;
        BigDecimal totalProtein = BigDecimal.ZERO;
        BigDecimal totalCarbs = BigDecimal.ZERO;
        BigDecimal totalFat = BigDecimal.ZERO;

        for (MealItemDTO item : dto.items()) {
            if (item.weightG() == null || item.weightG().signum() <= 0) {
                throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                        "食物[" + item.foodName() + "]的克重必须大于 0，当前值=" + item.weightG());
            }
            String itemName = item.foodName() == null ? "" : item.foodName().trim();
            FoodNutritionDict food = findFoodOrNull(itemName);

            int caloriesPer100g;
            BigDecimal proteinPer100g;
            BigDecimal carbsPer100g;
            BigDecimal fatPer100g;
            String source;
            if (food != null) {
                // 字典命中：一律以字典为准（即便调用方传了数值，也不允许覆盖）
                caloriesPer100g = food.getCaloriesPer100g();
                proteinPer100g = food.getProteinPer100g();
                carbsPer100g = food.getCarbsPer100g();
                fatPer100g = food.getFatPer100g();
                source = NutritionService.SOURCE_DICTIONARY;
                itemName = food.getFoodName();
            } else if (item.caloriesPer100g() != null) {
                // 自定义食物：调用方给「每 100g」数值，折算与汇总全部由 Java 完成
                validateCustomPer100g(itemName, item);
                caloriesPer100g = item.caloriesPer100g();
                proteinPer100g = item.proteinPer100g();
                carbsPer100g = item.carbsPer100g();
                fatPer100g = item.fatPer100g();
                source = normalizeSource(item.source());
            } else {
                throw new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED,
                        "食物[" + itemName + "]不在本地营养字典中，且未提供每 100g 营养数值。"
                                + "自定义食物请补充 caloriesPer100g（以及可选的 proteinPer100g / carbsPer100g / fatPer100g），"
                                + "或改用字典中已有的食物名（GET /api/nutrition/foods?keyword= 可检索）。");
            }

            BigDecimal ratio = item.weightG().divide(HUNDRED, 10, RoundingMode.HALF_UP);
            totalCalories += roundHalfUp(BigDecimal.valueOf(caloriesPer100g).multiply(ratio));
            totalProtein = totalProtein.add(scale2(multiply(proteinPer100g, ratio)));
            totalCarbs = totalCarbs.add(scale2(multiply(carbsPer100g, ratio)));
            totalFat = totalFat.add(scale2(multiply(fatPer100g, ratio)));
            // 把「每 100g 营养 + 来源」一起存进 items_json：一键打卡时无需再查字典，自定义食物也能落库
            normalizedItems.add(new MealItemDTO(itemName, item.weightG(), caloriesPer100g,
                    proteinPer100g, carbsPer100g, fatPer100g, source));
        }

        MealTemplate entity = new MealTemplate();
        entity.setUserId(dto.userId());
        entity.setTemplateName(dto.templateName().trim());
        entity.setMealType(mealType);
        entity.setItemsJson(writeJson(normalizedItems));
        entity.setTotalCalories(totalCalories);
        entity.setTotalProteinG(totalProtein.setScale(2, RoundingMode.HALF_UP));
        entity.setTotalCarbsG(totalCarbs.setScale(2, RoundingMode.HALF_UP));
        entity.setTotalFatG(totalFat.setScale(2, RoundingMode.HALF_UP));
        mealTemplateMapper.insert(entity);
        log.info("新增套餐模板：user={} name={} {} kcal / P{}g / C{}g / F{}g", dto.userId(),
                entity.getTemplateName(), totalCalories, entity.getTotalProteinG(),
                entity.getTotalCarbsG(), entity.getTotalFatG());
        return mealTemplateMapper.selectById(entity.getId());
    }

    /** 一键套餐打卡：把模板里的所有食物按指定餐次写入某一天的 diet_log。 */
    @Transactional
    public MealTemplateApplyVO applyMealTemplate(Long templateId, LocalDate date, String mealTypeOverride) {
        MealTemplate template = mealTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "套餐模板不存在：templateId=" + templateId);
        }
        LocalDate logDate = date == null ? LocalDate.now() : date;
        String mealType = mealTypeOverride == null || mealTypeOverride.isBlank()
                ? template.getMealType()
                : requireInSet("mealType", mealTypeOverride, MEAL_TYPES);

        List<MealItemDTO> items = readItems(template.getItemsJson());
        int addedCalories = 0;
        for (MealItemDTO item : items) {
            DietLog saved = nutritionService.addDietLog(new DietLogCreateDTO(template.getUserId(), logDate, mealType,
                    item.foodName(), item.weightG(), null, null, null, null,
                    item.caloriesPer100g(), item.proteinPer100g(), item.carbsPer100g(), item.fatPer100g(),
                    item.source()), item.source());
            addedCalories += saved.getCalories() == null ? 0 : saved.getCalories();
        }
        DailyNutritionSummaryVO summary = nutritionService.summary(template.getUserId(), logDate);
        log.info("一键套餐打卡：user={} 模板={} 写入 {} 条食物，合计 {} kcal", template.getUserId(),
                template.getTemplateName(), items.size(), addedCalories);
        return new MealTemplateApplyVO(template.getId(), template.getTemplateName(), logDate, mealType,
                items.size(), addedCalories, summary);
    }

    // ------------------------------------------------------------------
    // 训练模板
    // ------------------------------------------------------------------

    /** 一键套用经典分化模板：纯 Java 排课 + 自动避开该用户当前 ACTIVE 伤病。 */
    @Transactional
    public WorkoutTemplateApplyVO applyWorkoutTemplate(WorkoutTemplateApplyDTO dto) {
        return applyWorkoutTemplate(dto, false);
    }

    /**
     * 一键套用经典分化模板。
     *
     * @param systemExercisesOnly true 时只用系统内置动作。演示播种固定传 true：
     *                            否则用户后来新建的自定义动作会进入演示课表，让演示场景（动作数 / 打卡数）不可复现。
     *                            用户自己点「套用模板」时传 false —— 自定义动作与内置动作同等参与排课。
     */
    @Transactional
    public WorkoutTemplateApplyVO applyWorkoutTemplate(WorkoutTemplateApplyDTO dto, boolean systemExercisesOnly) {
        profileService.getDigitalTwin(dto.userId());
        WorkoutTemplate template = WorkoutTemplate.fromCode(dto.templateCode());

        List<WorkoutPlanItemDTO> items = new ArrayList<>();
        List<String> generated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, Integer> rotation = new HashMap<>();
        // 同一天内不重复排同一个动作：伤病过滤后某肌群只剩一个可用动作时，宁可少排一个槽位
        Map<Integer, Set<Long>> usedByDay = new HashMap<>();
        // sort_order 约定为「当天内的顺序」（与 AI 排课、删除后重排保持一致），而不是全计划连续编号
        Map<Integer, Integer> orderByDay = new HashMap<>();

        for (WorkoutTemplate.Slot slot : template.slots()) {
            List<ExerciseDict> safe = workoutService.findSafeExercises(dto.userId(), slot.muscle(), null);
            if (systemExercisesOnly) {
                safe = safe.stream().filter(exercise -> exercise.getUserId() == null).toList();
            }
            if (safe.isEmpty()) {
                skipped.add("周" + slot.dayOfWeek() + " " + slot.muscle()
                        + "：无可用安全动作（该部位动作全部与该用户 ACTIVE 伤病冲突）");
                continue;
            }
            Set<Long> usedToday = usedByDay.computeIfAbsent(slot.dayOfWeek(), day -> new java.util.LinkedHashSet<>());
            int start = rotation.getOrDefault(slot.muscle(), 0);
            ExerciseDict pick = null;
            for (int i = 0; i < safe.size(); i++) {
                ExerciseDict candidate = safe.get((start + i) % safe.size());
                if (!usedToday.contains(candidate.getId())) {
                    pick = candidate;
                    break;
                }
            }
            if (pick == null) {
                skipped.add("周" + slot.dayOfWeek() + " " + slot.muscle()
                        + "：当日该肌群可用的安全动作已排满（避免同一动作在同一天重复），已跳过第 "
                        + slot.sets() + " 组槽位");
                continue;
            }
            rotation.put(slot.muscle(), start + 1);
            usedToday.add(pick.getId());
            int order = orderByDay.merge(slot.dayOfWeek(), 1, Integer::sum);
            items.add(new WorkoutPlanItemDTO(slot.dayOfWeek(), pick.getId(), slot.sets(), slot.reps(),
                    slot.baseWeightKg(), null, order, "模板生成：" + template.displayName()));
            generated.add("周" + slot.dayOfWeek() + " " + slot.muscle() + " → " + pick.getName()
                    + "（" + slot.sets() + "组×" + slot.reps() + "次，基准 " + slot.baseWeightKg() + "kg）");
        }
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "模板 " + template.code() + " 的所有槽位都因伤病安全过滤而没有可用动作，无法生成课表");
        }

        LocalDate today = LocalDate.now();
        WorkoutPlanVO plan = workoutService.createPlan(new WorkoutPlanCreateDTO(dto.userId(),
                template.displayName() + "（模板一键生成）", template.splitType(),
                today, today.plusWeeks(8), items));
        log.info("一键套用训练模板：user={} code={} 生成 {} 个动作（跳过 {} 个槽位）",
                dto.userId(), template.code(), items.size(), skipped.size());
        return new WorkoutTemplateApplyVO(plan.id(), template.code(), plan.planName(), plan.splitType(),
                items.size(), generated, skipped);
    }

    // ------------------------------------------------------------------
    // 内置模板枚举
    // ------------------------------------------------------------------

    /** 内置经典分化模板。 */
    public enum WorkoutTemplate {

        /** 推拉腿三分化（周一推 / 周三拉 / 周五腿） */
        PPL_3DAY("PPL_3DAY", "推拉腿三分化", "PPL", List.of(
                Slot.of(1, "CHEST", 4, 8), Slot.of(1, "CHEST", 3, 10), Slot.of(1, "SHOULDERS", 3, 10), Slot.of(1, "ARMS", 3, 12),
                Slot.of(3, "BACK", 4, 8), Slot.of(3, "BACK", 3, 10), Slot.of(3, "BACK", 3, 12), Slot.of(3, "ARMS", 3, 12),
                Slot.of(5, "LEGS", 4, 8), Slot.of(5, "LEGS", 3, 10), Slot.of(5, "LEGS", 3, 12), Slot.of(5, "CORE", 3, 15))),

        /** 上下肢四分化（周一/周四上肢，周二/周五下肢） */
        UPPER_LOWER_4DAY("UPPER_LOWER_4DAY", "上下肢四分化", "UPPER_LOWER", List.of(
                Slot.of(1, "CHEST", 4, 8), Slot.of(1, "BACK", 4, 8), Slot.of(1, "SHOULDERS", 3, 10), Slot.of(1, "ARMS", 3, 12),
                Slot.of(2, "LEGS", 4, 8), Slot.of(2, "LEGS", 3, 10), Slot.of(2, "CORE", 3, 15),
                Slot.of(4, "BACK", 4, 8), Slot.of(4, "CHEST", 3, 10), Slot.of(4, "ARMS", 3, 12),
                Slot.of(5, "LEGS", 4, 10), Slot.of(5, "LEGS", 3, 12), Slot.of(5, "CORE", 3, 15)));

        /** 课表槽位：第几天、练哪个肌群、几组、几次、初始基准重量。 */
        public record Slot(int dayOfWeek, String muscle, int sets, int reps, BigDecimal baseWeightKg) {
            static Slot of(int dayOfWeek, String muscle, int sets, int reps) {
                return new Slot(dayOfWeek, muscle, sets, reps,
                        BASE_WEIGHTS.getOrDefault(muscle, BigDecimal.ZERO));
            }
        }

        private final String code;
        private final String displayName;
        private final String splitType;
        private final List<Slot> slots;

        WorkoutTemplate(String code, String displayName, String splitType, List<Slot> slots) {
            this.code = code;
            this.displayName = displayName;
            this.splitType = splitType;
            this.slots = slots;
        }

        public String code() {
            return code;
        }

        public String displayName() {
            return displayName;
        }

        public String splitType() {
            return splitType;
        }

        public List<Slot> slots() {
            return slots;
        }

        public static WorkoutTemplate fromCode(String code) {
            if (code != null) {
                for (WorkoutTemplate template : values()) {
                    if (template.code.equalsIgnoreCase(code.trim())) {
                        return template;
                    }
                }
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "templateCode 取值非法：" + code + "，可选 " + java.util.Arrays.toString(values()));
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private FoodNutritionDict findFoodOrNull(String foodName) {
        String name = foodName == null ? "" : foodName.trim();
        if (name.isEmpty()) {
            return null;
        }
        return foodNutritionDictMapper.selectOne(Wrappers.<FoodNutritionDict>lambdaQuery()
                .eq(FoodNutritionDict::getFoodName, name)
                .last("LIMIT 1"));
    }

    /** 自定义食物每 100g 数值的边界校验（与 NutritionService 保持同一套护栏）。 */
    private void validateCustomPer100g(String foodName, MealItemDTO item) {
        if (item.caloriesPer100g() < 0 || item.caloriesPer100g() > 1000) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "食物[" + foodName + "]每 100g 热量 " + item.caloriesPer100g() + " kcal 超出合理范围（0 ~ 1000）");
        }
        for (Map.Entry<String, BigDecimal> entry : Map.of(
                "proteinPer100g", orZero(item.proteinPer100g()),
                "carbsPer100g", orZero(item.carbsPer100g()),
                "fatPer100g", orZero(item.fatPer100g())).entrySet()) {
            if (entry.getValue().signum() < 0 || entry.getValue().compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                        "食物[" + foodName + "]的 " + entry.getKey() + "=" + entry.getValue()
                                + " 超出合理范围（0 ~ 100 g/100g）");
            }
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 数据来源归一化：只接受白名单，其余一律按 CUSTOM 处理。 */
    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return NutritionService.SOURCE_CUSTOM;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        return NutritionService.SOURCE_AI_ESTIMATED.equals(normalized)
                ? NutritionService.SOURCE_AI_ESTIMATED
                : NutritionService.SOURCE_CUSTOM;
    }

    private List<MealItemDTO> readItems(String itemsJson) {
        try {
            return objectMapper.readValue(itemsJson, new TypeReference<List<MealItemDTO>>() {
            });
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "套餐模板明细解析失败：" + e.getMessage(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "套餐模板明细序列化失败：" + e.getMessage(), e);
        }
    }

    private String requireInSet(String field, String value, Set<String> allowed) {
        String normalized = value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    field + " 取值非法：" + value + "，允许的值为 " + allowed);
        }
        return normalized;
    }

    private static int roundHalfUp(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal multiply(BigDecimal value, BigDecimal ratio) {
        return value == null ? BigDecimal.ZERO : value.multiply(ratio);
    }

    /** 供其它模块复用的模板代码清单。 */
    public static List<Map<String, String>> availableWorkoutTemplates() {
        List<Map<String, String>> list = new ArrayList<>();
        for (WorkoutTemplate template : WorkoutTemplate.values()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", template.code());
            item.put("name", template.displayName());
            item.put("splitType", template.splitType());
            item.put("slotCount", String.valueOf(template.slots().size()));
            list.add(item);
        }
        return list;
    }
}