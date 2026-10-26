package com.fitness.ai.profile.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.common.ErrorCode;
import com.fitness.ai.profile.dto.InjuryTagCreateDTO;
import com.fitness.ai.profile.dto.UserProfileUpdateDTO;
import com.fitness.ai.profile.entity.UserInjuryTag;
import com.fitness.ai.profile.entity.UserProfile;
import com.fitness.ai.profile.mapper.UserInjuryTagMapper;
import com.fitness.ai.profile.mapper.UserProfileMapper;
import com.fitness.ai.profile.vo.UserDigitalTwinVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用户数字孪生服务：体测档案维护 + 确定性 BMR/TDEE 计算 + 伤病标签管理。
 *
 * <p>严格遵守 AGENTS.md 第 1 条：所有数值计算都在这里用确定性 Java 代码完成，
 * 后续即使暴露给 LLM，也只是「调用方法」而不是「让模型算」。</p>
 */
@Slf4j
@Service
public class ProfileService {

    /** 性别取值 */
    public static final String GENDER_MALE = "MALE";
    public static final String GENDER_FEMALE = "FEMALE";
    private static final Set<String> GENDERS = Set.of(GENDER_MALE, GENDER_FEMALE);

    /** 健身目标取值 */
    private static final Set<String> FITNESS_GOALS = Set.of("MUSCLE_GAIN", "FAT_LOSS", "STRENGTH");
    /** 器械环境取值 */
    private static final Set<String> EQUIPMENT_ENVS = Set.of("GYM", "DUMBBELL", "BODYWEIGHT");
    /** 伤病严重程度取值 */
    private static final Set<String> SEVERITIES = Set.of("MILD", "MODERATE", "SEVERE");
    /** 伤病状态取值 */
    public static final String INJURY_ACTIVE = "ACTIVE";
    public static final String INJURY_RECOVERED = "RECOVERED";
    private static final Set<String> INJURY_STATUSES = Set.of(INJURY_ACTIVE, INJURY_RECOVERED);

    /** 活动系数合理区间（BMR → TDEE 的乘数，超出即为脏数据） */
    private static final BigDecimal ACTIVITY_LEVEL_MIN = new BigDecimal("1.0");
    private static final BigDecimal ACTIVITY_LEVEL_MAX = new BigDecimal("2.5");
    /** 体测数值的物理合理区间 */
    private static final BigDecimal HEIGHT_MIN = new BigDecimal("100");
    private static final BigDecimal HEIGHT_MAX = new BigDecimal("250");
    private static final BigDecimal WEIGHT_MIN = new BigDecimal("25");
    private static final BigDecimal WEIGHT_MAX = new BigDecimal("300");
    private static final int AGE_MIN = 10;
    private static final int AGE_MAX = 100;

    private final UserProfileMapper userProfileMapper;
    private final UserInjuryTagMapper userInjuryTagMapper;

    public ProfileService(UserProfileMapper userProfileMapper, UserInjuryTagMapper userInjuryTagMapper) {
        this.userProfileMapper = userProfileMapper;
        this.userInjuryTagMapper = userInjuryTagMapper;
    }

    // ------------------------------------------------------------------
    // 确定性公式（纯函数，便于测试与后续复用）
    // ------------------------------------------------------------------

    /**
     * Mifflin-St Jeor 公式计算基础代谢率 BMR（kcal/天）。
     *
     * <pre>
     *   男性：BMR = round(10 × weightKg + 6.25 × heightCm - 5 × age + 5)
     *   女性：BMR = round(10 × weightKg + 6.25 × heightCm - 5 × age - 161)
     * </pre>
     */
    public static int calculateBmr(String gender, BigDecimal weightKg, BigDecimal heightCm, int age) {
        if (gender == null || !GENDERS.contains(gender.toUpperCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "无法计算 BMR：gender 必须是 MALE 或 FEMALE，当前值=" + gender);
        }
        double base = 10 * weightKg.doubleValue() + 6.25 * heightCm.doubleValue() - 5 * age;
        double bmr = GENDER_FEMALE.equalsIgnoreCase(gender) ? base - 161 : base + 5;
        return (int) Math.round(bmr);
    }

