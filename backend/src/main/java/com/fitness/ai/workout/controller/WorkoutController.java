package com.fitness.ai.workout.controller;

import com.fitness.ai.common.Result;
import com.fitness.ai.workout.dto.CustomExerciseCreateDTO;
import com.fitness.ai.workout.dto.WorkoutLogCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanItemDTO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.entity.WorkoutLog;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.service.WorkoutService;
import com.fitness.ai.workout.vo.CustomExerciseDeleteVO;
import com.fitness.ai.workout.vo.PlanItemDeleteVO;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 训练课表与打卡接口。
 */
@Tag(name = "Workout", description = "训练课表、安全动作过滤与打卡流水")
@RestController
@RequestMapping("/api/workout")
@Validated
public class WorkoutController {

    private final WorkoutService workoutService;

    public WorkoutController(WorkoutService workoutService) {
        this.workoutService = workoutService;
    }

    @Operation(summary = "查询安全动作列表",
            description = "在 Java 中自动过滤掉与该用户当前 ACTIVE 伤病冲突的动作")
    @GetMapping("/exercises/safe")
    public Result<List<ExerciseDict>> safeExercises(@RequestParam Long userId,
                                                    @RequestParam(required = false) String targetMuscle,
                                                    @RequestParam(required = false) String equipment) {
        return Result.success(workoutService.findSafeExercises(userId, targetMuscle, equipment));
    }

    @Operation(summary = "查询动作字典全量（不做伤病过滤）",
            description = "系统内置动作 + 该用户的自定义动作；仅供前端「换动作 / 自定义排课」弹窗的"
                    + "“显示全部动作（含禁忌）”开关使用；落库时仍会执行伤病冲突与训练量护栏校验")
    @GetMapping("/exercises")
    public Result<List<ExerciseDict>> allExercises(@RequestParam Long userId,
                                                   @RequestParam(required = false) String targetMuscle,
                                                   @RequestParam(required = false) String equipment) {
        return Result.success(workoutService.findAllExercises(userId, targetMuscle, equipment));
    }

    @Operation(summary = "新建自定义动作",
            description = "自己起名字建动作。名称全局唯一；禁忌受伤部位只接受系统已知的 5 个部位"
                    + "（留空表示对所有伤病都安全，护栏将无法拦截该动作）")
    @PostMapping("/exercises")
    public Result<ExerciseDict> createCustomExercise(@Valid @RequestBody CustomExerciseCreateDTO dto) {
        return Result.success(workoutService.createCustomExercise(dto));
    }

    @Operation(summary = "删除自定义动作",
            description = "系统内置动作不可删；仅能删自己的；已被课表明细或打卡记录引用时不可删")
    @DeleteMapping("/exercises/{exerciseId}")
    public Result<CustomExerciseDeleteVO> deleteCustomExercise(@PathVariable Long exerciseId,
                                                               @RequestParam Long userId) {
        return Result.success(workoutService.deleteCustomExercise(exerciseId, userId));
    }

    @Operation(summary = "创建周训练计划", description = "若该用户已有 ACTIVE 计划，先将其置为 ARCHIVED")
    @PostMapping("/plans")
    public Result<WorkoutPlanVO> createPlan(@Valid @RequestBody WorkoutPlanCreateDTO dto) {
        return Result.success(workoutService.createPlan(dto));
    }

    @Operation(summary = "查询当前激活的周课表")
    @GetMapping("/plans/active")
    public Result<WorkoutPlanVO> activePlan(@RequestParam Long userId) {
        return Result.success(workoutService.getActivePlan(userId));
    }

    @Operation(summary = "修改课表中的某条明细", description = "会重新触发伤病冲突与训练量护栏校验")
    @PutMapping("/plans/items/{itemId}")
    public Result<WorkoutPlanItem> updatePlanItem(@PathVariable Long itemId,
                                                  @Valid @RequestBody WorkoutPlanItemDTO dto) {
        return Result.success(workoutService.updatePlanItem(itemId, dto));
    }

    @Operation(summary = "删除课表中的某条明细",
            description = "只允许删除 ACTIVE 计划里的动作；删除后自动重排当日动作顺序，且已产生的训练打卡记录一律保留（历史不可篡改）")
    @DeleteMapping("/plans/items/{itemId}")
    public Result<PlanItemDeleteVO> deletePlanItem(@PathVariable Long itemId) {
        return Result.success(workoutService.deletePlanItem(itemId));
    }

    @Operation(summary = "记录训练打卡", description = "自动按 Epley 公式计算 estimated1rm 与 totalVolume 后落库")
    @PostMapping("/logs")
    public Result<WorkoutLog> logWorkout(@Valid @RequestBody WorkoutLogCreateDTO dto) {
        return Result.success(workoutService.logWorkout(dto));
    }

    @Operation(summary = "查询最近若干天的训练打卡流水")
    @GetMapping("/logs")
    public Result<List<WorkoutLog>> logs(@RequestParam Long userId,
                                         @RequestParam(defaultValue = "7") int days) {
        return Result.success(workoutService.findLogs(userId, days));
    }
}