package com.fitness.ai.catalog.controller;

import com.fitness.ai.catalog.dto.MealTemplateCreateDTO;
import com.fitness.ai.catalog.dto.WorkoutTemplateApplyDTO;
import com.fitness.ai.catalog.entity.MealTemplate;
import com.fitness.ai.catalog.service.CatalogService;
import com.fitness.ai.catalog.vo.MealTemplateApplyVO;
import com.fitness.ai.catalog.vo.WorkoutTemplateApplyVO;
import com.fitness.ai.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 预设模板库与一键打卡中心接口。
 */
@Tag(name = "Catalog", description = "常吃饮食套餐模板 + 经典训练模板一键套用")
@RestController
@RequestMapping("/api/catalog")
@Validated
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Operation(summary = "查询用户的常吃套餐模板")
    @GetMapping("/meal-templates")
    public Result<List<MealTemplate>> listMealTemplates(@RequestParam Long userId) {
        return Result.success(catalogService.listMealTemplates(userId));
    }

    @Operation(summary = "新增常吃套餐模板", description = "自动遍历 items 查食物字典汇总总热量与三大营养素")
    @PostMapping("/meal-templates")
    public Result<MealTemplate> createMealTemplate(@Valid @RequestBody MealTemplateCreateDTO dto) {
        return Result.success(catalogService.createMealTemplate(dto));
    }

    @Operation(summary = "一键套餐打卡", description = "把套餐内所有食物按指定餐次批量写入 diet_log，无需逐个输入")
    @PostMapping("/meal-templates/{templateId}/apply")
    public Result<MealTemplateApplyVO> applyMealTemplate(
            @PathVariable Long templateId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String mealType) {
        return Result.success(catalogService.applyMealTemplate(templateId, date, mealType));
    }

    @Operation(summary = "内置训练模板清单")
    @GetMapping("/workout-templates")
    public Result<List<Map<String, String>>> workoutTemplates() {
        return Result.success(CatalogService.availableWorkoutTemplates());
    }

    @Operation(summary = "一键套用经典训练模板",
            description = "纯 Java 毫秒级排课：自动从动作字典挑选不与该用户 ACTIVE 伤病冲突的代表动作并生成激活周课表")
    @PostMapping("/workout-templates/apply")
    public Result<WorkoutTemplateApplyVO> applyWorkoutTemplate(@Valid @RequestBody WorkoutTemplateApplyDTO dto) {
        return Result.success(catalogService.applyWorkoutTemplate(dto));
    }
}