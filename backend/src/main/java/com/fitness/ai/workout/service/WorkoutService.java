package com.fitness.ai.workout.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.profile.entity.UserInjuryTag;
import com.fitness.ai.profile.entity.UserProfile;
import com.fitness.ai.profile.mapper.UserInjuryTagMapper;
import com.fitness.ai.profile.mapper.UserProfileMapper;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.workout.dto.CustomExerciseCreateDTO;
import com.fitness.ai.workout.dto.WorkoutLogCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanCreateDTO;
import com.fitness.ai.workout.dto.WorkoutPlanItemDTO;
import com.fitness.ai.workout.entity.ExerciseDict;
import com.fitness.ai.workout.entity.WorkoutLog;
import com.fitness.ai.workout.entity.WorkoutPlan;
import com.fitness.ai.workout.entity.WorkoutPlanItem;
import com.fitness.ai.workout.mapper.ExerciseDictMapper;
import com.fitness.ai.workout.mapper.WorkoutLogMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanItemMapper;
import com.fitness.ai.workout.mapper.WorkoutPlanMapper;
import com.fitness.ai.workout.vo.CustomExerciseDeleteVO;
import com.fitness.ai.workout.vo.PlanItemDeleteVO;
import com.fitness.ai.workout.vo.WorkoutPlanVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 训练课表与打卡服务：确定性 1RM / 容量计算 + 硬编码安全护栏（训练量 + 伤病冲突）。
 *
 * <p>本类是 AGENTS.md 第 4 条「硬护栏」的主战场：所有边界都在 Java 里显式判断，
 * 越界抛出带具体数值与修正建议的 {@link BusinessException}，绝不依赖模型自觉。</p>
 */
@Slf4j
@Service
public class WorkoutService {

    // ------------------------------------------------------------------
    // 硬编码安全护栏常量
    // ------------------------------------------------------------------

    /** 单动作组数下限 */
    public static final int MIN_SETS_PER_EXERCISE = 1;
    /** 单动作组数上限 */
    public static final int MAX_SETS_PER_EXERCISE = 15;
    /** 单日（同一 dayOfWeek）计划总组数上限 */
    public static final int MAX_SETS_PER_DAY = 30;
    /** 次数下限 */
    public static final int MIN_REPS = 1;
    /** 次数上限 */
    public static final int MAX_REPS = 100;
    /** RPE 下限 */
    public static final BigDecimal MIN_RPE = new BigDecimal("1.0");
    /** RPE 上限 */
    public static final BigDecimal MAX_RPE = new BigDecimal("10.0");

    private static final String PLAN_ACTIVE = "ACTIVE";
    private static final String PLAN_ARCHIVED = "ARCHIVED";
    private static final Set<String> SPLIT_TYPES = Set.of("PPL", "UPPER_LOWER", "FULL_BODY");
    private static final Set<String> TARGET_MUSCLES = Set.of("CHEST", "BACK", "LEGS", "SHOULDERS", "ARMS", "CORE");
    private static final Set<String> EQUIPMENTS = Set.of("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT", "OTHER");
    private static final Set<String> DIFFICULTIES = Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED");
    /** 系统已知的受伤部位：自定义动作的禁忌部位只接受这几个值，防止拼错导致护栏静默失效 */
    private static final Set<String> INJURY_PARTS = Set.of("LEFT_SHOULDER", "RIGHT_SHOULDER", "KNEE", "LOWER_BACK", "WRIST");
    private static final int CUSTOM_NAME_MIN = 2;
    private static final int CUSTOM_NAME_MAX = 32;
    private static final String[] WEEK_LABELS = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final ExerciseDictMapper exerciseDictMapper;
    private final WorkoutPlanMapper workoutPlanMapper;
    private final WorkoutPlanItemMapper workoutPlanItemMapper;
    private final WorkoutLogMapper workoutLogMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserInjuryTagMapper userInjuryTagMapper;

    public WorkoutService(ExerciseDictMapper exerciseDictMapper,
                          WorkoutPlanMapper workoutPlanMapper,
                          WorkoutPlanItemMapper workoutPlanItemMapper,
                          WorkoutLogMapper workoutLogMapper,
                          UserProfileMapper userProfileMapper,
                          UserInjuryTagMapper userInjuryTagMapper) {
        this.exerciseDictMapper = exerciseDictMapper;
        this.workoutPlanMapper = workoutPlanMapper;
        this.workoutPlanItemMapper = workoutPlanItemMapper;
        this.workoutLogMapper = workoutLogMapper;
        this.userProfileMapper = userProfileMapper;
        this.userInjuryTagMapper = userInjuryTagMapper;
    }

