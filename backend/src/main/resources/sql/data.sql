-- =====================================================================
-- Personal AI Fitness System - 初始字典与测试数据脚本
--
-- 执行方式（手动执行，需先执行 schema.sql）：
--   mysql -h 127.0.0.1 -P 3306 -u root -p < backend/src/main/resources/sql/data.sql
--
-- 说明：
--   1. 全部使用 INSERT IGNORE，可重复执行而不会重复插入或报错。
--   2. 仅包含「默认用户档案 + 1 条伤病记录 + 动作字典 + 食物字典」；
--      workout_plan / workout_plan_item / workout_log / daily_macro_target / diet_log
--      属于用户产生的业务流水，故意保持为空，由后续业务接口写入。
--   3. 显式指定 id（1 起），与运行期雪花 ID 不会冲突。
-- =====================================================================

USE fitness_ai;

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 默认用户档案（id = 1）
--    BMR  : Mifflin-St Jeor 男性公式 = 10 × 75 + 6.25 × 178 - 5 × 21 + 5 = 1762.5 → 1763 kcal
--    TDEE : BMR 1763 × 活动系数 1.55（中等活动） = 2732.65 → 2733 kcal
--    注：此处只是初始快照值，运行期一律由 Service 层确定性 Java 方法重算，禁止交给 LLM。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO user_profile (id, nickname, gender, age, height_cm, weight_kg, body_fat_pct, bmr, tdee,
                                 activity_level, fitness_goal, equipment_env, created_at, updated_at)
VALUES (1, '默认用户', 'MALE', 21, 178.00, 75.00, 16.00, 1763, 2733,
        1.55, 'MUSCLE_GAIN', 'GYM', NOW(), NOW());

-- ---------------------------------------------------------------------
-- 2. 初始伤病记录（左肩轻度不适）
--    ⚠️ 只在「该用户从未有过 LEFT_SHOULDER 记录」时插入。
--    如果写成裸 INSERT IGNORE ... VALUES (1, ...)，那么当 id=1 这一行已被删除（例如用户
--    把伤病标记为已恢复后又重建过记录）时，重复执行本脚本会凭空插入一条新的 ACTIVE 伤病，
--    导致「明明已恢复，重启后又有伤」。因此改用 INSERT ... SELECT ... WHERE NOT EXISTS。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO user_injury_tag (id, user_id, body_part, description, severity, status, created_at, updated_at)
SELECT 1, 1, 'LEFT_SHOULDER', '卧推与推举时左肩前侧有轻微不适，无活动受限，暂不建议大重量过头推举',
       'MILD', 'ACTIVE', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM user_injury_tag WHERE user_id = 1 AND body_part = 'LEFT_SHOULDER'
);

-- ---------------------------------------------------------------------
-- 3. 内置标准动作库（27 个，覆盖胸 / 背 / 腿 / 肩 / 手臂 / 核心）
--    avoid_injury_parts 逗号分隔，供 Agent 换动作时做安全过滤（空串代表无禁忌）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO exercise_dict (id, name, target_muscle, equipment, difficulty, avoid_injury_parts, cues)
VALUES
-- 胸
(1, '杠铃卧推', 'CHEST', 'BARBELL', 'INTERMEDIATE', 'LEFT_SHOULDER,RIGHT_SHOULDER,WRIST',
 '肩胛后缩下沉贴紧卧凳，杠铃下放至乳线附近，大臂与躯干约 75° 夹角，双脚踩实地面。'),
(2, '上斜哑铃卧推', 'CHEST', 'DUMBBELL', 'INTERMEDIATE', 'LEFT_SHOULDER,RIGHT_SHOULDER',
 '靠背调至约 30°，哑铃下放至锁骨下方，避免肘部过度外展，顶端不锁死肘关节。'),
(3, '哑铃飞鸟', 'CHEST', 'DUMBBELL', 'BEGINNER', 'LEFT_SHOULDER,RIGHT_SHOULDER',
 '肘关节保持微屈固定角度，用胸大肌收缩带动画弧，不要耸肩或用手臂甩动。'),
