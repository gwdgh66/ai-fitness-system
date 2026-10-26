package com.fitness.ai.nutrition.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.nutrition.dto.DietLogCreateDTO;
import com.fitness.ai.nutrition.dto.MacroTargetGenerateDTO;
import com.fitness.ai.nutrition.entity.DailyMacroTarget;
import com.fitness.ai.nutrition.entity.DietLog;
import com.fitness.ai.nutrition.entity.FoodNutritionDict;
import com.fitness.ai.nutrition.mapper.DailyMacroTargetMapper;
import com.fitness.ai.nutrition.mapper.DietLogMapper;
import com.fitness.ai.nutrition.mapper.FoodNutritionDictMapper;
import com.fitness.ai.nutrition.vo.DailyNutritionSummaryVO;
import com.fitness.ai.profile.entity.UserProfile;
import com.fitness.ai.profile.mapper.UserProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 饮食与热量账单服务：确定性营养目标计算 + 热量底线护栏 + 饮食热量自动换算。
 *
 * <p>计算规则（全部硬编码在 Java 中，不让模型估算）：</p>
 * <ol>
 *   <li>目标热量 = TDEE + 目标修正（MUSCLE_GAIN +300 / FAT_LOSS -400 / STRENGTH +100）</li>
 *   <li>热量底线 = max(1000, round(BMR × 0.8))，低于底线直接拒绝</li>
 *   <li>蛋白质按体重系数（MUSCLE_GAIN 2.0 / FAT_LOSS 2.2 / STRENGTH 1.8 g/kg）</li>
 *   <li>脂肪按热量占比（HIGH_CARB 20% / MEDIUM_CARB 25% / LOW_CARB 35%），碳水吃剩余热量</li>
 * </ol>
 */
@Slf4j
@Service
public class NutritionService {

    /** 目标热量修正（kcal） */
    private static final Map<String, Integer> GOAL_CALORIE_DELTA =
            Map.of("MUSCLE_GAIN", 300, "FAT_LOSS", -400, "STRENGTH", 100);
    /** 蛋白质系数（g / kg 体重） */
    private static final Map<String, BigDecimal> PROTEIN_FACTOR_G_PER_KG =
            Map.of("MUSCLE_GAIN", new BigDecimal("2.0"),
                    "FAT_LOSS", new BigDecimal("2.2"),
                    "STRENGTH", new BigDecimal("1.8"));
    /** 脂肪供能占比（随碳水日型调整） */
    private static final Map<String, BigDecimal> FAT_CALORIE_RATIO =
            Map.of("HIGH_CARB", new BigDecimal("0.20"),
                    "MEDIUM_CARB", new BigDecimal("0.25"),
                    "LOW_CARB", new BigDecimal("0.35"));

    /** 单日目标热量绝对底线（kcal） */
    public static final int ABSOLUTE_MIN_CALORIES = 1000;
    /** 单日目标热量相对底线：BMR × 0.8 */
    public static final BigDecimal BMR_FLOOR_RATIO = new BigDecimal("0.8");

    private static final BigDecimal KCAL_PER_G_PROTEIN = new BigDecimal("4");
    private static final BigDecimal KCAL_PER_G_CARB = new BigDecimal("4");
    private static final BigDecimal KCAL_PER_G_FAT = new BigDecimal("9");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final Set<String> DAY_TYPES = Set.of("HIGH_CARB", "MEDIUM_CARB", "LOW_CARB");
    private static final Set<String> MEAL_TYPES = Set.of("BREAKFAST", "LUNCH", "DINNER", "SNACK");

    private final UserProfileMapper userProfileMapper;
    private final FoodNutritionDictMapper foodNutritionDictMapper;
    private final DailyMacroTargetMapper dailyMacroTargetMapper;
    private final DietLogMapper dietLogMapper;

    public NutritionService(UserProfileMapper userProfileMapper,
                            FoodNutritionDictMapper foodNutritionDictMapper,
                            DailyMacroTargetMapper dailyMacroTargetMapper,
                            DietLogMapper dietLogMapper) {
        this.userProfileMapper = userProfileMapper;
        this.foodNutritionDictMapper = foodNutritionDictMapper;
        this.dailyMacroTargetMapper = dailyMacroTargetMapper;
        this.dietLogMapper = dietLogMapper;
    }

