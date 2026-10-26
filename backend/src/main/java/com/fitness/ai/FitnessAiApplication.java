package com.fitness.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Personal AI Fitness System 后端启动类。
 *
 * <p>虚拟线程由 {@code spring.threads.virtual.enabled=true} 全局开启，无需在此显式配置。</p>
 */
@EnableScheduling
@SpringBootApplication
public class FitnessAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FitnessAiApplication.class, args);
    }
}