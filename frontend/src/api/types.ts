/**
 * 后端契约类型定义 —— 与 backend `com.fitness.ai.*.{dto,vo,entity}` 一一对应。
 *
 * 命名约定：
 *  - 后端 `BigDecimal` → `number`；`LocalDate` → `'YYYY-MM-DD'`；`LocalDateTime` → ISO 字符串。
 *  - 统一响应体为 `Result<T>` = `{ code, message, data, timestamp }`，成功码为 200。
 */

// ====================================================================
// 通用
// ====================================================================

/** 后端统一响应体。 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

/**
 * 雪花 ID 类型：后端把 Java Long 统一序列化为 JSON 字符串，以避免 JavaScript
 * 53 位精度丢失（2^53-1 ≈ 9.007e15，而雪花 ID 量级约 2.1e18）。
 * 少数种子数据 id（1、2…）在请求体里仍可用 number，因此这里两种都接受；
 * 拼 URL 时请统一 `String(id)`，**不要**对它做 `Number()`（会重新丢精度）。
 */
export type Id = string | number

/** Java long（计数器 / 耗时）：同样以字符串返回。 */
export type LongValue = number | string

export type Gender = 'MALE' | 'FEMALE'
export type FitnessGoal = 'MUSCLE_GAIN' | 'FAT_LOSS' | 'STRENGTH'
export type EquipmentEnv = 'GYM' | 'HOME_DUMBBELL' | 'BODYWEIGHT'
export type InjuryStatus = 'ACTIVE' | 'RECOVERED'
export type InjurySeverity = 'MILD' | 'MODERATE' | 'SEVERE'
/** 与 exercise_dict.avoid_injury_parts 中真实出现的部位一一对应（共 5 个）。 */
export type BodyPart = 'LEFT_SHOULDER' | 'RIGHT_SHOULDER' | 'KNEE' | 'LOWER_BACK' | 'WRIST'
export type MuscleGroup = 'CHEST' | 'BACK' | 'LEGS' | 'SHOULDERS' | 'ARMS' | 'CORE'
export type SplitType = 'PPL' | 'UPPER_LOWER' | 'FULL_BODY'
export type MealType = 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK'
export type DayType = 'HIGH_CARB' | 'MEDIUM_CARB' | 'LOW_CARB'
export type ProgressionStatus = 'OVERLOAD_READY' | 'MAINTAIN' | 'DELOAD_NEEDED' | 'INSUFFICIENT_DATA'
export type AuditStatus = 'SUCCESS' | 'GUARDRAIL_BLOCKED' | 'EXCEPTION'
export type PlanStatus = 'ACTIVE' | 'ARCHIVED'
export type WorkoutTemplateCode = 'PPL_3DAY' | 'UPPER_LOWER_4DAY'

// ====================================================================
// Profile / 数字孪生
// ====================================================================

export interface UserInjuryTag {
  id: Id
  userId: Id
  bodyPart: BodyPart | string
  description: string | null
  severity: InjurySeverity
  status: InjuryStatus
  createdAt: string
  updatedAt: string
}

/** GET /api/profile/{userId} */
export interface UserDigitalTwinVO {
  userId: Id
  nickname: string | null
  gender: Gender
  age: number
  heightCm: number
  weightKg: number
  bodyFatPct: number | null
  bmr: number
  tdee: number
  activityLevel: number
  fitnessGoal: FitnessGoal
  equipmentEnv: EquipmentEnv
  createdAt: string
  updatedAt: string
  activeInjuries: UserInjuryTag[]
}

/** PUT /api/profile/{userId} —— 部分更新，传了的字段才生效。 */
export interface UserProfileUpdateDTO {
  nickname?: string
  gender?: Gender
  age?: number
  heightCm?: number
  weightKg?: number
  bodyFatPct?: number
  activityLevel?: number
  fitnessGoal?: FitnessGoal
  equipmentEnv?: EquipmentEnv
}

/** POST /api/profile/{userId}/injuries */
export interface InjuryTagCreateDTO {
  bodyPart: string
  description?: string
  severity?: InjurySeverity
}

// ====================================================================
// Workout / 训练
// ====================================================================

export interface ExerciseDict {
  id: Id
  /** null = 系统内置动作；非空 = 该用户自定义动作 */
  userId: Id | null
  name: string
  targetMuscle: MuscleGroup | string
  equipment: string
  difficulty: string
  avoidInjuryParts: string | null
  cues: string | null
  /** 前端派生标记：是否为用户自定义动作 */
  custom?: boolean
}

