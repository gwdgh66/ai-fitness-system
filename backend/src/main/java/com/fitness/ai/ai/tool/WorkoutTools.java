package com.fitness.ai.ai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.workout.dto.WorkoutLogCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanItemDTO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.entity.WorkoutLog;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.service.WorkoutService;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
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
 * 训练课表、安全动作与打卡工具集。
 *
 * <p>参数里的日期统一用字符串（yyyy-MM-dd）接收、小数统一用 Double 接收，在工具内部再转成
 * LocalDate / BigDecimal：这样不依赖 LangChain4j 的工具参数转换器对 Java 时间与高精度类型的支持，
 * 精度换算留在 Java 侧显式完成。</p>
 */
@Slf4j
@Component("workoutTools")
public class WorkoutTools {

    private final WorkoutService workoutService;
    private final ObjectMapper objectMapper;

    public WorkoutTools(WorkoutService workoutService, ObjectMapper objectMapper) {
        this.workoutService = workoutService;
        this.objectMapper = objectMapper;
    }

    @Tool("""
            根据目标肌群与器械条件查询动作字典，返回的动作列表已自动过滤掉用户当前 ACTIVE 伤病的禁忌动作，
            因此是本用户「可以放心排进课表」的动作白名单。
            当用户要求换动作、制定训练计划，或者你不确定某个动作的 exerciseId 时，必须先调用本工具查询。
            返回 JSON 数组，每项含：exerciseId、name、targetMuscle、equipment、difficulty、avoidInjuryParts。""")
    public String querySafeExercises(
            @ToolMemoryId Long userId,
            @P(value = "目标肌群，可选：CHEST（胸）、BACK（背）、LEGS（腿）、SHOULDERS（肩）、ARMS（手臂）、CORE（核心）；不确定就留空返回全部", required = false)
            String targetMuscle,
            @P(value = "器械条件，可选：BARBELL、DUMBBELL、MACHINE、CABLE、BODYWEIGHT；不确定就留空", required = false)
            String equipment) {
        log.info("[Tool] querySafeExercises userId={} targetMuscle={} equipment={}", userId, targetMuscle, equipment);
        try {
            List<ExerciseDict> safe = workoutService.findSafeExercises(userId, targetMuscle, equipment);
            List<Map<String, Object>> items = new ArrayList<>();
            for (ExerciseDict e : safe) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("exerciseId", e.getId());
                item.put("name", e.getName());
                item.put("targetMuscle", e.getTargetMuscle());
                item.put("equipment", e.getEquipment());
                item.put("difficulty", e.getDifficulty());
                item.put("avoidInjuryParts", e.getAvoidInjuryParts());
                items.add(item);
            }
            return json(items);
        } catch (BusinessException e) {
            return error(e, "请确认 targetMuscle / equipment 使用了枚举值（或留空查询全部）。");
        }
    }

    @Tool("""
            查询用户当前处于 ACTIVE 状态的周训练课表（含每日动作明细），用于查看现有安排、拿到课表明细的 itemId。
            返回：{"planId":..,"planName":..,"splitType":..,"items":[{"itemId":..,"dayOfWeek":1~7,"exerciseId":..,
            "exerciseName":"..","targetSets":..,"targetReps":..,"targetWeightKg":..,"targetRpe":..,"notes":".."}]}。
            dayOfWeek 中 1 代表周一、7 代表周日。""")
    public String getActiveWorkoutPlan(@ToolMemoryId Long userId) {
        log.info("[Tool] getActiveWorkoutPlan userId={}", userId);
        try {
            WorkoutPlanVO plan = workoutService.getActivePlan(userId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("planId", plan.id());
            result.put("planName", plan.planName());
            result.put("splitType", plan.splitType());
            result.put("startDate", String.valueOf(plan.startDate()));
            result.put("endDate", String.valueOf(plan.endDate()));
            result.put("items", plan.items().stream().map(this::toItemMap).toList());
            return json(result);
        } catch (BusinessException e) {
            // 读类工具遇到「没有计划」不是护栏拦截，不该让模型反复重试，返回结构化空态并给出指引
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("planId", null);
            empty.put("items", List.of());
            empty.put("message", "用户当前没有 ACTIVE 状态的训练计划（" + e.getMessage() + "）");
            return json(empty);
        }
    }

    @Tool("""
            修改或替换用户周课表中的某一个动作明细（按 itemId 定位）。只传需要改的字段，其余留空表示保持原值。
            本工具会重新执行伤病冲突与训练量护栏校验：若返回以 ERROR: 开头的内容，说明被护栏拦截，
            请根据提示修正参数（例如先用 querySafeExercises 换成不冲突的动作）后再次调用本工具。
            返回修改后的明细：{"itemId":..,"dayOfWeek":..,"exerciseName":"..","targetSets":..,"targetReps":..,"targetWeightKg":..}。""")
    public String updateWorkoutPlanItem(
            @ToolMemoryId Long userId,
            @P("要修改的课表明细 ID（itemId），可从 getActiveWorkoutPlan 获取")
            Long itemId,
            @P(value = "替换成的新动作 ID（exerciseId），须来自 querySafeExercises 的返回值；不换动作就留空", required = false)
            Long exerciseId,
            @P(value = "目标组数，1~15；不改就留空", required = false) Integer targetSets,
            @P(value = "每组目标次数，1~100；不改就留空", required = false) Integer targetReps,
            @P(value = "目标重量（kg）；不改就留空", required = false) Double targetWeightKg,
            @P(value = "目标 RPE，1.0~10.0；不改就留空", required = false) Double targetRpe,
            @P(value = "备注，例如「膝盖不适期减重 20%」；不改就留空", required = false) String notes) {
        log.info("[Tool] updateWorkoutPlanItem userId={} itemId={} newExerciseId={} sets={}", userId, itemId, exerciseId, targetSets);
        try {
            WorkoutPlanItem current = workoutService.getPlanItem(itemId);
            if (!userId.equals(current.getUserId())) {
                return "ERROR: itemId=" + itemId + " 不属于当前用户，无法修改。"
                        + "请先调用 getActiveWorkoutPlan 获取当前用户自己的 itemId。";
            }
            WorkoutPlanItem updated = workoutService.updatePlanItem(itemId, new WorkoutPlanItemDTO(
                    current.getDayOfWeek(),
                    exerciseId != null ? exerciseId : current.getExerciseId(),
                    targetSets != null ? targetSets : current.getTargetSets(),
                    targetReps != null ? targetReps : current.getTargetReps(),
                    targetWeightKg != null ? BigDecimal.valueOf(targetWeightKg) : current.getTargetWeightKg(),
                    targetRpe != null ? BigDecimal.valueOf(targetRpe) : current.getTargetRpe(),
                    current.getSortOrder(),
                    notes != null ? notes : current.getNotes()));
            return json(toItemMap(updated));
        } catch (BusinessException e) {
            return error(e, "请先用 querySafeExercises 查询不冲突的动作，并用 getActiveWorkoutPlan 确认 itemId 与当日组数，再重新调用本工具。");
        }
    }

    @Tool("""
            当用户汇报「今天完成了某项训练」时调用本工具写入打卡流水，例如「杠铃卧推 80kg 做了 4 组 8 次，RPE 8.5」。
            本工具会用 Java 的 Epley 公式算出 estimated1rm（估算极限重量）和 totalVolume（总容量 = 组数 × 次数 × 重量）。
            严禁自己口算 1RM 或总容量，必须引用本工具返回的数值回复用户。
            返回：{"logId":..,"exerciseName":"..","actualSets":..,"actualReps":..,"actualWeightKg":..,
            "estimated1rm":..,"totalVolume":..,"logDate":".."}。""")
    public String logWorkoutExecution(
            @ToolMemoryId Long userId,
            @P("完成的动作 ID（exerciseId），必须来自 querySafeExercises 的返回值；不确定就先调用它查询")
            Long exerciseId,
            @P("实际完成的组数，1~15") Integer actualSets,
            @P("每组实际完成的次数，1~100") Integer actualReps,
            @P("实际使用的重量（kg），徒手动作填 0") Double actualWeightKg,
            @P(value = "实际 RPE（自觉用力程度），1.0~10.0；用户没提就留空", required = false) Double actualRpe,
            @P(value = "训练日期，格式 yyyy-MM-dd；用户说「今天」就留空，默认今天", required = false) String logDate,
            @P(value = "用户对本次训练的感受描述，例如「最后一组很吃力」", required = false) String feelingNotes) {
        log.info("[Tool] logWorkoutExecution userId={} exerciseId={} {}组×{}次×{}kg", userId, exerciseId, actualSets, actualReps, actualWeightKg);
        try {
            LocalDate date = parseDate(logDate);
            WorkoutLog saved = workoutService.logWorkout(new WorkoutLogCreateDTO(
                    userId, null, date, exerciseId, actualSets, actualReps,
                    BigDecimal.valueOf(actualWeightKg),
                    actualRpe == null ? null : BigDecimal.valueOf(actualRpe),
                    feelingNotes));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("logId", saved.getId());
            result.put("exerciseName", saved.getExerciseName());
            result.put("actualSets", saved.getActualSets());
            result.put("actualReps", saved.getActualReps());
            result.put("actualWeightKg", saved.getActualWeightKg());
            result.put("actualRpe", saved.getActualRpe());
            result.put("estimated1rm", saved.getEstimated1rm());
            result.put("totalVolume", saved.getTotalVolume());
            result.put("logDate", String.valueOf(saved.getLogDate()));
            return json(result);
        } catch (BusinessException e) {
            return error(e, "请先用 querySafeExercises 确认动作的 exerciseId 与安全性；组数须 1~15、次数 1~100、RPE 须 1.0~10.0。");
        }
    }

    @Tool("""
            查询用户最近若干天的训练打卡流水，用于分析训练容量与力量进展（例如「我这周练得怎么样」）。
            返回：{"days":7,"count":..,"logs":[{"logDate":"..","exerciseName":"..","actualSets":..,"actualReps":..,
            "actualWeightKg":..,"actualRpe":..,"estimated1rm":..,"totalVolume":..}]}。""")
    public String getRecentWorkoutLogs(
            @ToolMemoryId Long userId,
            @P(value = "查询最近多少天（含今天），1~365；用户没说明时用 7", required = false) Integer days) {
        int queryDays = days == null ? 7 : days;
        log.info("[Tool] getRecentWorkoutLogs userId={} days={}", userId, queryDays);
        try {
            List<WorkoutLog> logs = workoutService.findLogs(userId, queryDays);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("days", queryDays);
            result.put("count", logs.size());
            result.put("logs", logs.stream().map(l -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("logDate", String.valueOf(l.getLogDate()));
                item.put("exerciseName", l.getExerciseName());
                item.put("actualSets", l.getActualSets());
                item.put("actualReps", l.getActualReps());
                item.put("actualWeightKg", l.getActualWeightKg());
                item.put("actualRpe", l.getActualRpe());
                item.put("estimated1rm", l.getEstimated1rm());
                item.put("totalVolume", l.getTotalVolume());
                return item;
            }).toList());
            return json(result);
        } catch (BusinessException e) {
            return error(e, "days 需在 1~365 之间。");
        }
    }

    private Map<String, Object> toItemMap(WorkoutPlanItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("itemId", item.getId());
        map.put("dayOfWeek", item.getDayOfWeek());
        map.put("exerciseId", item.getExerciseId());
        map.put("exerciseName", item.getExerciseName());
        map.put("targetSets", item.getTargetSets());
        map.put("targetReps", item.getTargetReps());
        map.put("targetWeightKg", item.getTargetWeightKg());
        map.put("targetRpe", item.getTargetRpe());
        map.put("notes", item.getNotes());
        return map;
    }

    private LocalDate parseDate(String logDate) {
        if (logDate == null || logDate.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(logDate.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(com.fitness.ai.common.ErrorCode.BAD_REQUEST,
                    "日期格式非法：" + logDate + "，必须为 yyyy-MM-dd，例如 2026-10-25");
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