    // ------------------------------------------------------------------
    // 确定性公式（纯函数）
    // ------------------------------------------------------------------

    /**
     * Epley 公式估算极限重量，保留 1 位小数。
     *
     * <pre>
     *   reps == 1 → 1RM = weight
     *   reps &gt; 1  → 1RM = weight × (1 + reps / 30)
     * </pre>
     */
    public static BigDecimal estimateOneRm(BigDecimal weightKg, int reps) {
        BigDecimal value;
        if (reps == 1) {
            value = weightKg;
        } else {
            BigDecimal repsRatio = BigDecimal.valueOf(reps)
                    .divide(BigDecimal.valueOf(30), 10, RoundingMode.HALF_UP);
            value = weightKg.multiply(BigDecimal.ONE.add(repsRatio));
        }
        return value.setScale(1, RoundingMode.HALF_UP);
    }

    /** 总容量 = 组数 × 次数 × 重量，保留 1 位小数。 */
    public static BigDecimal calcTotalVolume(int sets, int reps, BigDecimal weightKg) {
        return weightKg.multiply(BigDecimal.valueOf((long) sets * reps)).setScale(1, RoundingMode.HALF_UP);
    }

    /** 计算动作禁忌部位与用户当前 ACTIVE 伤病部位的交集（两边都做去空格 / 大写归一）。 */
    public static Set<String> findConflictingParts(String avoidInjuryParts, Collection<String> activeInjuryParts) {
        Set<String> conflicts = new LinkedHashSet<>();
        if (avoidInjuryParts == null || avoidInjuryParts.isBlank() || activeInjuryParts == null) {
            return conflicts;
        }
        Set<String> avoid = Arrays.stream(avoidInjuryParts.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String part : activeInjuryParts) {
            if (part != null && avoid.contains(part.trim().toUpperCase(Locale.ROOT))) {
                conflicts.add(part.trim().toUpperCase(Locale.ROOT));
            }
        }
        return conflicts;
    }

    // ------------------------------------------------------------------
    // 安全动作查询
    // ------------------------------------------------------------------

    /** 查询动作字典，并在 Java 中过滤掉与该用户当前 ACTIVE 伤病冲突的动作。 */
    @Transactional(readOnly = true)
    public List<ExerciseDict> findSafeExercises(Long userId, String targetMuscle, String equipment) {
        requireProfile(userId);
        String muscle = normalize(targetMuscle);
        String equip = normalize(equipment);
        List<ExerciseDict> candidates = queryExercises(userId, muscle, equip);

        Set<String> activeParts = activeInjuryParts(userId);
        List<ExerciseDict> safe = candidates.stream()
                .filter(e -> findConflictingParts(e.getAvoidInjuryParts(), activeParts).isEmpty())
                .toList();
        log.info("用户 {} 安全动作查询：候选 {} 个，剔除 {} 个（ACTIVE 伤病={}），返回 {} 个",
                userId, candidates.size(), candidates.size() - safe.size(), activeParts, safe.size());
        return safe;
    }

    /**
     * 查询动作字典全量（**不做伤病过滤**）：系统内置动作 + 该用户的自定义动作。
     *
     * <p>仅供前端「换动作 / 自定义排课」弹窗的“显示全部动作（含禁忌）”开关使用：让使用者可以主动选择
     * 与当前 ACTIVE 伤病冲突的动作，从而真实触发并观察 Java 护栏的拦截效果。
     * 该接口只读、不写库；真正落库时 {@code updatePlanItem} / {@code createPlan} 仍会执行伤病冲突与训练量校验。</p>
     */
    @Transactional(readOnly = true)
    public List<ExerciseDict> findAllExercises(Long userId, String targetMuscle, String equipment) {
        List<ExerciseDict> all = queryExercises(userId, normalize(targetMuscle), normalize(equipment));
        log.info("全量动作查询（未过滤伤病）：userId={} targetMuscle={} equipment={} 返回 {} 个",
                userId, targetMuscle, equipment, all.size());
        return all;
    }