(4, '双杠臂屈伸', 'CHEST', 'BODYWEIGHT', 'ADVANCED', 'LEFT_SHOULDER,RIGHT_SHOULDER,WRIST',
 '身体前倾更多刺激下胸，下放至肩略低于肘即可，肩部出现刺痛立即停止。'),
(5, '器械推胸', 'CHEST', 'MACHINE', 'BEGINNER', '',
 '背部紧贴靠垫，握把推至手臂接近伸直但不锁死肘关节，全程控制离心 2 秒。'),
-- 背
(6, '传统硬拉', 'BACK', 'BARBELL', 'ADVANCED', 'LOWER_BACK,KNEE,WRIST',
 '杠铃贴近小腿，髋膝同时伸展，背部全程中立；切忌弓背启动或先抬臀后蹬腿。'),
(7, '引体向上', 'BACK', 'BODYWEIGHT', 'INTERMEDIATE', 'LEFT_SHOULDER,RIGHT_SHOULDER,WRIST',
 '先肩胛下沉再屈臂上拉，胸口靠向单杠，避免借助摆荡；力竭时用弹力带辅助。'),
(8, '高位下拉', 'BACK', 'CABLE', 'BEGINNER', 'LEFT_SHOULDER,RIGHT_SHOULDER',
 '躯干微后仰约 15°，横杆拉至锁骨上方，肘部向下向后，感受背阔肌发力。'),
(9, '杠铃划船', 'BACK', 'BARBELL', 'INTERMEDIATE', 'LOWER_BACK,WRIST',
 '髋部后移、躯干俯身约 45°，杠铃拉向下腹部，全程保持脊柱中立不驼背。'),
(10, '坐姿绳索划船', 'BACK', 'CABLE', 'BEGINNER', 'LOWER_BACK',
 '挺胸不后仰过度，肘部贴近身体向后拉，末端充分后缩肩胛并停顿 1 秒。'),
(11, '单臂哑铃划船', 'BACK', 'DUMBBELL', 'BEGINNER', 'LOWER_BACK',
 '单手单膝撑凳稳定躯干，肘部沿身体侧后方拉起，避免转动躯干借力。'),
-- 腿
(12, '杠铃深蹲', 'LEGS', 'BARBELL', 'INTERMEDIATE', 'KNEE,LOWER_BACK',
 '髋膝同步下蹲至大腿与地面平行，膝盖与脚尖同向不内扣，核心全程收紧。'),
(13, '保加利亚分腿蹲', 'LEGS', 'DUMBBELL', 'INTERMEDIATE', 'KNEE',
 '后脚搭在凳上，重心压在前脚脚跟与足中，躯干微前倾，下蹲至前腿大腿接近平行。'),
(14, '罗马尼亚硬拉', 'LEGS', 'BARBELL', 'INTERMEDIATE', 'LOWER_BACK',
 '屈髋不弓腰下放至腘绳肌明显拉伸，杠铃贴腿，起身用臀部发力而非腰部。'),
(15, '腿举', 'LEGS', 'MACHINE', 'BEGINNER', 'KNEE',
 '下放至膝关节约 90° 即可，避免臀部离开靠垫与膝盖完全锁死。'),
(16, '哑铃箭步蹲', 'LEGS', 'DUMBBELL', 'BEGINNER', 'KNEE',
 '前脚踩实、后膝下沉接近地面，躯干保持直立，前膝不超过脚尖过多。'),
(17, '站姿提踵', 'LEGS', 'MACHINE', 'BEGINNER', '',
 '前脚掌发力顶至最高点停顿 1 秒，再缓慢下放至小腿充分拉伸。'),
-- 肩
(18, '坐姿哑铃推举', 'SHOULDERS', 'DUMBBELL', 'INTERMEDIATE', 'LEFT_SHOULDER,RIGHT_SHOULDER',
 '背部贴紧靠垫，哑铃推至头顶上方内收，避免挺腰代偿；肩部不适可改用中立握法。'),
