package com.fitness.ai.catalog.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 套餐内的一条食物明细。
 *
 * <p>为了让「自定义食物」也能进套餐并顺利打卡，明细里除了食物名与克重，还可以携带
 * <b>每 100g 营养</b>与<b>数据来源</b>：创建套餐时后端会把折算结果一起写进 items_json，
 * 这样一键打卡时即便食物不在字典中也能直接落库（不再依赖字典二次查询）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MealItemDTO(
        String foodName,
        BigDecimal weightG,
        Integer caloriesPer100g,
        BigDecimal proteinPer100g,
        BigDecimal carbsPer100g,
        BigDecimal fatPer100g,
        /** DICTIONARY / CUSTOM / AI_ESTIMATED */
        String source
) {

    /**
     * 显式声明规范构造器并标注 {@code @JsonCreator}。
     *
     * <p>本 record 同时提供「只给食物名 + 克重」的兼容构造器；若不显式指定 creator，
     * Jackson 可能选中那个 2 参数构造器，导致每 100g 字段全部反序列化为 null —— 表现为
     * 「明明传了 caloriesPer100g，后端却报未提供营养数值」。</p>
     */
    @JsonCreator
    public MealItemDTO(@JsonProperty("foodName") String foodName,
                       @JsonProperty("weightG") BigDecimal weightG,
                       @JsonProperty("caloriesPer100g") Integer caloriesPer100g,
                       @JsonProperty("proteinPer100g") BigDecimal proteinPer100g,
                       @JsonProperty("carbsPer100g") BigDecimal carbsPer100g,
                       @JsonProperty("fatPer100g") BigDecimal fatPer100g,
                       @JsonProperty("source") String source) {
        this.foodName = foodName;
        this.weightG = weightG;
        this.caloriesPer100g = caloriesPer100g;
        this.proteinPer100g = proteinPer100g;
        this.carbsPer100g = carbsPer100g;
        this.fatPer100g = fatPer100g;
        this.source = source;
    }

    /** 兼容简写：只给食物名与克重（营养由字典解析）。 */
    public MealItemDTO(String foodName, BigDecimal weightG) {
        this(foodName, weightG, null, null, null, null, null);
    }
}