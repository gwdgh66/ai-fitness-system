package com.fitness.ai.ai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.ai.common.BusinessException;
import com.fitness.ai.profile.dto.InjuryTagCreateDTO;
import com.fitness.ai.profile.dto.UserProfileUpdateDTO;
import com.fitness.ai.profile.entity.UserInjuryTag;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.profile.vo.UserDigitalTwinVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户档案与伤病工具集。
 *
 * <p>写操作统一遵守「异常不外抛」约定：捕获 {@link BusinessException} 后转成以 {@code ERROR: } 开头的
 * 字符串返回给大模型，让模型在同一轮对话里自我修正，而不是炸断整个对话流。</p>
 */
@Slf4j
@Component("profileTools")
public class ProfileTools {

    private final ProfileService profileService;
    private final ObjectMapper objectMapper;

    public ProfileTools(ProfileService profileService, ObjectMapper objectMapper) {
        this.profileService = profileService;
        this.objectMapper = objectMapper;
    }

    @Tool("""
            当用户在对话中提到身体某个部位出现疼痛、受伤、拉伤或不适时调用本工具，把该部位登记为用户的活跃伤病（ACTIVE）。
            登记后所有训练动作都会被伤病安全带过滤。如果该部位已有活跃记录，本工具会更新症状描述与严重程度，不会重复新增。
            返回登记后的伤病记录：{"injuryId":..,"bodyPart":"..","severity":"..","status":".."}。""")
    public String recordOrUpdateInjury(
            @ToolMemoryId Long userId,
            @P("受伤部位的标准枚举值，可选：LEFT_SHOULDER、RIGHT_SHOULDER、KNEE、LOWER_BACK、UPPER_BACK、WRIST、ELBOW、ANKLE、HIP。用户说「左肩」用 LEFT_SHOULDER，说「膝盖」用 KNEE，说「腰」用 LOWER_BACK")
            String bodyPart,
            @P("用户描述的具体症状，例如「卧推时肩前侧刺痛」「下楼时膝盖发软」")
            String description,
            @P(value = "严重程度：MILD（轻微）/ MODERATE（中度）/ SEVERE（严重）；用户没说明时用 MILD", required = false)
            String severity) {
        log.info("[Tool] recordOrUpdateInjury userId={} bodyPart={} severity={}", userId, bodyPart, severity);
        try {
            UserInjuryTag tag = profileService.addInjury(userId,
                    new InjuryTagCreateDTO(bodyPart, description, severity));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("injuryId", tag.getId());
            result.put("bodyPart", tag.getBodyPart());
            result.put("severity", tag.getSeverity());
            result.put("status", tag.getStatus());
            result.put("description", tag.getDescription());
            return json(result);
        } catch (BusinessException e) {
            return error(e, "请检查 bodyPart 是否使用了枚举中的标准值后重试。");
        }
    }

    @Tool("""
            当用户明确表示某个部位的伤病已经康复、不再疼痛时调用本工具，把对应伤病记录的状态从 ACTIVE 改为 RECOVERED。
            改为 RECOVERED 后，相关动作不再被伤病安全带过滤。返回更新后的伤病记录。""")
    public String markInjuryRecovered(
            @ToolMemoryId Long userId,
            @P("已经康复的受伤部位标准值，例如 LEFT_SHOULDER、KNEE、LOWER_BACK")
            String bodyPart) {
        log.info("[Tool] markInjuryRecovered userId={} bodyPart={}", userId, bodyPart);
        try {
            String normalized = bodyPart == null ? "" : bodyPart.trim().toUpperCase(java.util.Locale.ROOT);
            List<UserInjuryTag> actives = profileService.listActiveInjuries(userId);
            UserInjuryTag target = actives.stream()
                    .filter(t -> t.getBodyPart() != null && t.getBodyPart().equalsIgnoreCase(normalized))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                String existing = actives.isEmpty() ? "（当前没有任何 ACTIVE 伤病）"
                        : actives.stream().map(UserInjuryTag::getBodyPart).toList().toString();
                return "ERROR: 用户当前没有部位为 " + bodyPart + " 的 ACTIVE 伤病记录，无法标记康复。当前活跃伤病："
                        + existing + "。请确认部位名称，或先调用 recordOrUpdateInjury 登记该伤病。";
            }
            UserInjuryTag updated = profileService.updateInjuryStatus(target.getId(),
                    ProfileService.INJURY_RECOVERED);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("injuryId", updated.getId());
            result.put("bodyPart", updated.getBodyPart());
            result.put("status", updated.getStatus());
            return json(result);
        } catch (BusinessException e) {
            return error(e, null);
        }
    }

    @Tool("""
            当用户汇报了最新的体重、体脂率、身高、年龄、性别、活动系数，或要求修改健身目标、器械环境时调用本工具。
            本工具会更新档案，并由 Java 按 Mifflin-St Jeor 公式重新计算 BMR 与 TDEE。
            严禁自己口算 BMR/TDEE，必须调用本工具后引用其返回值。只传用户真正提到的字段，其余留空表示不修改。
            返回：{"bmr":..,"tdee":..,"weightKg":..,"bodyFatPct":..,"fitnessGoal":..,"equipmentEnv":..}。""")
    public String updateBodyMetrics(
            @ToolMemoryId Long userId,
            @P(value = "最新体重（kg），例如 74.5", required = false) BigDecimal weightKg,
            @P(value = "最新体脂率，传百分比数值（16 表示 16%）", required = false) BigDecimal bodyFatPct,
            @P(value = "身高（cm）", required = false) BigDecimal heightCm,
            @P(value = "年龄（岁）", required = false) Integer age,
            @P(value = "性别：MALE 或 FEMALE", required = false) String gender,
            @P(value = "活动系数：久坐 1.2、每周训练 1~3 次 1.375、每周 3~5 次 1.55、每周 6~7 次 1.725、体力劳动 1.9", required = false)
            BigDecimal activityLevel,
            @P(value = "健身目标：MUSCLE_GAIN（增肌）/ FAT_LOSS（减脂）/ STRENGTH（力量）", required = false)
            String fitnessGoal,
            @P(value = "器械环境：GYM（健身房）/ DUMBBELL（哑铃居家）/ BODYWEIGHT（徒手）", required = false)
            String equipmentEnv) {
        log.info("[Tool] updateBodyMetrics userId={} weight={} bodyFat={} goal={}", userId, weightKg, bodyFatPct, fitnessGoal);
        try {
            UserDigitalTwinVO twin = profileService.updateProfile(userId, new UserProfileUpdateDTO(
                    null, gender, age, heightCm, weightKg, bodyFatPct, activityLevel, fitnessGoal, equipmentEnv));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bmr", twin.bmr());
            result.put("tdee", twin.tdee());
            result.put("weightKg", twin.weightKg());
            result.put("bodyFatPct", twin.bodyFatPct());
            result.put("heightCm", twin.heightCm());
            result.put("activityLevel", twin.activityLevel());
            result.put("fitnessGoal", twin.fitnessGoal());
            result.put("equipmentEnv", twin.equipmentEnv());
            result.put("activeInjuries", twin.activeInjuries().size());
            return json(result);
        } catch (BusinessException e) {
            return error(e, "请检查传入的数值是否在合理区间内（体重 25~300kg、身高 100~250cm、年龄 10~100、活动系数 1.0~2.5）。");
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