/** POST /api/workout/exercises —— 新建自定义动作。 */
export interface CustomExerciseCreateDTO {
  userId: Id
  name: string
  targetMuscle: MuscleGroup | string
  equipment?: string
  difficulty?: string
  /** 逗号分隔；可选值 LEFT_SHOULDER / RIGHT_SHOULDER / KNEE / LOWER_BACK / WRIST */
  avoidInjuryParts?: string | null
  cues?: string | null
}

/** DELETE /api/workout/exercises/{exerciseId} 的结果。 */
export interface CustomExerciseDeleteVO {
  exerciseId: Id
  name: string
  referencedPlanItems: number
  referencedLoggedCount?: number
  referencedLogs: number | string
  message: string
}

export interface WorkoutPlanItem {
  id: Id
  planId: Id
  userId: Id
  dayOfWeek: number
  exerciseId: Id
  exerciseName: string
  targetSets: number
  targetReps: number
  targetWeightKg: number | null
  targetRpe: number | null
  sortOrder: number | null
  notes: string | null
  createdAt: string
  updatedAt: string
}

export interface WorkoutPlanVO {
  id: Id
  userId: Id
  planName: string
  splitType: SplitType | string
  startDate: string
  endDate: string
  status: PlanStatus | string
  createdAt: string
  updatedAt: string
  items: WorkoutPlanItem[]
}

export interface WorkoutPlanItemDTO {
  dayOfWeek: number
  exerciseId: Id
  targetSets: number
  targetReps: number
  targetWeightKg?: number | null
  targetRpe?: number | null
  sortOrder?: number | null
  notes?: string | null
}

export interface WorkoutPlanCreateDTO {
  userId: Id
  planName: string
  splitType: SplitType | string
  startDate?: string
  endDate?: string
  items: WorkoutPlanItemDTO[]
}

export interface WorkoutLog {
  id: Id
  userId: Id
  planItemId: Id | null
  logDate: string
  exerciseId: Id | null
  exerciseName: string | null
  actualSets: number | null
  actualReps: number | null
  actualWeightKg: number | null
  actualRpe: number | null
  estimated1rm: number | null
  totalVolume: number | null
  feelingNotes: string | null
  createdAt: string
}

/** DELETE /api/workout/plans/items/{itemId} 的结果。 */
export interface PlanItemDeleteVO {
  itemId: Id
  exerciseName: string
  dayOfWeek: number | null
  dayLabel: string
  removedSets: number
  remainingItemsOnDay: number
  remainingSetsOnDay: number
  /** 该动作已产生的打卡记录条数：删除课表不会删训练历史 */
  preservedWorkoutLogs: number | string
  /** 删完之后整份计划是否已没有任何动作 */
  planEmptied: boolean
  message: string
}

export interface WorkoutLogCreateDTO {
  userId: Id
  planItemId?: Id | null
  logDate?: string
  exerciseId?: Id | null
  actualSets: number
  actualReps: number
  actualWeightKg: number
  actualRpe?: number | null
  feelingNotes?: string | null
}

// ====================================================================
// Nutrition / 营养
// ====================================================================

export interface FoodNutritionDict {
  id: Id
  foodName: string
  category: string
  caloriesPer100g: number
  proteinPer100g: number
  carbsPer100g: number
  fatPer100g: number
  unitHint: string | null
}

/** 饮食数据的来源标记：字典命中 / 用户自填 / AI 估算（已人工确认）。 */
export type DietSource = 'DICTIONARY' | 'CUSTOM' | 'AI_ESTIMATED'

export interface DietLog {
  id: Id
  userId: Id
  logDate: string
  mealType: MealType | string
  foodName: string
  weightG: number
  calories: number
  proteinG: number
  carbsG: number
  fatG: number
  /** 数据来源，用于在账单上区分实测字典值与估算值 */
  source: DietSource | string
  createdAt: string
}

/**
 * 饮食打卡请求：营养数值的三种提交方式（折算一律由后端 Java 完成）
 *  1. 只给 foodName + weightG → 命中字典按每 100g 折算
 *  2. 给 caloriesPer100g 等「每 100g」→ 字典未命中时按克重折算（自定义食物推荐）
 *  3. 给 calories 等「本条总量」→ 兼容显式估算
 */
export interface DietLogCreateDTO {
  userId: Id
  logDate?: string
  mealType: MealType | string
  foodName: string
  weightG: number
  calories?: number | null
  proteinG?: number | null
  carbsG?: number | null
  fatG?: number | null
  caloriesPer100g?: number | null
  proteinPer100g?: number | null
  carbsPer100g?: number | null
  fatPer100g?: number | null
  source?: DietSource | string | null
}

