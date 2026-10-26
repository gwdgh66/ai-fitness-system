package com.fitness.ai.profile.controller;

import com.fitness.ai.common.Result;
import com.fitness.ai.profile.dto.InjuryTagCreateDTO;
import com.fitness.ai.profile.dto.UserProfileUpdateDTO;
import com.fitness.ai.profile.entity.UserInjuryTag;
import com.fitness.ai.profile.service.ProfileService;
import com.fitness.ai.profile.vo.UserDigitalTwinVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户数字孪生接口。
 */
@Tag(name = "Profile", description = "用户数字孪生：体测档案与伤病标签")
@RestController
@RequestMapping("/api/profile")
@Validated
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(summary = "获取用户数字孪生", description = "返回体测档案 + 当前所有 ACTIVE 状态的伤病标签")
    @GetMapping("/{userId}")
    public Result<UserDigitalTwinVO> getDigitalTwin(@PathVariable Long userId) {
        return Result.success(profileService.getDigitalTwin(userId));
    }

    @Operation(summary = "更新体测档案", description = "部分更新；身高/体重/年龄/性别/活动系数变更后自动重算 BMR 与 TDEE")
    @PutMapping("/{userId}")
    public Result<UserDigitalTwinVO> updateProfile(@PathVariable Long userId,
                                                   @Valid @RequestBody UserProfileUpdateDTO dto) {
        return Result.success(profileService.updateProfile(userId, dto));
    }

    @Operation(summary = "新增伤病标签", description = "同一部位已有 ACTIVE 记录时，改为更新其描述与严重程度")
    @PostMapping("/{userId}/injuries")
    public Result<UserInjuryTag> addInjury(@PathVariable Long userId,
                                           @Valid @RequestBody InjuryTagCreateDTO dto) {
        return Result.success(profileService.addInjury(userId, dto));
    }

    @Operation(summary = "修改伤病状态", description = "在 ACTIVE 与 RECOVERED 之间切换")
    @PatchMapping("/injuries/{injuryId}/status")
    public Result<UserInjuryTag> updateInjuryStatus(@PathVariable Long injuryId,
                                                    @RequestParam String status) {
        return Result.success(profileService.updateInjuryStatus(injuryId, status));
    }
}