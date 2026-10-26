package com.fitness.ai.dashboard.controller;

import com.fitness.ai.common.Result;
import com.fitness.ai.dashboard.service.DashboardService;
import com.fitness.ai.dashboard.vo.DashboardOverviewVO;
import com.fitness.ai.dashboard.vo.DemoSeedResultVO;
import com.fitness.ai.dashboard.vo.MuscleVolumeVO;
import com.fitness.ai.dashboard.vo.NutritionTrendVO;
import com.fitness.ai.dashboard.vo.StrengthTrendVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据可视化看板接口（纯 Java 聚合，供前端 ECharts 使用）。
 */
@Tag(name = "Dashboard", description = "核心指标卡、力量走势、肌群容量、营养趋势与演示数据播种")
@RestController
@RequestMapping("/api/dashboard")
@Validated
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "顶部核心指标卡",
            description = "当前体重/BMR/TDEE、今日已摄入 vs 目标、本周完成训练次数 vs 计划天数、活跃伤病数、近 30 天总容量")
    @GetMapping("/overview/{userId}")
    public Result<DashboardOverviewVO> overview(@PathVariable Long userId) {
        return Result.success(dashboardService.overview(userId));
    }

    @Operation(summary = "力量走势曲线", description = "按日期聚合三大项（深蹲/卧推/硬拉）的 estimated_1rm")
    @GetMapping("/charts/strength-trend/{userId}")
    public Result<StrengthTrendVO> strengthTrend(@PathVariable Long userId,
                                                 @RequestParam(required = false) Integer days) {
        return Result.success(dashboardService.strengthTrend(userId, days));
    }

    @Operation(summary = "肌群容量分布", description = "按目标肌群统计近 N 天总容量与总组数，用于雷达图/饼图")
    @GetMapping("/charts/muscle-volume/{userId}")
    public Result<MuscleVolumeVO> muscleVolume(@PathVariable Long userId,
                                               @RequestParam(required = false) Integer days) {
        return Result.success(dashboardService.muscleVolume(userId, days));
    }

    @Operation(summary = "营养趋势序列", description = "近 N 天每日实际摄入 vs 目标对比，用于堆叠柱状图")
    @GetMapping("/charts/nutrition-trend/{userId}")
    public Result<NutritionTrendVO> nutritionTrend(@PathVariable Long userId,
                                                   @RequestParam(required = false) Integer days) {
        return Result.success(dashboardService.nutritionTrend(userId, days));
    }

    @Operation(summary = "14 天演示数据一键播种",
            description = "幂等：先清理该用户旧业务流水，再生成兼容其当前伤病的激活周课表、14 天训练打卡与 7 天饮食账单")
    @PostMapping("/demo-seed/{userId}")
    public Result<DemoSeedResultVO> demoSeed(@PathVariable Long userId) {
        return Result.success(dashboardService.demoSeed(userId));
    }
}