    // ------------------------------------------------------------------
    // 食物字典
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<FoodNutritionDict> searchFoods(String keyword) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        return foodNutritionDictMapper.selectList(Wrappers.<FoodNutritionDict>lambdaQuery()
                .like(hasKeyword, FoodNutritionDict::getFoodName, hasKeyword ? keyword.trim() : null)
                .orderByAsc(FoodNutritionDict::getCategory)
                .orderByAsc(FoodNutritionDict::getId));
    }

    // ------------------------------------------------------------------
    // 每日营养目标
    // ------------------------------------------------------------------

    /** 生成或更新某日营养素目标（同一 user_id + target_date 唯一）。 */
    @Transactional
    public DailyMacroTarget generateTarget(MacroTargetGenerateDTO dto) {
        UserProfile profile = requireProfile(dto.userId());
        String dayType = requireInSet("dayType", dto.dayType(), DAY_TYPES);

        if (profile.getBmr() == null || profile.getTdee() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "用户 " + dto.userId() + " 的档案缺少 bmr/tdee，无法计算营养目标：请先调用 "
                            + "PUT /api/profile/" + dto.userId() + " 补全身高、体重、年龄、性别与活动系数");
        }
        if (profile.getWeightKg() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "用户 " + dto.userId() + " 的档案缺少体重，无法按体重系数计算蛋白质目标");
        }
        String goal = profile.getFitnessGoal();
        if (goal == null || !GOAL_CALORIE_DELTA.containsKey(goal)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "用户 " + dto.userId() + " 的档案缺少合法的 fitnessGoal（MUSCLE_GAIN / FAT_LOSS / STRENGTH）");
        }

        int tdee = profile.getTdee();
        int bmr = profile.getBmr();
        int targetCalories = tdee + GOAL_CALORIE_DELTA.get(goal);

        // ---------------- 热量底线护栏 ----------------
        int calorieFloor = Math.max(ABSOLUTE_MIN_CALORIES,
                roundHalfUp(BigDecimal.valueOf(bmr).multiply(BMR_FLOOR_RATIO)));
        if (targetCalories < calorieFloor) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "热量底线护栏拦截：" + goal + " 目标下算出的单日目标热量为 " + targetCalories
                            + " kcal，低于安全底线 " + calorieFloor + " kcal（= max(" + ABSOLUTE_MIN_CALORIES
                            + ", BMR " + bmr + " × " + BMR_FLOOR_RATIO + ")）。请提高目标热量、调整健身目标，"
                            + "或先完善体测档案（当前 TDEE=" + tdee + "，目标修正="
                            + GOAL_CALORIE_DELTA.get(goal) + " kcal）。");
        }

        BigDecimal caloriesBd = BigDecimal.valueOf(targetCalories);
        int targetProteinG = roundHalfUp(profile.getWeightKg().multiply(PROTEIN_FACTOR_G_PER_KG.get(goal)));
        int targetFatG = roundHalfUp(caloriesBd.multiply(FAT_CALORIE_RATIO.get(dayType))
                .divide(KCAL_PER_G_FAT, 10, RoundingMode.HALF_UP));
        BigDecimal carbsCalories = caloriesBd
                .subtract(KCAL_PER_G_PROTEIN.multiply(BigDecimal.valueOf(targetProteinG)))
                .subtract(KCAL_PER_G_FAT.multiply(BigDecimal.valueOf(targetFatG)));
        int targetCarbsG = roundHalfUp(carbsCalories.divide(KCAL_PER_G_CARB, 10, RoundingMode.HALF_UP));
        if (targetCarbsG < 0) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "营养素分配失败：目标热量 " + targetCalories + " kcal 无法同时满足蛋白质 " + targetProteinG
                            + " g 与脂肪 " + targetFatG + " g（合计已占 "
                            + KCAL_PER_G_PROTEIN.multiply(BigDecimal.valueOf(targetProteinG))
                            .add(KCAL_PER_G_FAT.multiply(BigDecimal.valueOf(targetFatG))) + " kcal），"
                            + "碳水计算值为负，请降低蛋白质系数或脂肪占比。");
        }

        DailyMacroTarget existing = dailyMacroTargetMapper.selectOne(Wrappers.<DailyMacroTarget>lambdaQuery()
                .eq(DailyMacroTarget::getUserId, dto.userId())
                .eq(DailyMacroTarget::getTargetDate, dto.targetDate())
                .last("LIMIT 1"));

        DailyMacroTarget target = existing == null ? new DailyMacroTarget() : existing;
        target.setUserId(dto.userId());
        target.setTargetDate(dto.targetDate());
        target.setDayType(dayType);
        target.setTargetCalories(targetCalories);
        target.setTargetProteinG(targetProteinG);
        target.setTargetCarbsG(targetCarbsG);
        target.setTargetFatG(targetFatG);
        if (existing == null) {
            dailyMacroTargetMapper.insert(target);
        } else {
            dailyMacroTargetMapper.updateById(target);
        }
        log.info("用户 {} {} 的 {} 目标已生成：{} kcal / P{}g / C{}g / F{}g", dto.userId(), dto.targetDate(),
                dayType, targetCalories, targetProteinG, targetCarbsG, targetFatG);
        return dailyMacroTargetMapper.selectById(target.getId());
    }

    // ------------------------------------------------------------------
    // 饮食打卡
    // ------------------------------------------------------------------

    /** 数据来源：本地营养字典命中（可信度最高）。 */
    public static final String SOURCE_DICTIONARY = "DICTIONARY";
    /** 数据来源：用户自填每 100g 营养。 */
    public static final String SOURCE_CUSTOM = "CUSTOM";
    /** 数据来源：AI 估算后经用户确认保存。 */
    public static final String SOURCE_AI_ESTIMATED = "AI_ESTIMATED";
    private static final Set<String> DIET_SOURCES = Set.of(SOURCE_DICTIONARY, SOURCE_CUSTOM, SOURCE_AI_ESTIMATED);
    /** 自定义食物每 100g 的合法性上限（防手滑输入离谱数值）。 */
    private static final int MAX_CALORIES_PER_100G = 1000;
    private static final BigDecimal MAX_MACRO_PER_100G = new BigDecimal("100");

    /** 记录饮食流水：优先按本地字典精确折算，字典未命中时按「每 100g 数值」或「本条总量」折算。 */
    @Transactional
    public DietLog addDietLog(DietLogCreateDTO dto) {
        return addDietLog(dto, null);
    }

    /**
     * 记录饮食流水（可显式指定数据来源）。
     *
     * @param explicitSource DICTIONARY / CUSTOM / AI_ESTIMATED；为空时按解析结果自动推断。
     *                       字典命中时该参数会被忽略，一律标记为 DICTIONARY（保证不会把字典值标成估算值）。
     */
    @Transactional
    public DietLog addDietLog(DietLogCreateDTO dto, String explicitSource) {
        requireProfile(dto.userId());
        String mealType = requireInSet("mealType", dto.mealType(), MEAL_TYPES);
        if (dto.weightG() == null || dto.weightG().signum() <= 0
                || dto.weightG().compareTo(new BigDecimal("10000")) > 0) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "weightG=" + dto.weightG() + " 非法，必须是 0 ~ 10000 克之间的正数");
        }

        String foodName = dto.foodName().trim();
        // 来源优先级：方法参数（模板打卡时显式传入）> 请求体里的 source > 按解析结果推断
        String requestedSource = explicitSource != null && !explicitSource.isBlank()
                ? explicitSource
                : dto.source();
        FoodNutritionDict food = foodNutritionDictMapper.selectOne(Wrappers.<FoodNutritionDict>lambdaQuery()
                .eq(FoodNutritionDict::getFoodName, foodName)
                .last("LIMIT 1"));

        DietLog entity = new DietLog();
        entity.setUserId(dto.userId());
        entity.setLogDate(dto.logDate());
        entity.setMealType(mealType);
        entity.setFoodName(foodName);
        entity.setWeightG(dto.weightG());

        if (food != null) {
            BigDecimal ratio = dto.weightG().divide(HUNDRED, 10, RoundingMode.HALF_UP);
            entity.setCalories(roundHalfUp(
                    BigDecimal.valueOf(food.getCaloriesPer100g()).multiply(ratio)));
            entity.setProteinG(scale2(multiply(food.getProteinPer100g(), ratio)));
            entity.setCarbsG(scale2(multiply(food.getCarbsPer100g(), ratio)));
            entity.setFatG(scale2(multiply(food.getFatPer100g(), ratio)));
            entity.setSource(SOURCE_DICTIONARY);
            log.info("饮食打卡命中本地字典[{}]，按 {} g 折算：{} kcal / P{}g / C{}g / F{}g", foodName,
                    dto.weightG(), entity.getCalories(), entity.getProteinG(), entity.getCarbsG(), entity.getFatG());
        } else if (dto.caloriesPer100g() != null) {
            // 自定义食物：调用方只给「每 100g」数值，折算（乘除）仍由 Java 完成
            validatePer100g(dto);
            BigDecimal ratio = dto.weightG().divide(HUNDRED, 10, RoundingMode.HALF_UP);
            entity.setCalories(roundHalfUp(
                    BigDecimal.valueOf(dto.caloriesPer100g()).multiply(ratio)));
            entity.setProteinG(scale2(multiply(dto.proteinPer100g(), ratio)));
            entity.setCarbsG(scale2(multiply(dto.carbsPer100g(), ratio)));
            entity.setFatG(scale2(multiply(dto.fatPer100g(), ratio)));
            entity.setSource(normalizeSource(requestedSource, SOURCE_CUSTOM));
            log.info("饮食打卡未命中字典[{}]，按自定义每 100g（{} kcal）折算 {} g：{} kcal / P{}g / C{}g / F{}g，来源={}",
                    foodName, dto.caloriesPer100g(), dto.weightG(), entity.getCalories(),
                    entity.getProteinG(), entity.getCarbsG(), entity.getFatG(), entity.getSource());
        } else if (dto.calories() != null) {
            entity.setCalories(dto.calories());
            entity.setProteinG(scale2(dto.proteinG()));
            entity.setCarbsG(scale2(dto.carbsG()));
            entity.setFatG(scale2(dto.fatG()));
            entity.setSource(normalizeSource(requestedSource, SOURCE_CUSTOM));
            log.info("饮食打卡未命中本地字典[{}]，使用调用方给出的本条总量：{} kcal，来源={}",
                    foodName, dto.calories(), entity.getSource());
        } else {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_FAILED,
                    "食物[" + foodName + "]不在本地营养字典中，且未提供营养数值。三种解决方式：① 改用字典中已有的食物名"
                            + "（可调用 GET /api/nutrition/foods?keyword= 检索）；② 提交 caloriesPer100g 等「每 100g 数值」，"
                            + "由后端按克重折算（自定义食物推荐这种方式）；③ 直接提交 calories 等本条总量。");
        }

        dietLogMapper.insert(entity);
        return entity;
    }

    /** 自定义食物每 100g 数值的边界校验。 */
    private void validatePer100g(DietLogCreateDTO dto) {
        if (dto.caloriesPer100g() < 0 || dto.caloriesPer100g() > MAX_CALORIES_PER_100G) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "每 100g 热量 " + dto.caloriesPer100g() + " kcal 超出合理范围（0 ~ "
                            + MAX_CALORIES_PER_100G + "），请检查输入");
        }
        for (Map.Entry<String, BigDecimal> entry : Map.of(
                "proteinPer100g", orZero(dto.proteinPer100g()),
                "carbsPer100g", orZero(dto.carbsPer100g()),
                "fatPer100g", orZero(dto.fatPer100g())).entrySet()) {
            BigDecimal value = entry.getValue();
            if (value.signum() < 0 || value.compareTo(MAX_MACRO_PER_100G) > 0) {
                throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                        entry.getKey() + "=" + value + " 超出合理范围（0 ~ 100 g/100g），请检查输入");
            }
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String normalizeSource(String source, String fallback) {
        if (source == null || source.isBlank()) {
            return fallback;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        return DIET_SOURCES.contains(normalized) ? normalized : fallback;
    }

    // ------------------------------------------------------------------
    // 账单汇总
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DailyNutritionSummaryVO summary(Long userId, LocalDate date) {
        requireProfile(userId);
        DailyMacroTarget target = dailyMacroTargetMapper.selectOne(Wrappers.<DailyMacroTarget>lambdaQuery()
                .eq(DailyMacroTarget::getUserId, userId)
                .eq(DailyMacroTarget::getTargetDate, date)
                .last("LIMIT 1"));
        List<DietLog> logs = dietLogMapper.selectList(Wrappers.<DietLog>lambdaQuery()
                .eq(DietLog::getUserId, userId)
                .eq(DietLog::getLogDate, date)
                .orderByAsc(DietLog::getId));

        int consumedCalories = logs.stream()
                .map(DietLog::getCalories)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        BigDecimal consumedProtein = sum(logs.stream().map(DietLog::getProteinG).toList());
        BigDecimal consumedCarbs = sum(logs.stream().map(DietLog::getCarbsG).toList());
        BigDecimal consumedFat = sum(logs.stream().map(DietLog::getFatG).toList());

        Integer remainingCalories = null;
        BigDecimal remainingProtein = null;
        BigDecimal remainingCarbs = null;
        BigDecimal remainingFat = null;
        if (target != null) {
            remainingCalories = target.getTargetCalories() - consumedCalories;
            remainingProtein = subtract(target.getTargetProteinG(), consumedProtein);
            remainingCarbs = subtract(target.getTargetCarbsG(), consumedCarbs);
            remainingFat = subtract(target.getTargetFatG(), consumedFat);
        }

        return new DailyNutritionSummaryVO(userId, date,
                target == null ? null : target.getDayType(),
                target == null ? null : target.getTargetCalories(),
                target == null ? null : target.getTargetProteinG(),
                target == null ? null : target.getTargetCarbsG(),
                target == null ? null : target.getTargetFatG(),
                consumedCalories, consumedProtein, consumedCarbs, consumedFat,
                remainingCalories, remainingProtein, remainingCarbs, remainingFat,
                logs);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private UserProfile requireProfile(Long userId) {
        UserProfile profile = userId == null ? null : userProfileMapper.selectById(userId);
        if (profile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "用户档案不存在：userId=" + userId + "，请先创建该用户的体测档案");
        }
        return profile;
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
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal multiply(BigDecimal value, BigDecimal ratio) {
        return value == null ? null : value.multiply(ratio);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal subtract(Integer target, BigDecimal consumed) {
        BigDecimal targetBd = target == null ? BigDecimal.ZERO : BigDecimal.valueOf(target);
        return targetBd.subtract(consumed).setScale(2, RoundingMode.HALF_UP);
    }
}