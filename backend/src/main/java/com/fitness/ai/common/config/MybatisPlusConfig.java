package com.fitness.ai.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件与 Mapper 扫描配置。
 *
 * <p>插件顺序遵循官方建议：分页插件在前，防全表更新/删除插件在后。</p>
 */
@Configuration
@MapperScan("com.fitness.ai.*.mapper")
public class MybatisPlusConfig {

    /** 单页最大条数上限，防止一次性拉取过多数据。 */
    private static final long MAX_PAGE_SIZE = 500L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1) 分页插件。
        //    注意：MyBatis-Plus 3.5.9 起 PaginationInnerInterceptor 依赖 mybatis-plus-jsqlparser 模块，
        //    该依赖已在 pom.xml 中显式声明，缺失会导致本类无法编译。
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);

        // 2) 防全表更新与删除插件：拦截不带 WHERE 条件的 update / delete，
        //    作为 AGENTS.md「硬护栏」在 ORM 层的第一道兜底（业务边界校验仍必须在 Java 代码中显式编写）。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}