(19, '哑铃侧平举', 'SHOULDERS', 'DUMBBELL', 'BEGINNER', 'LEFT_SHOULDER,RIGHT_SHOULDER',
 '肘部微屈固定，抬至与肩同高即止，用肩中束带动，全程避免耸肩。'),
(20, '绳索面拉', 'SHOULDERS', 'CABLE', 'BEGINNER', 'LEFT_SHOULDER,RIGHT_SHOULDER',
 '绳索拉向面部同时外旋，肘高于手腕，强化三角肌后束与肩袖，适合肩部康复期降负荷使用。'),
(21, '俯身反向飞鸟', 'SHOULDERS', 'DUMBBELL', 'BEGINNER', 'LEFT_SHOULDER,RIGHT_SHOULDER',
 '俯身固定躯干，肘微屈向两侧展开至与肩同高，感受后束收缩，不要用斜方肌耸肩。'),
-- 手臂
(22, '杠铃弯举', 'ARMS', 'BARBELL', 'BEGINNER', 'WRIST',
 '大臂夹紧身体两侧固定，仅做屈肘动作，避免甩腰借力；手腕疼痛可改用曲杆。'),
(23, '绳索下压', 'ARMS', 'CABLE', 'BEGINNER', 'WRIST',
 '大臂贴身固定，仅做肘关节伸展至手臂伸直，末端挤压肱三头肌 1 秒。'),
(24, '锤式哑铃弯举', 'ARMS', 'DUMBBELL', 'BEGINNER', 'WRIST',
 '掌心相对的中立握法，离心下放控制 2 秒，对肘关节更友好。'),
-- 核心
(25, '平板支撑', 'CORE', 'BODYWEIGHT', 'BEGINNER', '',
 '肘关节位于肩正下方，收腹夹臀，身体保持一条直线，切勿塌腰或抬臀。'),
(26, '悬垂举腿', 'CORE', 'BODYWEIGHT', 'ADVANCED', 'LOWER_BACK,LEFT_SHOULDER,RIGHT_SHOULDER',
 '先骨盆后倾再卷起双腿，控制离心不摆荡；握力或肩部不适时改用罗马椅举腿。'),
(27, '卷腹', 'CORE', 'BODYWEIGHT', 'BEGINNER', 'LOWER_BACK',
 '下背贴地，用腹直肌卷起上背而非用手拉脖子，呼气时收紧顶点停顿。');

-- ---------------------------------------------------------------------
-- 4. 内置常见食物库（27 种，营养数据按每 100g 计；标注「估算」的为食堂/外食折算值）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO food_nutrition_dict (id, food_name, category, calories_per_100g, protein_per_100g, carbs_per_100g,
                                        fat_per_100g, unit_hint)