export interface MacroTargetGenerateDTO {
  userId: Id
  targetDate?: string
  dayType: DayType | string
}

export interface DailyMacroTarget {
  id: Id
  userId: Id
  targetDate: string
  dayType: DayType | string
  targetCalories: number
  targetProteinG: number
  targetCarbsG: number
  targetFatG: number
  createdAt: string
  updatedAt: string
}

/** GET /api/nutrition/summary */
export interface DailyNutritionSummaryVO {
  userId: Id
  date: string
  dayType: DayType | string | null
  targetCalories: number | null
  targetProteinG: number | null
  targetCarbsG: number | null
  targetFatG: number | null
  consumedCalories: number
  consumedProteinG: number
  consumedCarbsG: number
  consumedFatG: number
  remainingCalories: number | null
  remainingProteinG: number | null
  remainingCarbsG: number | null
  remainingFatG: number | null
  logs: DietLog[]
}

// ====================================================================
// Progression / 双渐进
// ====================================================================

export interface RecentLogBrief {
  logDate: string
  actualSets: number | null
  actualReps: number | null
  actualWeightKg: number | null
  actualRpe: number | null
  estimated1rm: number | null
}

/** GET /api/progression/recommendations/{userId} */
export interface ProgressionRecommendationVO {
  itemId: Id
  exerciseId: Id
  exerciseName: string
  targetMuscle: MuscleGroup | string | null
  currentTargetSets: number | null
  currentTargetReps: number | null
  currentTargetWeightKg: number | null
  /** 建议计算基准 = max(课表目标重量, 最近一次实际重量) */
  workingWeightKg: number | null
  status: ProgressionStatus
  suggestedWeightKg: number | null
  suggestedReps: number | null
  reason: string
  sampleSize: number
  recentLogs: RecentLogBrief[]
}

/** POST /api/progression/apply/{itemId} */
export interface ProgressionApplyVO {
  itemId: Id
  exerciseName: string
  status: ProgressionStatus
  previousWeightKg: number | null
  appliedWeightKg: number | null
  previousTargetReps: number | null
  appliedTargetReps: number | null
  message: string
}

// ====================================================================
// Catalog / 模板库
// ====================================================================

export interface MealTemplate {
  id: Id
  userId: Id
  templateName: string
  mealType: MealType | string
  itemsJson: string
  totalCalories: number
  totalProteinG: number
  totalCarbsG: number
  totalFatG: number
  createdAt: string
  updatedAt: string
}

/** 套餐内的一条食物明细；自定义食物可携带「每 100g 营养 + 来源」，打卡时无需再查字典。 */
export interface MealItemDTO {
  foodName: string
  weightG: number
  caloriesPer100g?: number | null
  proteinPer100g?: number | null
  carbsPer100g?: number | null
  fatPer100g?: number | null
  source?: DietSource | string | null
}

export interface MealTemplateCreateDTO {
  userId: Id
  templateName: string
  mealType: MealType | string
  items: MealItemDTO[]
}

export interface MealTemplateApplyVO {
  templateId: Id
  templateName: string
  date: string
  mealType: MealType | string
  loggedItems: number
  addedCalories: number
  summary: DailyNutritionSummaryVO
}

export interface WorkoutTemplateInfo {
  code: WorkoutTemplateCode | string
  name: string
  splitType: string
  slotCount: string
}

export interface WorkoutTemplateApplyDTO {
  userId: Id
  templateCode: WorkoutTemplateCode | string
}

export interface WorkoutTemplateApplyVO {
  planId: Id
  templateCode: string
  planName: string
  splitType: string
  itemCount: number
  generatedItems: string[]
  skippedSlots: string[]
}

// ====================================================================
// Dashboard / 看板
// ====================================================================

/** GET /api/dashboard/overview/{userId} */
export interface DashboardOverviewVO {
  weightKg: number
  bodyFatPct: number | null
  bmr: number
  tdee: number
  fitnessGoal: FitnessGoal
  consumedCaloriesToday: number
  targetCaloriesToday: number | null
  remainingCaloriesToday: number | null
  completedWorkoutsThisWeek: number
  plannedTrainingDaysThisWeek: number
  activeInjuries: number
  totalVolumeLast30Days: number
  workoutLogCountLast30Days: number
}

/** GET /api/dashboard/charts/strength-trend/{userId} */
export interface StrengthTrendVO {
  from: string
  to: string
  dates: string[]
  series: StrengthTrendSeries[]
}

export interface StrengthTrendSeries {
  exerciseName: string
  targetMuscle: MuscleGroup | string | null
  /** 与 dates 等长；当天无记录处为 null */
  estimated1rm: (number | null)[]
}