    /** 动作字典公共查询：校验取值域 + 按「系统内置 + 本人自定义」过滤。 */
    private List<ExerciseDict> queryExercises(Long userId, String muscle, String equipment) {
        if (muscle != null && !TARGET_MUSCLES.contains(muscle)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "targetMuscle 取值非法：" + muscle + "，允许的值为 " + TARGET_MUSCLES);
        }
        if (equipment != null && !EQUIPMENTS.contains(equipment)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "equipment 取值非法：" + equipment + "，允许的值为 " + EQUIPMENTS);
        }
        return exerciseDictMapper.selectList(Wrappers.<ExerciseDict>lambdaQuery()
                // 系统内置（user_id 为空）或本人自定义；看不到别人的自定义动作
                .and(userId != null, w -> w.isNull(ExerciseDict::getUserId)
                        .or().eq(ExerciseDict::getUserId, userId))
                .eq(muscle != null, ExerciseDict::getTargetMuscle, muscle)
                .eq(equipment != null, ExerciseDict::getEquipment, equipment)
                .orderByAsc(ExerciseDict::getTargetMuscle)
                .orderByAsc(ExerciseDict::getId));
    }

    // ------------------------------------------------------------------
    // 自定义动作
    // ------------------------------------------------------------------

    /**
     * 新建自定义动作（用户自己起名字）。
     *
     * <p>三条约束：① 名称全局唯一（表上有 UNIQUE KEY，这里先查后插并兜底捕获唯一键冲突）；
     * ② 目标肌群必填且取值受控；③ <b>禁忌部位严格校验</b> —— 伤病护栏完全依赖该字段，
     * 拼错会导致护栏静默失效，因此只接受系统已知的 5 个部位；留空表示「对所有伤病都安全」，
     * 日志里会明确记录这一点以便排查。</p>
     */
    @Transactional
    public ExerciseDict createCustomExercise(CustomExerciseCreateDTO dto) {
        requireProfile(dto.userId());
        String name = dto.name() == null ? "" : dto.name().trim();
        if (name.length() < CUSTOM_NAME_MIN || name.length() > CUSTOM_NAME_MAX) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "自定义动作名称长度必须在 " + CUSTOM_NAME_MIN + " ~ " + CUSTOM_NAME_MAX
                            + " 个字符之间，当前值=「" + name + "」");
        }
        String muscle = normalize(dto.targetMuscle());
        if (muscle == null || !TARGET_MUSCLES.contains(muscle)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "targetMuscle 取值非法：" + dto.targetMuscle() + "，允许的值为 " + TARGET_MUSCLES);
        }
        String equipment = normalize(dto.equipment()) == null ? "OTHER" : normalize(dto.equipment());
        if (!EQUIPMENTS.contains(equipment)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "equipment 取值非法：" + dto.equipment() + "，允许的值为 " + EQUIPMENTS);
        }
        String difficulty = normalize(dto.difficulty()) == null ? "INTERMEDIATE" : normalize(dto.difficulty());
        if (!DIFFICULTIES.contains(difficulty)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "difficulty 取值非法：" + dto.difficulty() + "，允许的值为 " + DIFFICULTIES);
        }
        String avoidParts = normalizeAvoidParts(dto.avoidInjuryParts());
        String cues = dto.cues() == null ? null : dto.cues().trim();
        if (cues != null && cues.length() > 500) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS, "动作要领最长 500 个字符");
        }

        ExerciseDict existing = exerciseDictMapper.selectOne(Wrappers.<ExerciseDict>lambdaQuery()
                .eq(ExerciseDict::getName, name)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "已存在同名动作「" + name + "」（" + (existing.getUserId() == null ? "系统内置" : "自定义")
                            + "，exerciseId=" + existing.getId() + "），直接选它即可，无需重复创建。");
        }

        ExerciseDict entity = new ExerciseDict();
        entity.setUserId(dto.userId());
        entity.setName(name);
        entity.setTargetMuscle(muscle);
        entity.setEquipment(equipment);
        entity.setDifficulty(difficulty);
        entity.setAvoidInjuryParts(avoidParts);
        entity.setCues(cues);
        try {
            exerciseDictMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发下唯一键兜底（表上有 uk_exercise_dict_name）
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "已存在同名动作「" + name + "」，请直接选用它。");
        }
        log.info("用户 {} 新建自定义动作「{}」（id={}，肌群={}，器械={}，禁忌部位={}）", dto.userId(), name,
                entity.getId(), muscle, equipment,
                avoidParts == null ? "未声明——伤病护栏无法拦截该动作，请提醒用户谨慎" : avoidParts);
        return exerciseDictMapper.selectById(entity.getId());
    }

    /**
     * 删除自定义动作。
     *
     * <p>护栏：系统内置动作不可删；只能删自己的；<b>已被课表明细或打卡记录引用时不可删</b>
     * —— 否则课表与训练历史会失去动作信息（宁可让用户先在课表里移除）。</p>
     */
    @Transactional
    public CustomExerciseDeleteVO deleteCustomExercise(Long exerciseId, Long userId) {
        ExerciseDict exercise = exerciseDictMapper.selectById(exerciseId);
        if (exercise == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "动作不存在：exerciseId=" + exerciseId);
        }
        if (exercise.getUserId() == null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "「" + exercise.getName() + "」是系统内置动作，不能删除（只能删除你自己创建的动作）。");
        }
        if (userId == null || !exercise.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "「" + exercise.getName() + "」不属于当前用户，不能删除。");
        }
        int planRefs = workoutPlanItemMapper.selectCount(Wrappers.<WorkoutPlanItem>lambdaQuery()
                .eq(WorkoutPlanItem::getExerciseId, exerciseId)).intValue();
        long logRefs = workoutLogMapper.selectCount(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getExerciseId, exerciseId));
        if (planRefs > 0 || logRefs > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "「" + exercise.getName() + "」仍被引用（课表明细 " + planRefs + " 条 / 打卡记录 " + logRefs
                            + " 条），不能删除：删掉会让这些课表与训练历史失去动作信息。请先从课表里移除该动作，或保留它。");
        }
        exerciseDictMapper.deleteById(exerciseId);
        log.info("用户 {} 删除自定义动作「{}」（id={}）", userId, exercise.getName(), exerciseId);
        return new CustomExerciseDeleteVO(exerciseId, exercise.getName(), planRefs, logRefs,
                "已删除自定义动作「" + exercise.getName() + "」。");
    }

    /** 禁忌部位归一化 + 严格校验（防止拼错导致护栏静默失效）。 */
    private String normalizeAvoidParts(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<String> parts = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(part -> part.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String part : parts) {
            if (!INJURY_PARTS.contains(part)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "avoidInjuryParts 含非法部位[" + part + "]，允许的值为 " + INJURY_PARTS
                                + "（拼错会导致伤病护栏静默失效，因此这里严格校验）");
            }
        }
        return String.join(",", parts);
    }

    // ------------------------------------------------------------------
    // 计划
    // ------------------------------------------------------------------

    /** 创建周训练计划：先归档旧 ACTIVE 计划，再逐条校验明细（伤病 + 训练量）后入库。 */
    @Transactional
    public WorkoutPlanVO createPlan(WorkoutPlanCreateDTO dto) {
        Long userId = dto.userId();
        requireProfile(userId);
        String splitType = requireInSet("splitType", dto.splitType(), SPLIT_TYPES);

        List<WorkoutPlanItemDTO> items = dto.items();
        Map<Integer, List<String>> perDayDetails = new TreeMap<>();
        Map<Integer, Integer> perDaySets = new TreeMap<>();

        for (WorkoutPlanItemDTO item : items) {
            int day = requireDayOfWeek(item.dayOfWeek());
            ExerciseDict exercise = requireExercise(item.exerciseId());
            checkInjuryConflict(userId, exercise);
            validateSets(item.targetSets(), exercise.getName());
            validateReps(item.targetReps(), exercise.getName());
            validateRpe(item.targetRpe(), exercise.getName());
            validateWeight(item.targetWeightKg(), exercise.getName());
            perDaySets.merge(day, item.targetSets(), Integer::sum);
            perDayDetails.computeIfAbsent(day, d -> new ArrayList<>())
                    .add(exercise.getName() + " " + item.targetSets() + " 组");
        }
        perDaySets.forEach((day, totalSets) -> {
            if (totalSets > MAX_SETS_PER_DAY) {
                throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                        "训练量护栏拦截：" + WEEK_LABELS[day] + " 的当日计划总组数为 " + totalSets
                                + " 组，超过单日安全上限 " + MAX_SETS_PER_DAY + " 组。请减少动作数量或单动作组数（"
                                + WEEK_LABELS[day] + " 明细：" + String.join(" + ", perDayDetails.get(day)) + "）。");
            }
        });

        archiveActivePlans(userId);

        WorkoutPlan plan = new WorkoutPlan();
        plan.setUserId(userId);
        plan.setPlanName(dto.planName());
        plan.setSplitType(splitType);
        plan.setStartDate(dto.startDate());
        plan.setEndDate(dto.endDate());
        plan.setStatus(PLAN_ACTIVE);
        workoutPlanMapper.insert(plan);

        int order = 1;
        for (WorkoutPlanItemDTO item : items) {
            ExerciseDict exercise = requireExercise(item.exerciseId());
            WorkoutPlanItem entity = new WorkoutPlanItem();
            entity.setPlanId(plan.getId());
            entity.setUserId(userId);
            entity.setDayOfWeek(item.dayOfWeek());
            entity.setExerciseId(exercise.getId());
            entity.setExerciseName(exercise.getName());
            entity.setTargetSets(item.targetSets());
            entity.setTargetReps(item.targetReps());
            entity.setTargetWeightKg(item.targetWeightKg());
            entity.setTargetRpe(item.targetRpe());
            entity.setSortOrder(item.sortOrder() == null ? order++ : item.sortOrder());
            entity.setNotes(item.notes());
            workoutPlanItemMapper.insert(entity);
        }
        log.info("用户 {} 新建训练计划 {}（id={}，{} 条明细）", userId, dto.planName(), plan.getId(), items.size());
        return getActivePlan(userId);
    }

    /** 查询用户当前 ACTIVE 的完整周课表。 */
    @Transactional(readOnly = true)
    public WorkoutPlanVO getActivePlan(Long userId) {
        requireProfile(userId);
        WorkoutPlan plan = workoutPlanMapper.selectOne(Wrappers.<WorkoutPlan>lambdaQuery()
                .eq(WorkoutPlan::getUserId, userId)
                .eq(WorkoutPlan::getStatus, PLAN_ACTIVE)
                .orderByDesc(WorkoutPlan::getId)
                .last("LIMIT 1"));
        if (plan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "用户 " + userId + " 当前没有处于 ACTIVE 状态的训练计划，请先调用 POST /api/workout/plans 创建");
        }
        List<WorkoutPlanItem> items = workoutPlanItemMapper.selectList(Wrappers.<WorkoutPlanItem>lambdaQuery()
                .eq(WorkoutPlanItem::getPlanId, plan.getId())
                .orderByAsc(WorkoutPlanItem::getDayOfWeek)
                .orderByAsc(WorkoutPlanItem::getSortOrder)
                .orderByAsc(WorkoutPlanItem::getId));
        return new WorkoutPlanVO(plan.getId(), plan.getUserId(), plan.getPlanName(), plan.getSplitType(),
                plan.getStartDate(), plan.getEndDate(), plan.getStatus(),
                plan.getCreatedAt(), plan.getUpdatedAt(), items);
    }

    /** 按 itemId 查询课表明细（供 AI 工具做「部分字段修改」时取当前值）。 */
    @Transactional(readOnly = true)
    public WorkoutPlanItem getPlanItem(Long itemId) {
        WorkoutPlanItem item = itemId == null ? null : workoutPlanItemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "课表明细不存在：itemId=" + itemId);
        }
        return item;
    }

    /** 替换课表中某条明细（重新触发伤病与训练量护栏校验）。 */
    @Transactional
    public WorkoutPlanItem updatePlanItem(Long itemId, WorkoutPlanItemDTO dto) {
        WorkoutPlanItem item = workoutPlanItemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "课表明细不存在：itemId=" + itemId);
        }
        int day = requireDayOfWeek(dto.dayOfWeek());
        ExerciseDict exercise = requireExercise(dto.exerciseId());
        checkInjuryConflict(item.getUserId(), exercise);
        validateSets(dto.targetSets(), exercise.getName());
        validateReps(dto.targetReps(), exercise.getName());
        validateRpe(dto.targetRpe(), exercise.getName());
        validateWeight(dto.targetWeightKg(), exercise.getName());

        int otherSets = workoutPlanItemMapper.selectList(Wrappers.<WorkoutPlanItem>lambdaQuery()
                        .eq(WorkoutPlanItem::getPlanId, item.getPlanId())
                        .eq(WorkoutPlanItem::getDayOfWeek, day)
                        .ne(WorkoutPlanItem::getId, itemId))
                .stream()
                .map(WorkoutPlanItem::getTargetSets)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        int totalSets = otherSets + dto.targetSets();
        if (totalSets > MAX_SETS_PER_DAY) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "训练量护栏拦截：" + WEEK_LABELS[day] + " 的当日总组数将变为 " + totalSets + " 组（该日其它动作 "
                            + otherSets + " 组 + 本条 " + dto.targetSets() + " 组），超过单日安全上限 "
                            + MAX_SETS_PER_DAY + " 组。请调低本条组数或先减少该日其它动作。");
        }

        item.setDayOfWeek(day);
        item.setExerciseId(exercise.getId());
        item.setExerciseName(exercise.getName());
        item.setTargetSets(dto.targetSets());
        item.setTargetReps(dto.targetReps());
        item.setTargetWeightKg(dto.targetWeightKg());
        item.setTargetRpe(dto.targetRpe());
        if (dto.sortOrder() != null) {
            item.setSortOrder(dto.sortOrder());
        }
        item.setNotes(dto.notes());
        workoutPlanItemMapper.updateById(item);
        log.info("课表明细 {} 已更新为动作[{}] {} 组 × {} 次", itemId, exercise.getName(),
                dto.targetSets(), dto.targetReps());
        return workoutPlanItemMapper.selectById(itemId);
    }

    // ------------------------------------------------------------------
    // 删除课表明细
    // ------------------------------------------------------------------

    /**
     * 删除课表中的某条明细。
     *
     * <p>护栏与约定：</p>
     * <ol>
     *   <li>明细必须存在，否则 404；</li>
     *   <li><b>只能删除 ACTIVE 计划里的动作</b>：历史归档计划不允许改（否则历史记录会被篡改）；</li>
     *   <li>删除后自动把该日剩余动作的 sortOrder 重排为 1..N，避免界面出现 #1 #3 断号；</li>
     *   <li><b>已产生的训练打卡记录一律保留</b>：workout_log 存的是动作快照（动作名/组数/重量/1RM/容量），
     *       删除课表动作不会抹掉训练历史；</li>
     *   <li>允许把某天甚至整份计划删空（该日即成为休息日），此时返回 planEmptied=true 提示用户重建。</li>
     * </ol>
     */
    @Transactional
    public PlanItemDeleteVO deletePlanItem(Long itemId) {
        WorkoutPlanItem item = workoutPlanItemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "课表明细不存在：itemId=" + itemId);
        }
        WorkoutPlan plan = workoutPlanMapper.selectById(item.getPlanId());
        if (plan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "该明细所属的周计划不存在：planId=" + item.getPlanId());
        }
        if (!PLAN_ACTIVE.equals(plan.getStatus())) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "该明细属于已归档的历史计划（planId=" + plan.getId() + "，status=" + plan.getStatus()
                            + "），只能删除当前激活课表里的动作。如需重建请使用训练模板或 AI 排课。");
        }

        Integer dayOfWeek = item.getDayOfWeek();
        int removedSets = item.getTargetSets() == null ? 0 : item.getTargetSets();
        String exerciseName = item.getExerciseName();
        String dayLabel = dayLabel(dayOfWeek);

        // 先统计「该动作已产生的打卡记录」——删除后这些历史依然保留
        long preservedLogs = workoutLogMapper.selectCount(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getPlanItemId, itemId));

        workoutPlanItemMapper.deleteById(itemId);

        // 重排当日剩余动作的顺序，保持 1..N 连续
        List<WorkoutPlanItem> remaining = workoutPlanItemMapper.selectList(Wrappers.<WorkoutPlanItem>lambdaQuery()
                .eq(WorkoutPlanItem::getPlanId, item.getPlanId())
                .eq(WorkoutPlanItem::getDayOfWeek, dayOfWeek)
                .orderByAsc(WorkoutPlanItem::getSortOrder)
                .orderByAsc(WorkoutPlanItem::getId));
        int order = 1;
        for (WorkoutPlanItem row : remaining) {
            if (row.getSortOrder() == null || row.getSortOrder() != order) {
                row.setSortOrder(order);
                workoutPlanItemMapper.updateById(row);
            }
            order++;
        }

        int remainingSets = remaining.stream()
                .map(WorkoutPlanItem::getTargetSets)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        boolean planEmptied = remaining.isEmpty() && workoutPlanItemMapper.selectCount(
                Wrappers.<WorkoutPlanItem>lambdaQuery().eq(WorkoutPlanItem::getPlanId, item.getPlanId())) == 0;

        String message = "已从" + dayLabel + "删除「" + exerciseName + "」（原 " + removedSets + " 组）；"
                + "该日剩余 " + remaining.size() + " 个动作共 " + remainingSets + " 组"
                + (preservedLogs > 0 ? "，该动作已产生的 " + preservedLogs + " 条打卡记录已保留" : "")
                + (planEmptied ? "。当前课表已没有任何动作，可用训练模板或 AI 排课重新生成。" : "。");
        log.info("课表明细 {}（{} · {} 组）已从计划 {} 删除；该日剩余 {} 个动作 {} 组；保留打卡记录 {} 条；计划已空={}",
                itemId, exerciseName, removedSets, plan.getId(), remaining.size(), remainingSets,
                preservedLogs, planEmptied);

        return new PlanItemDeleteVO(itemId, exerciseName, dayOfWeek, dayLabel, removedSets,
                remaining.size(), remainingSets, preservedLogs, planEmptied, message);
    }

    private static String dayLabel(Integer dayOfWeek) {
        if (dayOfWeek != null && dayOfWeek >= 1 && dayOfWeek <= WEEK_LABELS.length - 1) {
            return WEEK_LABELS[dayOfWeek];
        }
        return dayOfWeek == null ? "该训练日" : "第 " + dayOfWeek + " 天";
    }

    // ------------------------------------------------------------------
    // 打卡
    // ------------------------------------------------------------------

    /** 记录训练打卡：护栏校验后自动计算 estimated1rm 与 totalVolume 落库。 */
    @Transactional
    public WorkoutLog logWorkout(WorkoutLogCreateDTO dto) {
        requireProfile(dto.userId());
        // planItemId 是「软引用」：允许为空（散记打卡），但一旦传了就必须真实存在且属于当前用户，
        // 否则会写入指向不存在明细的孤儿记录 —— 这类记录永远不参与双渐进统计，只会污染账单。
        if (dto.planItemId() != null) {
            WorkoutPlanItem planItem = workoutPlanItemMapper.selectById(dto.planItemId());
            if (planItem == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                        "课表明细不存在：planItemId=" + dto.planItemId()
                                + "，请先查询当前课表 /api/workout/plans/active");
            }
            if (!dto.userId().equals(planItem.getUserId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "课表明细 " + dto.planItemId() + " 不属于用户 " + dto.userId());
            }
        }
        ExerciseDict exercise = requireExercise(dto.exerciseId());
        validateSets(dto.actualSets(), exercise.getName());
        validateReps(dto.actualReps(), exercise.getName());
        validateRpe(dto.actualRpe(), exercise.getName());
        validateWeight(dto.actualWeightKg(), exercise.getName());

        WorkoutLog entity = new WorkoutLog();
        entity.setUserId(dto.userId());
        entity.setPlanItemId(dto.planItemId());
        entity.setLogDate(dto.logDate());
        entity.setExerciseId(exercise.getId());
        entity.setExerciseName(exercise.getName());
        entity.setActualSets(dto.actualSets());
        entity.setActualReps(dto.actualReps());
        entity.setActualWeightKg(dto.actualWeightKg());
        entity.setActualRpe(dto.actualRpe());
        entity.setEstimated1rm(estimateOneRm(dto.actualWeightKg(), dto.actualReps()));
        entity.setTotalVolume(calcTotalVolume(dto.actualSets(), dto.actualReps(), dto.actualWeightKg()));
        entity.setFeelingNotes(dto.feelingNotes());
        workoutLogMapper.insert(entity);
        log.info("用户 {} 打卡[{}] {} 组 × {} 次 × {} kg → 1RM={} 容量={}", dto.userId(), exercise.getName(),
                dto.actualSets(), dto.actualReps(), dto.actualWeightKg(), entity.getEstimated1rm(), entity.getTotalVolume());
        return entity;
    }

    /** 查询用户最近 days 天（含今天）的训练打卡流水。 */
    @Transactional(readOnly = true)
    public List<WorkoutLog> findLogs(Long userId, int days) {
        if (days < 1 || days > 365) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "days=" + days + " 超出查询范围，必须在 1 ~ 365 天之间");
        }
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        return workoutLogMapper.selectList(Wrappers.<WorkoutLog>lambdaQuery()
                .eq(WorkoutLog::getUserId, userId)
                .ge(WorkoutLog::getLogDate, from)
                .orderByDesc(WorkoutLog::getLogDate)
                .orderByDesc(WorkoutLog::getId));
    }

    // ------------------------------------------------------------------
    // 护栏与工具
    // ------------------------------------------------------------------

    /** 核心护栏：动作禁忌部位 vs 用户当前 ACTIVE 伤病部位。 */
    private void checkInjuryConflict(Long userId, ExerciseDict exercise) {
        List<UserInjuryTag> activeInjuries = userInjuryTagMapper.selectList(Wrappers.<UserInjuryTag>lambdaQuery()
                .eq(UserInjuryTag::getUserId, userId)
                .eq(UserInjuryTag::getStatus, ProfileService.INJURY_ACTIVE)
                .orderByAsc(UserInjuryTag::getId));
        Set<String> activeParts = activeInjuries.stream()
                .map(UserInjuryTag::getBodyPart)
                .filter(java.util.Objects::nonNull)
                .map(p -> p.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> conflicts = findConflictingParts(exercise.getAvoidInjuryParts(), activeParts);
        if (conflicts.isEmpty()) {
            return;
        }
        String injuryDetail = activeInjuries.stream()
                .filter(t -> t.getBodyPart() != null
                        && conflicts.contains(t.getBodyPart().toUpperCase(Locale.ROOT)))
                .map(t -> t.getBodyPart() + "（严重程度=" + t.getSeverity() + "，伤病记录ID=" + t.getId() + "）")
                .collect(Collectors.joining("、"));
        throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                "伤病冲突护栏拦截：动作[" + exercise.getName() + "]的禁忌部位包含 "
                        + String.join(",", conflicts) + "（该动作 avoid_injury_parts=" + exercise.getAvoidInjuryParts()
                        + "），而用户 " + userId + " 当前存在这些部位的 ACTIVE 伤病：" + injuryDetail
                        + "。请改用不涉及上述部位的动作，可调用 GET /api/workout/exercises/safe?userId=" + userId
                        + " 获取安全动作列表。");
    }

    private Set<String> activeInjuryParts(Long userId) {
        return userInjuryTagMapper.selectList(Wrappers.<UserInjuryTag>lambdaQuery()
                        .eq(UserInjuryTag::getUserId, userId)
                        .eq(UserInjuryTag::getStatus, ProfileService.INJURY_ACTIVE))
                .stream()
                .map(UserInjuryTag::getBodyPart)
                .filter(java.util.Objects::nonNull)
                .map(p -> p.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void validateSets(Integer sets, String exerciseName) {
        if (sets == null || sets < MIN_SETS_PER_EXERCISE || sets > MAX_SETS_PER_EXERCISE) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "训练量护栏拦截：动作[" + exerciseName + "]的组数为 " + sets + "，必须在 "
                            + MIN_SETS_PER_EXERCISE + " ~ " + MAX_SETS_PER_EXERCISE + " 组之间。");
        }
    }

    private void validateReps(Integer reps, String exerciseName) {
        if (reps == null || reps < MIN_REPS || reps > MAX_REPS) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "训练量护栏拦截：动作[" + exerciseName + "]的次数为 " + reps + "，必须在 "
                            + MIN_REPS + " ~ " + MAX_REPS + " 次之间。");
        }
    }

    private void validateRpe(BigDecimal rpe, String exerciseName) {
        if (rpe == null) {
            return;
        }
        if (rpe.compareTo(MIN_RPE) < 0 || rpe.compareTo(MAX_RPE) > 0) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "训练量护栏拦截：动作[" + exerciseName + "]的 RPE 为 " + rpe + "，必须在 "
                            + MIN_RPE + " ~ " + MAX_RPE + " 之间。");
        }
    }

    private void validateWeight(BigDecimal weightKg, String exerciseName) {
        if (weightKg == null) {
            return;
        }
        if (weightKg.signum() < 0 || weightKg.compareTo(new BigDecimal("1000")) > 0) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "训练量护栏拦截：动作[" + exerciseName + "]的重量为 " + weightKg + " kg，必须在 0 ~ 1000 kg 之间。");
        }
    }

    private int requireDayOfWeek(Integer dayOfWeek) {
        if (dayOfWeek == null || dayOfWeek < 1 || dayOfWeek > 7) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    "dayOfWeek=" + dayOfWeek + " 非法，必须是 1 ~ 7（1 代表周一，7 代表周日）");
        }
        return dayOfWeek;
    }

    private UserProfile requireProfile(Long userId) {
        UserProfile profile = userId == null ? null : userProfileMapper.selectById(userId);
        if (profile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "用户档案不存在：userId=" + userId + "，请先创建该用户的体测档案");
        }
        return profile;
    }

    private ExerciseDict requireExercise(Long exerciseId) {
        ExerciseDict exercise = exerciseId == null ? null : exerciseDictMapper.selectById(exerciseId);
        if (exercise == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "动作不存在：exerciseId=" + exerciseId + "，请先查询 /api/workout/exercises/safe 获取可用动作");
        }
        return exercise;
    }

    private void archiveActivePlans(Long userId) {
        List<WorkoutPlan> actives = workoutPlanMapper.selectList(Wrappers.<WorkoutPlan>lambdaQuery()
                .eq(WorkoutPlan::getUserId, userId)
                .eq(WorkoutPlan::getStatus, PLAN_ACTIVE));
        for (WorkoutPlan plan : actives) {
            plan.setStatus(PLAN_ARCHIVED);
            workoutPlanMapper.updateById(plan);
        }
        if (!actives.isEmpty()) {
            log.info("用户 {} 原有 {} 个 ACTIVE 计划已归档", userId, actives.size());
        }
    }

    private String requireInSet(String field, String value, Set<String> allowed) {
        String normalized = normalize(value);
        if (normalized == null || !allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    field + " 取值非法：" + value + "，允许的值为 " + allowed);
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}