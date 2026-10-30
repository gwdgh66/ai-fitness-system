package com.fitness.ai.nutrition.controller;

import com.fitness.ai.common.Result;
import com.fitness.ai.nutrition.dto.DietLogCreateDTO;
import com.fitness.ai.nutrition.dto.MacroTargetGenerateDTO;
import com.fitness.ai.nutrition.entity.DailyMacroTarget;
import com.fitness.ai.nutrition.entity.DietLog;
import com.fitness.ai.nutrition.entity.FoodNutritionDict;
import com.fitness.ai.nutrition.service.NutritionService;
import com.fitness.ai.nutrition.vo.DailyNutritionSummaryVO;
import com.fitness.ai.nutrition.vo.DietLogClearVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 饮食与热量账单接口。
 */
@Tag(name = "Nutrition", description = "食物字典、营养目标与饮食打卡账单")
@RestController
@RequestMapping("/api/nutrition")
@Validated
public class NutritionController {

    private final NutritionService nutritionService;

    public NutritionController(NutritionService nutritionService) {
        this.nutritionService = nutritionService;
    }

    @Operation(summary = "查询食物营养字典", description = "keyword 为空时返回全部字典数据")
    @GetMapping("/foods")
    public Result<List<FoodNutritionDict>> foods(@RequestParam(required = false) String keyword) {
        return Result.success(nutritionService.searchFoods(keyword));
    }

    @Operation(summary = "生成或更新某日营养素目标", description = "基于该用户 TDEE 与健身目标计算，含热量底线护栏")
    @PostMapping("/targets")
    public Result<DailyMacroTarget> generateTarget(@Valid @RequestBody MacroTargetGenerateDTO dto) {
        return Result.success(nutritionService.generateTarget(dto));
    }

    @Operation(summary = "新增饮食打卡", description = "优先按本地食物字典精确折算热量与三大营养素")
    @PostMapping("/logs")
    public Result<DietLog> addDietLog(@Valid @RequestBody DietLogCreateDTO dto) {
        return Result.success(nutritionService.addDietLog(dto));
    }

    @Operation(summary = "清空某日饮食流水",
            description = "只删除该用户在该日期的饮食打卡，不影响其它日期与其它用户；date 省略默认今天，便于清除演示/试录数据")
    @DeleteMapping("/logs")
    public Result<DietLogClearVO> clearDietLogs(
            @RequestParam Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(nutritionService.clearDietLogs(userId, date));
    }

    @Operation(summary = "查询每日营养账单汇总", description = "返回目标值、已摄入、剩余缺口与流水明细；date 省略时默认今天")
    @GetMapping("/summary")
    public Result<DailyNutritionSummaryVO> summary(
            @RequestParam Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        // 与 AI 工具 getDailyNutritionSummary 的行为保持一致：不传日期即查今天
        return Result.success(nutritionService.summary(userId, date == null ? LocalDate.now() : date));
    }
}