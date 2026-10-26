package com.fitness.ai.progression.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.progression.vo.ProgressionApplyVO;
import com.fitness.ai.progression.vo.ProgressionRecommendationVO;
import com.fitness.ai.workout.dto.WorkoutPlanItemDTO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.entity.WorkoutLog;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.mapper.ExerciseDictMapper;
import com.fitness.ai.workout.mapper.WorkoutLogMapper;
import com.fitness.ai.workout.service.WorkoutService;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 确定性渐进超负荷引擎（双重渐进 Double Progression）。
 *
 * <p>完全不调用大模型：读取当前激活课表的每个动作，与最近 2~3 次该动作的真实打卡比对，
 * 用固定阈值给出加重 / 维持 / 减载建议。所有数值计算都在这里用 Java 完成。</p>
 *
 * <pre>
 *   OVERLOAD_READY ：最近连续 2 次 actualReps &gt;= 计划次数 且 actualRpe &lt;= 8.0
 *                    → 建议加重（LEGS +5.0kg，其余 +2.5kg）
 *   DELOAD_NEEDED  ：最近连续 2 次 actualRpe &gt;= 9.5，或实际次数低于计划次数 20% 以上
 *                    → 建议降重 10%
 *   MAINTAIN       ：完成目标但 8.0 &lt; actualRpe &lt;= 9.0，或尚未稳定完成目标
 *                    → 维持当前重量与次数
 *   INSUFFICIENT_DATA：同动作历史打卡不足 2 次
 * </pre>
 */
@Slf4j
@Service
public class ProgressionService {

    /** 每次最多参考的历史打卡条数 */
    private static final int SAMPLE_SIZE = 3;
    /** 触发加重的 RPE 上限 */
    private static final BigDecimal OVERLOAD_RPE_CEILING = new BigDecimal("8.0");
    /** 触发减载的 RPE 下限 */
    private static final BigDecimal DELOAD_RPE_FLOOR = new BigDecimal("9.5");
    /** 实际次数低于计划次数的比例阈值（低于 80% 即预警） */
    private static final BigDecimal REP_SHORT_RATIO = new BigDecimal("0.8");
    /** 下肢加重幅度（kg） */
    private static final BigDecimal LOWER_BODY_INCREMENT = new BigDecimal("5.0");
    /** 其他部位加重幅度（kg） */
    private static final BigDecimal UPPER_BODY_INCREMENT = new BigDecimal("2.5");
    /** 减载比例 */
    private static final BigDecimal DELOAD_RATIO = new BigDecimal("0.9");

    public static final String STATUS_OVERLOAD_READY = "OVERLOAD_READY";
    public static final String STATUS_MAINTAIN = "MAINTAIN";
    public static final String STATUS_DELOAD_NEEDED = "DELOAD_NEEDED";
    public static final String STATUS_INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

    private final WorkoutService workoutService;
    private final WorkoutLogMapper workoutLogMapper;
    private final ExerciseDictMapper exerciseDictMapper;

    public ProgressionService(WorkoutService workoutService,
                              WorkoutLogMapper workoutLogMapper,
                              ExerciseDictMapper exerciseDictMapper) {
        this.workoutService = workoutService;
        this.workoutLogMapper = workoutLogMapper;
        this.exerciseDictMapper = exerciseDictMapper;
    }

    /** 扫描当前激活课表的每个动作，给出进阶建议。 */
    @Transactional(readOnly = true)
    public List<ProgressionRecommendationVO> recommend(Long userId) {
        WorkoutPlanVO plan = workoutService.getActivePlan(userId);
        List<ProgressionRecommendationVO> recommendations = new ArrayList<>();
        for (WorkoutPlanItem item : plan.items()) {
            recommendations.add(recommendForItem(item));
        }
        log.info("用户 {} 进阶建议生成完成：计划 {} 共 {} 个动作", userId, plan.planName(), recommendations.size());
        return recommendations;
    }

