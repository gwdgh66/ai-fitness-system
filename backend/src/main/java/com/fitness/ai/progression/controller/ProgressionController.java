package com.fitness.ai.progression.controller;

import com.fitness.ai.common.Result;
import com.fitness.ai.progression.service.ProgressionService;
import com.fitness.ai.progression.vo.ProgressionApplyVO;
import com.fitness.ai.progression.vo.ProgressionRecommendationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 确定性渐进超负荷与周期复盘接口（纯 Java 算法，不经过大模型）。
 */
@Tag(name = "Progression", description = "双重渐进超负荷推荐与一键采纳")
@RestController
@RequestMapping("/api/progression")
@Validated
public class ProgressionController {

    private final ProgressionService progressionService;

    public ProgressionController(ProgressionService progressionService) {
        this.progressionService = progressionService;
    }

    @Operation(summary = "获取进阶建议",
            description = "扫描当前激活课表的每个动作，比对最近 2~3 次打卡，给出 OVERLOAD_READY / MAINTAIN / DELOAD_NEEDED")
    @GetMapping("/recommendations/{userId}")
    public Result<List<ProgressionRecommendationVO>> recommendations(@PathVariable Long userId) {
        return Result.success(progressionService.recommend(userId));
    }

    @Operation(summary = "采纳进阶建议", description = "一键把推荐的新重量/次数写回课表明细（会重新执行安全护栏校验）")
    @PostMapping("/apply/{itemId}")
    public Result<ProgressionApplyVO> apply(@PathVariable Long itemId) {
        return Result.success(progressionService.apply(itemId));
    }
}