    /** TDEE = round(BMR × 活动系数)。 */
    public static int calculateTdee(int bmr, BigDecimal activityLevel) {
        if (activityLevel == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无法计算 TDEE：activityLevel 不能为空");
        }
        return (int) Math.round(bmr * activityLevel.doubleValue());
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public UserDigitalTwinVO getDigitalTwin(Long userId) {
        UserProfile profile = requireProfile(userId);
        List<UserInjuryTag> activeInjuries = listActiveInjuries(userId);
        return toVo(profile, activeInjuries);
    }

    @Transactional(readOnly = true)
    public List<UserInjuryTag> listActiveInjuries(Long userId) {
        return userInjuryTagMapper.selectList(Wrappers.<UserInjuryTag>lambdaQuery()
                .eq(UserInjuryTag::getUserId, userId)
                .eq(UserInjuryTag::getStatus, INJURY_ACTIVE)
                .orderByAsc(UserInjuryTag::getId));
    }

    // ------------------------------------------------------------------
    // 档案更新
    // ------------------------------------------------------------------

    /**
     * 部分更新体测档案；只要 gender / age / heightCm / weightKg / activityLevel 齐全，
     * 就自动重算并落库 BMR 与 TDEE。
     */
    @Transactional
    public UserDigitalTwinVO updateProfile(Long userId, UserProfileUpdateDTO dto) {
        UserProfile profile = requireProfile(userId);

        if (dto.nickname() != null) {
            profile.setNickname(dto.nickname());
        }
        if (dto.gender() != null) {
            profile.setGender(requireGender(dto.gender()));
        }
        if (dto.age() != null) {
            requireBetween("age", dto.age(), AGE_MIN, AGE_MAX);
            profile.setAge(dto.age());
        }
        if (dto.heightCm() != null) {
            requireBetween("heightCm", dto.heightCm(), HEIGHT_MIN, HEIGHT_MAX);
            profile.setHeightCm(dto.heightCm());
        }
        if (dto.weightKg() != null) {
            requireBetween("weightKg", dto.weightKg(), WEIGHT_MIN, WEIGHT_MAX);
            profile.setWeightKg(dto.weightKg());
        }
        if (dto.bodyFatPct() != null) {
            requireBetween("bodyFatPct", dto.bodyFatPct(), BigDecimal.ZERO, new BigDecimal("70"));
            profile.setBodyFatPct(dto.bodyFatPct());
        }
        if (dto.activityLevel() != null) {
            requireBetween("activityLevel", dto.activityLevel(), ACTIVITY_LEVEL_MIN, ACTIVITY_LEVEL_MAX);
            profile.setActivityLevel(dto.activityLevel());
        }
        if (dto.fitnessGoal() != null) {
            profile.setFitnessGoal(requireInSet("fitnessGoal", dto.fitnessGoal(), FITNESS_GOALS));
        }
        if (dto.equipmentEnv() != null) {
            profile.setEquipmentEnv(requireInSet("equipmentEnv", dto.equipmentEnv(), EQUIPMENT_ENVS));
        }

        recalculateEnergy(profile);
        userProfileMapper.updateById(profile);
        log.info("用户 {} 体测档案已更新，BMR={} TDEE={}", userId, profile.getBmr(), profile.getTdee());
        return getDigitalTwin(userId);
    }

    /**
     * 依据当前档案重算 BMR / TDEE（缺任一必要字段则保持原值不动）。
     */
    private void recalculateEnergy(UserProfile profile) {
        boolean computable = profile.getGender() != null && profile.getAge() != null
                && profile.getHeightCm() != null && profile.getWeightKg() != null
                && profile.getActivityLevel() != null;
        if (!computable) {
            log.warn("用户 {} 档案缺少 gender/age/heightCm/weightKg/activityLevel 中的部分字段，跳过 BMR/TDEE 重算",
                    profile.getId());
            return;
        }
        int bmr = calculateBmr(profile.getGender(), profile.getWeightKg(), profile.getHeightCm(), profile.getAge());
        profile.setBmr(bmr);
        profile.setTdee(calculateTdee(bmr, profile.getActivityLevel()));
    }

    // ------------------------------------------------------------------
    // 伤病标签
    // ------------------------------------------------------------------

    /** 新增伤病标签；同一部位已有 ACTIVE 记录时改为更新其描述与严重程度。 */
    @Transactional
    public UserInjuryTag addInjury(Long userId, InjuryTagCreateDTO dto) {
        requireProfile(userId);
        String bodyPart = dto.bodyPart().trim().toUpperCase(Locale.ROOT);
        String severity = dto.severity() == null || dto.severity().isBlank()
                ? "MILD"
                : requireInSet("severity", dto.severity(), SEVERITIES);

        UserInjuryTag existing = userInjuryTagMapper.selectOne(Wrappers.<UserInjuryTag>lambdaQuery()
                .eq(UserInjuryTag::getUserId, userId)
                .eq(UserInjuryTag::getBodyPart, bodyPart)
                .eq(UserInjuryTag::getStatus, INJURY_ACTIVE)
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setDescription(dto.description());
            existing.setSeverity(severity);
            userInjuryTagMapper.updateById(existing);
            log.info("用户 {} 的 {} 伤病记录已存在，已更新描述与严重程度（id={}）", userId, bodyPart, existing.getId());
            return userInjuryTagMapper.selectById(existing.getId());
        }

        UserInjuryTag tag = new UserInjuryTag();
        tag.setUserId(userId);
        tag.setBodyPart(bodyPart);
        tag.setDescription(dto.description());
        tag.setSeverity(severity);
        tag.setStatus(INJURY_ACTIVE);
        userInjuryTagMapper.insert(tag);
        log.info("用户 {} 新增伤病标签 {}（id={}，severity={}）", userId, bodyPart, tag.getId(), severity);
        return tag;
    }

    /** 切换伤病状态：ACTIVE <-> RECOVERED。 */
    @Transactional
    public UserInjuryTag updateInjuryStatus(Long injuryId, String status) {
        String target = status == null ? null : status.trim().toUpperCase(Locale.ROOT);
        if (target == null || !INJURY_STATUSES.contains(target)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "伤病状态只能是 ACTIVE 或 RECOVERED，当前值=" + status);
        }
        UserInjuryTag tag = userInjuryTagMapper.selectById(injuryId);
        if (tag == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "伤病记录不存在：injuryId=" + injuryId);
        }
        tag.setStatus(target);
        userInjuryTagMapper.updateById(tag);
        log.info("伤病记录 {} 状态已改为 {}", injuryId, target);
        return userInjuryTagMapper.selectById(injuryId);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private UserProfile requireProfile(Long userId) {
        UserProfile profile = userId == null ? null : userProfileMapper.selectById(userId);
        if (profile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "用户档案不存在：userId=" + userId + "，请先创建该用户的体测档案");
        }
        return profile;
    }

    private UserDigitalTwinVO toVo(UserProfile p, List<UserInjuryTag> injuries) {
        return new UserDigitalTwinVO(p.getId(), p.getNickname(), p.getGender(), p.getAge(),
                p.getHeightCm(), p.getWeightKg(), p.getBodyFatPct(), p.getBmr(), p.getTdee(),
                p.getActivityLevel(), p.getFitnessGoal(), p.getEquipmentEnv(),
                p.getCreatedAt(), p.getUpdatedAt(), injuries);
    }

    private String requireGender(String gender) {
        return requireInSet("gender", gender, GENDERS);
    }

    private String requireInSet(String field, String value, Set<String> allowed) {
        String normalized = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    field + " 取值非法：" + value + "，允许的值为 " + allowed);
        }
        return normalized;
    }

    private void requireBetween(String field, BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    field + "=" + value + " 超出合理区间，必须在 " + min + " ~ " + max + " 之间");
        }
    }

    private void requireBetween(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new BusinessException(ErrorCode.PARAM_OUT_OF_BOUNDS,
                    field + "=" + value + " 超出合理区间，必须在 " + min + " ~ " + max + " 之间");
        }
    }
}