    /** 采纳某个明细的进阶建议：把建议重量/次数一键写回课表（仍会重新过一遍安全护栏）。 */
    @Transactional
    public ProgressionApplyVO apply(Long itemId) {
        WorkoutPlanItem item = workoutService.getPlanItem(itemId);
        ProgressionRecommendationVO recommendation = recommendForItem(item);
        if (STATUS_INSUFFICIENT_DATA.equals(recommendation.status())) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "该动作历史打卡不足 2 次，尚无可靠的进阶建议，无法采纳：itemId=" + itemId);
        }
        BigDecimal previousWeight = item.getTargetWeightKg();
        Integer previousReps = item.getTargetReps();

        workoutService.updatePlanItem(itemId, new WorkoutPlanItemDTO(
                item.getDayOfWeek(),
                item.getExerciseId(),
                item.getTargetSets(),
                recommendation.suggestedReps(),
                recommendation.suggestedWeightKg(),
                item.getTargetRpe(),
                item.getSortOrder(),
                item.getNotes()));

        WorkoutPlanItem updated = workoutService.getPlanItem(itemId);
        String message = switch (recommendation.status()) {
            case STATUS_OVERLOAD_READY -> "已按进阶建议加重：" + previousWeight + "kg → "
                    + updated.getTargetWeightKg() + "kg";
            case STATUS_DELOAD_NEEDED -> "已按减载建议降重：" + previousWeight + "kg → "
                    + updated.getTargetWeightKg() + "kg";
            default -> "已维持当前重量：" + updated.getTargetWeightKg() + "kg";
        };
        log.info("采纳进阶建议：itemId={} 动作={} {} → {}", itemId, item.getExerciseName(), previousWeight,
                updated.getTargetWeightKg());
        return new ProgressionApplyVO(itemId, item.getExerciseName(), recommendation.status(),
                previousWeight, updated.getTargetWeightKg(), previousReps, updated.getTargetReps(), message);
    }

    // ------------------------------------------------------------------
    // 核心算法
    // ------------------------------------------------------------------

    private ProgressionRecommendationVO recommendForItem(WorkoutPlanItem item) {
        // 归属规则：**优先按课表明细（plan_item_id）**。
        // 因为同一个动作在自定义课表里可能有多个「重量组」（如 80kg×1 / 90kg×2 / 100kg×3），
        // 每个重量组必须各自独立进阶，不能共享同一份历史。
        List<WorkoutLog> boundLogs = workoutLogMapper.selectList(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, item.getUserId())
                .eq(WorkoutLog::getPlanItemId, item.getId())
                .orderByDesc(WorkoutLog::getLogDate)
                .orderByDesc(WorkoutLog::getId)
                .last("LIMIT " + SAMPLE_SIZE));
        // 兜底：未绑定明细的散记（例如在 AI 对话里直接说「今天深蹲 100kg 4×8」产生的打卡）
        // 也算作该动作的历史，避免这些记录被完全忽略。
        List<WorkoutLog> unboundLogs = workoutLogMapper.selectList(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, item.getUserId())
                .eq(WorkoutLog::getExerciseId, item.getExerciseId())
                .isNull(WorkoutLog::getPlanItemId)
                .orderByDesc(WorkoutLog::getLogDate)
                .orderByDesc(WorkoutLog::getId)
                .last("LIMIT " + SAMPLE_SIZE));
        List<WorkoutLog> recent = Stream.concat(boundLogs.stream(), unboundLogs.stream())
                .sorted(Comparator.comparing(WorkoutLog::getLogDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(WorkoutLog::getId, Comparator.reverseOrder()))
                .limit(SAMPLE_SIZE)
                .toList();

        ExerciseDict exercise = item.getExerciseId() == null ? null
                : exerciseDictMapper.selectById(item.getExerciseId());
        String muscle = exercise == null ? null : exercise.getTargetMuscle();
        BigDecimal planWeight = item.getTargetWeightKg() == null ? BigDecimal.ZERO : item.getTargetWeightKg();
        // 计算基准取「计划目标重量」与「最近一次实际重量」的较大值：
        // 若用户实际已经练得比计划更重，加重应当以实际重量为基准，否则会给出低于已完成水平的建议。
        BigDecimal latestLogWeight = recent.isEmpty() ? null : recent.get(0).getActualWeightKg();
        BigDecimal currentWeight = (latestLogWeight != null && latestLogWeight.compareTo(planWeight) > 0)
                ? latestLogWeight
                : planWeight;
        Integer targetReps = item.getTargetReps();

        List<ProgressionRecommendationVO.RecentLogBrief> briefs = recent.stream()
                .map(l -> new ProgressionRecommendationVO.RecentLogBrief(l.getLogDate(), l.getActualSets(),
                        l.getActualReps(), l.getActualWeightKg(), l.getActualRpe(), l.getEstimated1rm()))
                .toList();

        if (targetReps == null || recent.size() < 2) {
            return new ProgressionRecommendationVO(item.getId(), item.getExerciseId(), item.getExerciseName(),
                    muscle, item.getTargetSets(), targetReps, item.getTargetWeightKg(), currentWeight,
                    STATUS_INSUFFICIENT_DATA, currentWeight, targetReps,
                    "同动作历史打卡仅 " + recent.size() + " 次，不足 2 次无法判定渐进方向，请先积累打卡数据。",
                    recent.size(), briefs);
        }

        List<WorkoutLog> lastTwo = recent.stream().limit(2).toList();
        boolean repsMet = lastTwo.stream().allMatch(l -> l.getActualReps() != null && l.getActualReps() >= targetReps);
        boolean rpeMissing = lastTwo.stream().anyMatch(l -> l.getActualRpe() == null);
        boolean highRpe = !rpeMissing && lastTwo.stream()
                .allMatch(l -> l.getActualRpe().compareTo(DELOAD_RPE_FLOOR) >= 0);
        boolean lowRpe = !rpeMissing && lastTwo.stream()
                .allMatch(l -> l.getActualRpe().compareTo(OVERLOAD_RPE_CEILING) <= 0);
        boolean repShort = lastTwo.stream().anyMatch(l -> l.getActualReps() != null
                && BigDecimal.valueOf(l.getActualReps())
                .compareTo(BigDecimal.valueOf(targetReps).multiply(REP_SHORT_RATIO)) < 0);

        if (highRpe || repShort) {
            BigDecimal suggested = currentWeight.multiply(DELOAD_RATIO).setScale(1, RoundingMode.HALF_UP);
            String reason = highRpe
                    ? "最近连续 2 次实际 RPE 均 >= 9.5（" + rpeText(lastTwo) + "），局部疲劳累积，建议降重 10% 恢复。"
                    : "最近 2 次中有实际完成次数低于计划次数 20% 以上（计划 " + targetReps + " 次，实际 "
                    + repsText(lastTwo) + " 次），建议降重 10% 并保证动作质量。";
            return build(item, muscle, currentWeight, targetReps, STATUS_DELOAD_NEEDED, suggested, targetReps,
                    reason, briefs);
        }
        if (repsMet && lowRpe) {
            BigDecimal increment = "LEGS".equalsIgnoreCase(muscle) ? LOWER_BODY_INCREMENT : UPPER_BODY_INCREMENT;
            BigDecimal suggested = currentWeight.add(increment).setScale(1, RoundingMode.HALF_UP);
            String reason = "最近连续 2 次均完成计划次数（" + targetReps + " 次）且 RPE <= 8.0（" + rpeText(lastTwo)
                    + "），具备加重条件，建议 " + ("LEGS".equalsIgnoreCase(muscle) ? "下肢" : "上肢")
                    + "加重 " + increment + "kg。";
            return build(item, muscle, currentWeight, targetReps, STATUS_OVERLOAD_READY, suggested, targetReps,
                    reason, briefs);
        }
        String reason = repsMet
                ? "已完成计划次数但 RPE 偏高（" + rpeText(lastTwo) + "，处于 8.0~9.0 区间），建议先维持当前重量与次数，"
                + "等 RPE 回落到 8.0 以下再加重。"
                : "尚未连续稳定完成计划次数（计划 " + targetReps + " 次，实际 " + repsText(lastTwo)
                + " 次），建议维持当前重量，先把次数做稳。";
        return build(item, muscle, currentWeight, targetReps, STATUS_MAINTAIN, currentWeight, targetReps, reason, briefs);
    }

    private ProgressionRecommendationVO build(WorkoutPlanItem item, String muscle, BigDecimal currentWeight,
                                              Integer targetReps, String status, BigDecimal suggestedWeight,
                                              Integer suggestedReps, String reason,
                                              List<ProgressionRecommendationVO.RecentLogBrief> briefs) {
        return new ProgressionRecommendationVO(item.getId(), item.getExerciseId(), item.getExerciseName(), muscle,
                item.getTargetSets(), targetReps, item.getTargetWeightKg(), currentWeight, status, suggestedWeight,
                suggestedReps, reason, briefs.size(), briefs);
    }

    private static String rpeText(List<WorkoutLog> logs) {
        return logs.stream()
                .sorted(Comparator.comparing(WorkoutLog::getLogDate))
                .map(l -> String.valueOf(l.getActualRpe()))
                .reduce((a, b) -> a + " / " + b)
                .orElse("-");
    }

    private static String repsText(List<WorkoutLog> logs) {
        return logs.stream()
                .sorted(Comparator.comparing(WorkoutLog::getLogDate))
                .map(l -> String.valueOf(l.getActualReps()))
                .reduce((a, b) -> a + " / " + b)
                .orElse("-");
    }
}