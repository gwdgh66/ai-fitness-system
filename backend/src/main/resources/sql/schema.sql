-- =====================================================================
-- Personal AI Fitness System - 表结构初始化脚本（MySQL 8.0+）
--
-- 执行方式（手动执行，不会被 Spring Boot 自动加载，因文件位于 classpath:sql/ 下）：
--   mysql -h 127.0.0.1 -P 3306 -u root -p < backend/src/main/resources/sql/schema.sql
--
-- 说明：
--   1. 脚本可重复执行（每张表前均有 DROP TABLE IF EXISTS）。
--   2. 主键统一为 BIGINT 雪花 ID（MyBatis-Plus IdType.ASSIGN_ID），不使用 AUTO_INCREMENT。
--   3. 所有计算类字段（bmr / tdee / estimated_1rm / total_volume 等）均由 Java Service 层的
--      确定性方法写入，禁止由 LLM 直接计算（见 AGENTS.md 第 1 条）。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS fitness_ai
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE fitness_ai;

SET NAMES utf8mb4;

-- =====================================================================
-- (1) 用户数字孪生模块  com.fitness.ai.profile
-- =====================================================================

DROP TABLE IF EXISTS user_profile;
CREATE TABLE user_profile
(
    id             BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    nickname       VARCHAR(64)  NULL COMMENT '昵称',
    gender         VARCHAR(16)  NOT NULL DEFAULT 'MALE' COMMENT '性别：MALE / FEMALE',
    age            INT          NULL COMMENT '年龄（岁）',
    height_cm      DECIMAL(5, 2) NULL COMMENT '身高（cm）',
    weight_kg      DECIMAL(5, 2) NULL COMMENT '体重（kg）',
    body_fat_pct   DECIMAL(4, 2) NULL COMMENT '体脂率（%）',
    bmr            INT          NULL COMMENT '基础代谢率（kcal/天，Mifflin-St Jeor，由 Java 计算）',
    tdee           INT          NULL COMMENT '每日总消耗（kcal/天 = BMR × 活动系数，由 Java 计算）',
    activity_level DECIMAL(3, 2) NULL COMMENT '活动系数：1.2~1.9',
    fitness_goal   VARCHAR(32)  NULL COMMENT '健身目标：MUSCLE_GAIN / FAT_LOSS / STRENGTH',
    equipment_env  VARCHAR(32)  NULL COMMENT '器械环境：GYM / DUMBBELL / BODYWEIGHT',
    created_at     DATETIME     NULL COMMENT '创建时间（自动填充）',
    updated_at     DATETIME     NULL COMMENT '更新时间（自动填充）',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户体测与目标档案表';

DROP TABLE IF EXISTS user_injury_tag;
CREATE TABLE user_injury_tag
(
    id          BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    user_id     BIGINT       NOT NULL COMMENT '用户 ID（user_profile.id）',
    body_part   VARCHAR(32)  NOT NULL COMMENT '受伤部位：LEFT_SHOULDER / RIGHT_SHOULDER / KNEE / LOWER_BACK / WRIST 等',
    description VARCHAR(255) NULL COMMENT '具体症状描述',
    severity    VARCHAR(16)  NOT NULL DEFAULT 'MILD' COMMENT '严重程度：MILD / MODERATE / SEVERE',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / RECOVERED',
    created_at  DATETIME     NULL COMMENT '创建时间（自动填充）',
    updated_at  DATETIME     NULL COMMENT '更新时间（自动填充）',
    PRIMARY KEY (id),
    KEY idx_user_injury_tag_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户动态伤病与禁忌表';

-- =====================================================================
-- (2) 训练课表与打卡模块  com.fitness.ai.workout
-- =====================================================================

DROP TABLE IF EXISTS exercise_dict;
CREATE TABLE exercise_dict
(
    id                 BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    user_id            BIGINT       NULL COMMENT 'NULL=系统内置动作；非空=该用户自定义的动作',
    name               VARCHAR(64)  NOT NULL COMMENT '标准动作名称（全局唯一）',
    target_muscle      VARCHAR(32)  NOT NULL COMMENT '目标肌群：CHEST / BACK / LEGS / SHOULDERS / ARMS / CORE',
    equipment          VARCHAR(32)  NOT NULL COMMENT '所需器械：BARBELL / DUMBBELL / MACHINE / CABLE / BODYWEIGHT / OTHER',
    difficulty         VARCHAR(32)  NOT NULL DEFAULT 'BEGINNER' COMMENT '难度：BEGINNER / INTERMEDIATE / ADVANCED',
    avoid_injury_parts VARCHAR(255) NULL COMMENT '禁忌受伤部位（逗号分隔，如 KNEE,LOWER_BACK），供 Agent 换动作时做安全过滤',
    cues               VARCHAR(500) NULL COMMENT '动作核心要领',
    PRIMARY KEY (id),
    UNIQUE KEY uk_exercise_dict_name (name),
    KEY idx_exercise_dict_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='标准健身动作字典表（含用户自定义动作）';

DROP TABLE IF EXISTS workout_plan;
CREATE TABLE workout_plan
(
    id         BIGINT      NOT NULL COMMENT '主键（雪花 ID）',
    user_id    BIGINT      NOT NULL COMMENT '用户 ID（user_profile.id）',
    plan_name  VARCHAR(64) NOT NULL COMMENT '计划名称',
    split_type VARCHAR(32) NOT NULL COMMENT '分化方式：PPL / UPPER_LOWER / FULL_BODY',
    start_date DATE        NULL COMMENT '计划开始日期',
    end_date   DATE        NULL COMMENT '计划结束日期',
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / ARCHIVED',
    created_at DATETIME    NULL COMMENT '创建时间（自动填充）',
    updated_at DATETIME    NULL COMMENT '更新时间（自动填充）',
    PRIMARY KEY (id),
    KEY idx_workout_plan_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='周期训练计划主表';

DROP TABLE IF EXISTS workout_plan_item;
CREATE TABLE workout_plan_item
(
    id               BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    plan_id          BIGINT        NOT NULL COMMENT '所属计划 ID（workout_plan.id）',
    user_id          BIGINT        NOT NULL COMMENT '用户 ID（冗余，便于按用户直查）',
    day_of_week      INT           NOT NULL COMMENT '训练日：1~7 代表周一至周日',
    exercise_id      BIGINT        NOT NULL COMMENT '动作 ID（exercise_dict.id）',
    exercise_name    VARCHAR(64)   NOT NULL COMMENT '动作名称（冗余快照，避免字典变更导致历史课表失真）',
    target_sets      INT           NULL COMMENT '目标组数（业务护栏：1~30）',
    target_reps      INT           NULL COMMENT '目标次数（每组）',
    target_weight_kg DECIMAL(6, 2) NULL COMMENT '目标重量（kg）',
    target_rpe       DECIMAL(3, 1) NULL COMMENT '目标 RPE（自觉用力程度，6~10）',
    sort_order       INT           NULL COMMENT '当日课表内排序',
    notes            VARCHAR(255)  NULL COMMENT '备注',
    created_at       DATETIME      NULL COMMENT '创建时间（自动填充）',
    updated_at       DATETIME      NULL COMMENT '更新时间（自动填充）',
    PRIMARY KEY (id),
    KEY idx_workout_plan_item_plan_id (plan_id),
    KEY idx_workout_plan_item_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='训练课表每日动作明细表';

DROP TABLE IF EXISTS workout_log;
CREATE TABLE workout_log
(
    id               BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    user_id          BIGINT        NOT NULL COMMENT '用户 ID（user_profile.id）',
    plan_item_id     BIGINT        NULL COMMENT '关联课表明细 ID（自由训练时为空）',
    log_date         DATE          NOT NULL COMMENT '打卡日期',
    exercise_id      BIGINT        NULL COMMENT '动作 ID（exercise_dict.id）',
    exercise_name    VARCHAR(64)   NOT NULL COMMENT '动作名称（冗余快照）',
    actual_sets      INT           NULL COMMENT '实际完成组数（业务护栏：1~30）',
    actual_reps      INT           NULL COMMENT '实际完成次数（每组）',
    actual_weight_kg DECIMAL(6, 2) NULL COMMENT '实际使用重量（kg）',
    actual_rpe       DECIMAL(3, 1) NULL COMMENT '实际 RPE',
    estimated_1rm    DECIMAL(6, 2) NULL COMMENT 'Epley 公式估算极限重量 = 重量 × (1 + 次数 / 30)，由 Java 计算',
    total_volume     DECIMAL(10, 2) NULL COMMENT '总容量 = 组数 × 次数 × 重量，由 Java 计算',
    feeling_notes    VARCHAR(255)  NULL COMMENT '训练感受备注',
    created_at       DATETIME      NULL COMMENT '创建时间（自动填充）',
    PRIMARY KEY (id),
    KEY idx_workout_log_user_id (user_id),
    KEY idx_workout_log_log_date (log_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='实际训练打卡流水表';

-- =====================================================================
-- (3) 饮食与营养账单模块  com.fitness.ai.nutrition
-- =====================================================================

DROP TABLE IF EXISTS food_nutrition_dict;
CREATE TABLE food_nutrition_dict
(
    id                BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    food_name         VARCHAR(64)   NOT NULL COMMENT '食物名称（唯一）',
    category          VARCHAR(32)   NOT NULL COMMENT '分类：CARB / PROTEIN / FAT / VEG_FRUIT',
    calories_per_100g INT           NOT NULL COMMENT '每 100g 热量（kcal）',
    protein_per_100g  DECIMAL(5, 2) NULL COMMENT '每 100g 蛋白质（g）',
    carbs_per_100g    DECIMAL(5, 2) NULL COMMENT '每 100g 碳水（g）',
    fat_per_100g      DECIMAL(5, 2) NULL COMMENT '每 100g 脂肪（g）',
    unit_hint         VARCHAR(255)  NULL COMMENT '常见份量提示，如 "1个全蛋约50g，1勺乳清蛋白粉约30g"',
    PRIMARY KEY (id),
    UNIQUE KEY uk_food_nutrition_dict_name (food_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='本地食物营养成分字典表（按每 100g 计）';

DROP TABLE IF EXISTS daily_macro_target;
CREATE TABLE daily_macro_target
(
    id               BIGINT      NOT NULL COMMENT '主键（雪花 ID）',
    user_id          BIGINT      NOT NULL COMMENT '用户 ID（user_profile.id）',
    target_date      DATE        NOT NULL COMMENT '目标日期',
    day_type         VARCHAR(32) NOT NULL DEFAULT 'MEDIUM_CARB' COMMENT '碳水循环日型：HIGH_CARB / MEDIUM_CARB / LOW_CARB',
    target_calories  INT         NULL COMMENT '目标热量（kcal，业务护栏：>= 1000）',
    target_protein_g INT         NULL COMMENT '目标蛋白质（g）',
    target_carbs_g   INT         NULL COMMENT '目标碳水（g）',
    target_fat_g     INT         NULL COMMENT '目标脂肪（g）',
    created_at       DATETIME    NULL COMMENT '创建时间（自动填充）',
    updated_at       DATETIME    NULL COMMENT '更新时间（自动填充）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_macro_target_user_date (user_id, target_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='每日营养素目标表（支持碳水循环）';

DROP TABLE IF EXISTS diet_log;
CREATE TABLE diet_log
(
    id         BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    user_id    BIGINT        NOT NULL COMMENT '用户 ID（user_profile.id）',
    log_date   DATE          NOT NULL COMMENT '记录日期',
    meal_type  VARCHAR(32)   NOT NULL COMMENT '餐次：BREAKFAST / LUNCH / DINNER / SNACK',
    food_name  VARCHAR(64)   NOT NULL COMMENT '食物名称（冗余快照）',
    weight_g   DECIMAL(7, 2) NULL COMMENT '摄入重量（g）',
    calories   INT           NULL COMMENT '本条摄入热量（kcal，按 100g 数据折算后由 Java 计算）',
    protein_g  DECIMAL(6, 2) NULL COMMENT '本条摄入蛋白质（g）',
    carbs_g    DECIMAL(6, 2) NULL COMMENT '本条摄入碳水（g）',
    fat_g      DECIMAL(6, 2) NULL COMMENT '本条摄入脂肪（g）',
    source     VARCHAR(24)   NOT NULL DEFAULT 'DICTIONARY' COMMENT '数据来源：DICTIONARY 字典命中 / CUSTOM 用户自填 / AI_ESTIMATED AI 估算（已人工确认）',
    created_at DATETIME      NULL COMMENT '创建时间（自动填充）',
    PRIMARY KEY (id),
    KEY idx_diet_log_user_id (user_id),
    KEY idx_diet_log_log_date (log_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='每日饮食打卡流水表';

-- =====================================================================
-- (4) 预设模板库  com.fitness.ai.catalog
-- =====================================================================

DROP TABLE IF EXISTS meal_template;
CREATE TABLE meal_template
(
    id              BIGINT        NOT NULL COMMENT '主键（雪花 ID）',
    user_id         BIGINT        NOT NULL COMMENT '用户 ID（user_profile.id）',
    template_name   VARCHAR(64)   NOT NULL COMMENT '模板名称，如「高蛋白增肌早餐」',
    meal_type       VARCHAR(32)   NOT NULL COMMENT '对应餐次：BREAKFAST / LUNCH / DINNER / SNACK',
    items_json      TEXT          NOT NULL COMMENT '套餐明细 JSON：[{"foodName":"生鸡胸肉","weightG":150}]',
    total_calories  INT           NOT NULL DEFAULT 0 COMMENT '合计热量（kcal，由 Java 依每 100g 字典折算后求和）',
    total_protein_g DECIMAL(7, 2) NOT NULL DEFAULT 0 COMMENT '合计蛋白质（g）',
    total_carbs_g   DECIMAL(7, 2) NOT NULL DEFAULT 0 COMMENT '合计碳水（g）',
    total_fat_g     DECIMAL(7, 2) NOT NULL DEFAULT 0 COMMENT '合计脂肪（g）',
    created_at      DATETIME      NULL COMMENT '创建时间（自动填充）',
    updated_at      DATETIME      NULL COMMENT '更新时间（自动填充）',
    PRIMARY KEY (id),
    KEY idx_meal_template_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='常吃饮食套餐模板表';

-- =====================================================================
-- (5) AI 工具调用可观测性  com.fitness.ai.observability
-- =====================================================================

DROP TABLE IF EXISTS tool_audit_log;
CREATE TABLE tool_audit_log
(
    id          BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    user_id     BIGINT       NULL COMMENT '工具归属用户（由 @ToolMemoryId 注入；无用户上下文的工具为空）',
    tool_class  VARCHAR(128) NOT NULL COMMENT '工具类全名',
    tool_name   VARCHAR(64)  NOT NULL COMMENT '工具方法名',
    args_json   TEXT         NULL COMMENT '入参 JSON（已剔除 @ToolMemoryId 注入的用户 ID 字段）',
    result_text TEXT         NULL COMMENT '返回值文本（截断至 4000 字符）',
    status      VARCHAR(32)  NOT NULL COMMENT '执行结果：SUCCESS / GUARDRAIL_BLOCKED / EXCEPTION',
    cost_ms     BIGINT       NOT NULL DEFAULT 0 COMMENT '执行耗时（毫秒）',
    created_at  DATETIME     NULL COMMENT '创建时间（自动填充）',
    PRIMARY KEY (id),
    KEY idx_tool_audit_log_user_id (user_id),
    KEY idx_tool_audit_log_tool_name (tool_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='AI 工具调用可观测性审计表';