VALUES
-- 蛋白质类
(1, '生鸡胸肉', 'PROTEIN', 120, 22.50, 0.00, 2.60, '1 块中等鸡胸约 200g'),
(2, '瘦牛肉（生）', 'PROTEIN', 143, 20.20, 2.00, 6.20, '1 份约 150g'),
(3, '三文鱼（生）', 'PROTEIN', 208, 20.40, 0.00, 13.40, '1 份约 150g'),
(4, '白灼虾', 'PROTEIN', 99, 20.90, 0.00, 1.10, '1 只中等虾约 15g'),
(5, '鸡蛋（全蛋）', 'PROTEIN', 143, 12.60, 0.70, 9.50, '1 个全蛋约 50g'),
(6, '蛋清', 'PROTEIN', 52, 11.00, 0.70, 0.20, '1 个蛋清约 33g'),
(7, '乳清蛋白粉', 'PROTEIN', 400, 80.00, 8.00, 5.00, '1 勺约 30g，约 24g 蛋白质'),
(8, '北豆腐', 'PROTEIN', 76, 8.10, 1.90, 3.70, '1 块约 200g'),
(9, '脱脂牛奶', 'PROTEIN', 35, 3.40, 5.00, 0.10, '1 盒约 250ml'),
(10, '无糖豆浆', 'PROTEIN', 31, 3.00, 1.20, 1.60, '1 杯约 300ml'),
-- 碳水类
(11, '熟米饭', 'CARB', 116, 2.60, 25.90, 0.30, '1 小碗约 150g'),
(12, '燕麦片（干）', 'CARB', 389, 16.90, 66.30, 6.90, '1 勺约 30g'),
(13, '全麦面包', 'CARB', 247, 13.00, 41.00, 3.40, '1 片约 35g'),
(14, '紫薯（生）', 'CARB', 82, 1.60, 19.00, 0.20, '1 个中等紫薯约 150g'),
(15, '红薯（生）', 'CARB', 86, 1.60, 20.10, 0.10, '1 个中等红薯约 200g'),
(16, '土豆（生）', 'CARB', 77, 2.00, 17.50, 0.10, '1 个中等土豆约 150g'),
(17, '鲜玉米', 'CARB', 112, 4.00, 22.80, 1.20, '1 根约 200g 可食部分'),
(18, '糙米（干）', 'CARB', 370, 7.90, 77.20, 2.90, '1 份约 60g，煮熟约 180g'),
-- 脂肪类
(19, '牛油果', 'FAT', 160, 2.00, 8.50, 14.70, '1 个中等牛油果约 200g'),
(20, '杏仁', 'FAT', 579, 21.20, 21.60, 49.90, '1 小把约 20g'),
(21, '花生酱', 'FAT', 588, 25.10, 20.00, 50.40, '1 勺约 15g'),
-- 果蔬类
(22, '西兰花', 'VEG_FRUIT', 34, 2.80, 6.60, 0.40, '1 份约 200g'),
(23, '菠菜', 'VEG_FRUIT', 23, 2.90, 3.60, 0.40, '1 份约 200g'),
(24, '香蕉', 'VEG_FRUIT', 89, 1.10, 22.80, 0.30, '1 根中等香蕉约 120g'),
(25, '蓝莓', 'VEG_FRUIT', 57, 0.70, 14.50, 0.30, '1 盒约 125g'),
-- 外食估算值
(26, '食堂炒青菜（估算）', 'VEG_FRUIT', 90, 2.00, 5.00, 7.00, '1 份约 200g，含食用油估算'),
(27, '食堂番茄炒蛋（估算）', 'PROTEIN', 120, 6.00, 5.50, 8.50, '1 份约 200g，含食用油估算');

-- ---------------------------------------------------------------------
-- 5. 默认用户的常吃套餐模板（2 个）
--    合计值按「每 100g 数据 × 克重 / 100」逐项折算后求和，热量四舍五入取整、营养素保留 2 位小数
--    模板 1：燕麦片 60g + 鸡蛋 100g + 脱脂牛奶 250g = 464 kcal / P31.24 / C52.98 / F13.89
--    模板 2：生鸡胸肉 150g + 熟米饭 300g + 西兰花 150g = 579 kcal / P45.75 / C87.60 / F5.40
-- ---------------------------------------------------------------------
INSERT IGNORE INTO meal_template (id, user_id, template_name, meal_type, items_json, total_calories,
                                  total_protein_g, total_carbs_g, total_fat_g, created_at, updated_at)
VALUES (1, 1, '经典燕麦鸡蛋早餐', 'BREAKFAST',
        '[{"foodName":"燕麦片（干）","weightG":60},{"foodName":"鸡蛋（全蛋）","weightG":100},{"foodName":"脱脂牛奶","weightG":250}]',
        464, 31.24, 52.98, 13.89, NOW(), NOW()),
       (2, 1, '练后鸡胸肉米饭正餐', 'LUNCH',
        '[{"foodName":"生鸡胸肉","weightG":150},{"foodName":"熟米饭","weightG":300},{"foodName":"西兰花","weightG":150}]',
        579, 45.75, 87.60, 5.40, NOW(), NOW());