/** GET /api/dashboard/charts/muscle-volume/{userId} */
export interface MuscleVolumeVO {
  from: string
  to: string
  totalVolume: number
  totalSets: number
  muscles: MuscleVolumeStat[]
}

export interface MuscleVolumeStat {
  targetMuscle: MuscleGroup | string
  totalVolume: number
  totalSets: number
  logCount: number
  volumePercent: number
}

/** GET /api/dashboard/charts/nutrition-trend/{userId} */
export interface NutritionTrendVO {
  from: string
  to: string
  dates: string[]
  consumedCalories: number[]
  targetCalories: (number | null)[]
  consumedProteinG: number[]
  consumedCarbsG: number[]
  consumedFatG: number[]
  targetProteinG: (number | null)[]
  targetCarbsG: (number | null)[]
  targetFatG: (number | null)[]
}

/** POST /api/dashboard/demo-seed/{userId} */
export interface DemoSeedResultVO {
  userId: Id
  days: number
  from: string
  to: string
  planId: Id
  planName: string
  planItems: number
  workoutLogs: number
  dietLogs: number
  macroTargets: number
  note: string
}

// ====================================================================
// Observability / AOP 审计
// ====================================================================

export interface ToolAuditLog {
  id: Id
  userId: Id | null
  toolClass: string
  toolName: string
  argsJson: string | null
  resultText: string | null
  status: AuditStatus
  costMs: number | string | null
  createdAt: string
}

/** GET /api/observability/tool-logs */
export interface ToolAuditSummaryVO {
  userId: Id
  totalCalls: number | string
  guardrailBlocked: number
  exceptionCount: number
  avgCostMs: number | null
  maxCostMs: number | string | null
  limit: number
  logs: ToolAuditLog[]
}

// ====================================================================
// AI / 对话、RAG、结构化排课
// ====================================================================

/** POST /api/ai/chat */
export interface AiChatRequest {
  userId: Id
  message: string
}

export interface RagSearchHit {
  rank: number
  sectionTitle: string
  score: number
  bm25: number
  cosine: number
  keywordBonus: number
  matchedTerms: string[]
  content: string
}

/** GET /api/ai/rag/search */
export interface RagSearchResultVO {
  query: string
  totalSegments: number
  topK: number
  hits: RagSearchHit[]
}

export interface AiGeneratedPlanItemDTO {
  dayOfWeek: number
  exerciseId: Id
  exerciseName: string
  targetSets: number
  targetReps: number
  targetWeightKg: number | null
  targetRpe: number | null
  notes: string | null
}

export interface AiGeneratedPlanDTO {
  planName: string
  splitType: SplitType | string
  coachReasoning: string
  items: AiGeneratedPlanItemDTO[]
}

export interface AiPlanGenerateRequest {
  userId: Id
  requirement: string
}

/** POST /api/ai/plan/generate */
export interface AiPlanGenerationResultVO {
  plan: WorkoutPlanVO
  coachReasoning: string
  attempts: number
  retryHistory: string[]
  message: string
}

/** POST /api/ai/food-estimate —— AI 估算自定义食物营养（建议值，不落库）。 */
/** DELETE /api/nutrition/logs 的结果（清空某日饮食流水）。 */
export interface DietLogClearVO {
  userId: Id
  logDate: string
  /** 实际删除的流水条数 */
  deletedCount: number
  /** 被删除流水合计热量（kcal） */
  deletedCalories: number
  /** 清空后该日剩余已摄入热量 */
  consumedCaloriesAfter: number
  message: string
}

export interface FoodNutritionEstimateRequest {
  userId: Id
  foodName: string
  weightG?: number | null
  note?: string | null
}

export interface FoodNutritionEstimateVO {
  foodName: string
  caloriesPer100g: number
  proteinPer100g: number
  carbsPer100g: number
  fatPer100g: number
  /** AI 给出的估算依据 */
  basis: string
  /** HIGH / MEDIUM / LOW */
  confidence: string
  /** 后端对「4P+4C+9F 与热量是否自洽」的校验结论 */
  consistent: boolean
  warning: string | null
  elapsedMs: number
  disclaimer: string
}

/** SSE 事件名（GET /api/ai/chat/stream）。 */
export type AiStreamEvent = 'message' | 'reset' | 'done' | 'error'

/** 后端 Scheduled 任务结果（GET /api/observability 预留）。 */
export interface DailyMacroScheduleResultVO {
  date: string
  usersProcessed: number
  highCarbDays: number
  mediumCarbDays: number
  lowCarbDays: number
  failed: number
  details: string[]
}