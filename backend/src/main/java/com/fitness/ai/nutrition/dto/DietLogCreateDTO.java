package com.fitness.ai.nutrition.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 饮食打卡请求。
 *
 * <p>营养数值的三种提交方式（后端按顺序择优，**折算一律由 Java 完成**）：</p>
 * <ol>
 *   <li><b>只给食物名 + 克重</b>：命中本地字典 → 按字典每 100g 数据折算（来源标记 DICTIONARY）；</li>
 *   <li><b>给每 100g 营养</b>（{@code caloriesPer100g} 等）：字典未命中时按克重折算（来源标记 CUSTOM 或 AI_ESTIMATED）；</li>
 *   <li><b>直接给本条总量</b>（{@code calories} 等）：兼容旧调用方 / @Tool 的显式估算路径。</li>
 * </ol>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DietLogCreateDTO(
        Long userId,
        LocalDate logDate,
        String mealType,
        String foodName,
        BigDecimal weightG,
        Integer calories,
        BigDecimal proteinG,
        BigDecimal carbsG,
        BigDecimal fatG,
        /** 自定义食物的每 100g 热量（kcal），非空时优先按此折算 */
        Integer caloriesPer100g,
        BigDecimal proteinPer100g,
        BigDecimal carbsPer100g,
        BigDecimal fatPer100g,
        /** 数据来源：DICTIONARY / CUSTOM / AI_ESTIMATED；为空时由服务端推断 */
        String source
) {

    /**
     * 显式声明规范构造器并标注 {@code @JsonCreator}：本 record 还有「只给本条总量」的 9 参数兼容构造器，
     * 不显式指定时 Jackson 可能选中它，导致每 100g 字段反序列化为 null。
     */
    @JsonCreator
    public DietLogCreateDTO(@JsonProperty("userId") Long userId,
                            @JsonProperty("logDate") LocalDate logDate,
                            @JsonProperty("mealType") String mealType,
                            @JsonProperty("foodName") String foodName,
                            @JsonProperty("weightG") BigDecimal weightG,
                            @JsonProperty("calories") Integer calories,
                            @JsonProperty("proteinG") BigDecimal proteinG,
                            @JsonProperty("carbsG") BigDecimal carbsG,
                            @JsonProperty("fatG") BigDecimal fatG,
                            @JsonProperty("caloriesPer100g") Integer caloriesPer100g,
                            @JsonProperty("proteinPer100g") BigDecimal proteinPer100g,
                            @JsonProperty("carbsPer100g") BigDecimal carbsPer100g,
                            @JsonProperty("fatPer100g") BigDecimal fatPer100g,
                            @JsonProperty("source") String source) {
        this.userId = userId;
        this.logDate = logDate;
        this.mealType = mealType;
        this.foodName = foodName;
        this.weightG = weightG;
        this.calories = calories;
        this.proteinG = proteinG;
        this.carbsG = carbsG;
        this.fatG = fatG;
        this.caloriesPer100g = caloriesPer100g;
        this.proteinPer100g = proteinPer100g;
        this.carbsPer100g = carbsPer100g;
        this.fatPer100g = fatPer100g;
        this.source = source;
    }

    /** 兼容简写：只提交本条已折算好的总量（命中字典时以字典为准）。 */
    public DietLogCreateDTO(Long userId, LocalDate logDate, String mealType, String foodName,
                            BigDecimal weightG, Integer calories, BigDecimal proteinG,
                            BigDecimal carbsG, BigDecimal fatG) {
        this(userId, logDate, mealType, foodName, weightG, calories, proteinG, carbsG, fatG,
                null, null, null, null, null);
    }
}