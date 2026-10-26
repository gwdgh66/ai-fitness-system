package com.fitness.ai.profile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitness.ai.profile.entity.UserProfile;

/**
 * user_profile 表 Mapper。
 *
 * <p>复杂查询优先使用 {@code LambdaQueryWrapper}，仅多表联查才写 XML。</p>
 */
public interface UserProfileMapper extends BaseMapper<UserProfile> {
}