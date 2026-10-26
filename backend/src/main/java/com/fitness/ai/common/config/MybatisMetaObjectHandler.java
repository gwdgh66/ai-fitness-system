package com.fitness.ai.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 公共字段自动填充。
 *
 * <p>实体上标注 {@code @TableField(fill = FieldFill.INSERT)} / {@code INSERT_UPDATE} 的字段会自动写入时间；
 * 没有 updatedAt 字段的表（如 workout_log、diet_log）会被跳过。</p>
 *
 * <p><b>为什么更新用非严格填充 {@code setFieldValByName} 而不是 {@code strictUpdateFill}：</b>
 * {@code strictFillStrategy} 只在目标字段为 null 时才写入，而更新场景通常是
 * 「selectById 取出实体 → 改字段 → updateById」，此时实体的 updatedAt 早已非 null，
 * 严格填充会直接跳过，导致 updated_at 永远不刷新。这里显式覆盖以保证更新语义正确。</p>
 */
@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {

    private static final String CREATED_AT = "createdAt";
    private static final String UPDATED_AT = "updatedAt";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        // 插入用严格填充：调用方若已显式赋值，则尊重调用方的值。
        this.strictInsertFill(metaObject, CREATED_AT, LocalDateTime.class, now);
        this.strictInsertFill(metaObject, UPDATED_AT, LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新用非严格填充：无论实体是否已带旧值，都刷新为当前时间。
        this.setFieldValByName(UPDATED_AT, LocalDateTime.now(), metaObject);
    }
}