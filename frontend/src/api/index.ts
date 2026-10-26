import { del, get, patch, post, put } from './client'
import type {
  Id,
  CustomExerciseCreateDTO,
  CustomExerciseDeleteVO,
  AiChatRequest,
  AiPlanGenerateRequest,
  AiPlanGenerationResultVO,
  DashboardOverviewVO,
  DailyMacroScheduleResultVO,
  DailyNutritionSummaryVO,
  DemoSeedResultVO,
  DietLog,
  DietLogCreateDTO,
  ExerciseDict,
  FoodNutritionDict,
  FoodNutritionEstimateRequest,
  FoodNutritionEstimateVO,
  InjuryTagCreateDTO,
  MacroTargetGenerateDTO,
  MealTemplate,
  MealTemplateApplyVO,
  MealTemplateCreateDTO,
  MuscleVolumeVO,
  NutritionTrendVO,
  PlanItemDeleteVO,
  ProgressionApplyVO,
  ProgressionRecommendationVO,
  RagSearchResultVO,
  StrengthTrendVO,
  ToolAuditSummaryVO,
  UserDigitalTwinVO,
  UserInjuryTag,
  UserProfileUpdateDTO,
  WorkoutLog,
  WorkoutLogCreateDTO,
  WorkoutPlanCreateDTO,
  WorkoutPlanItem,
  WorkoutPlanItemDTO,
  WorkoutPlanVO,
  WorkoutTemplateApplyDTO,
  WorkoutTemplateApplyVO,
  WorkoutTemplateInfo,
} from './types'

/** 数字孪生与伤病（/api/profile）。 */
export const profileApi = {
  getTwin: (userId: Id) => get<UserDigitalTwinVO>(`/profile/${userId}`),
  /** 部分更新；身高/体重/年龄/性别/活动系数变更后后端会自动重算 BMR 与 TDEE。 */
  update: (userId: Id, dto: UserProfileUpdateDTO) => put<UserDigitalTwinVO>(`/profile/${userId}`, dto),
  addInjury: (userId: Id, dto: InjuryTagCreateDTO) => post<UserInjuryTag>(`/profile/${userId}/injuries`, dto),
  /** 注意：后端此接口的状态是 @RequestParam，不是请求体。 */
  setInjuryStatus: (injuryId: Id, status: 'ACTIVE' | 'RECOVERED') =>
    patch<UserInjuryTag>(`/profile/injuries/${injuryId}/status`, undefined, { status }),
}

/** 数据看板（/api/dashboard）。 */
export const dashboardApi = {
  overview: (userId: Id) => get<DashboardOverviewVO>(`/dashboard/overview/${userId}`),
  strengthTrend: (userId: Id, days?: number) =>
    get<StrengthTrendVO>(`/dashboard/charts/strength-trend/${userId}`, { days }),
  muscleVolume: (userId: Id, days?: number) =>
    get<MuscleVolumeVO>(`/dashboard/charts/muscle-volume/${userId}`, { days }),
  nutritionTrend: (userId: Id, days?: number) =>
    get<NutritionTrendVO>(`/dashboard/charts/nutrition-trend/${userId}`, { days }),
  /** 幂等：先清空该用户旧业务流水，再重建 14 天演示数据。 */
  demoSeed: (userId: Id) => post<DemoSeedResultVO>(`/dashboard/demo-seed/${userId}`),
}

/** 双渐进引擎（/api/progression）。 */
export const progressionApi = {
  recommendations: (userId: Id) =>
    get<ProgressionRecommendationVO[]>(`/progression/recommendations/${userId}`),
  apply: (itemId: Id) => post<ProgressionApplyVO>(`/progression/apply/${itemId}`),
}

/** 训练（/api/workout）。 */
export const workoutApi = {
  safeExercises: (userId: Id, targetMuscle?: string, equipment?: string) =>
    get<ExerciseDict[]>('/workout/exercises/safe', { userId, targetMuscle, equipment }),
  /** 全量动作字典（不做伤病过滤）：系统内置 + 本人自定义，供「换动作 / 自定义排课」使用。 */
  allExercises: (userId: Id, targetMuscle?: string, equipment?: string) =>
    get<ExerciseDict[]>('/workout/exercises', { userId, targetMuscle, equipment }),
  /** 新建自定义动作（自己起名字）。 */
  createCustomExercise: (dto: CustomExerciseCreateDTO) => post<ExerciseDict>('/workout/exercises', dto),
  /** 删除自定义动作：内置不可删、仅能删自己的、被课表或打卡引用时不可删。 */
  deleteCustomExercise: (exerciseId: Id, userId: Id) =>
    del<CustomExerciseDeleteVO>(`/workout/exercises/${exerciseId}`, { userId }),
  activePlan: (userId: Id) => get<WorkoutPlanVO>('/workout/plans/active', { userId }),
  createPlan: (dto: WorkoutPlanCreateDTO) => post<WorkoutPlanVO>('/workout/plans', dto),
  /** 修改明细：后端要求提交完整的 WorkoutPlanItemDTO（dayOfWeek/exerciseId/targetSets/targetReps 必填）。 */
  updatePlanItem: (itemId: Id, dto: WorkoutPlanItemDTO) =>
    put<WorkoutPlanItem>(`/workout/plans/items/${itemId}`, dto),
  /** 删除明细：只允许删 ACTIVE 计划里的动作；已产生的打卡历史会保留。 */
  deletePlanItem: (itemId: Id) => del<PlanItemDeleteVO>(`/workout/plans/items/${itemId}`),
  logWorkout: (dto: WorkoutLogCreateDTO) => post<WorkoutLog>('/workout/logs', dto),
  logs: (userId: Id, days = 7) => get<WorkoutLog[]>('/workout/logs', { userId, days }),
}

/** 营养（/api/nutrition）。 */
export const nutritionApi = {
  foods: (keyword?: string) => get<FoodNutritionDict[]>('/nutrition/foods', { keyword }),
  generateTarget: (dto: MacroTargetGenerateDTO) =>
    post<DailyNutritionSummaryVO>('/nutrition/targets', dto),
  addLog: (dto: DietLogCreateDTO) => post<DietLog>('/nutrition/logs', dto),
  summary: (userId: Id, date?: string) =>
    get<DailyNutritionSummaryVO>('/nutrition/summary', { userId, date }),
}

/** 模板库（/api/catalog）。 */
export const catalogApi = {
  mealTemplates: (userId: Id) => get<MealTemplate[]>('/catalog/meal-templates', { userId }),
  createMealTemplate: (dto: MealTemplateCreateDTO) => post<MealTemplate>('/catalog/meal-templates', dto),
  applyMealTemplate: (templateId: Id, date?: string, mealType?: string) =>
    post<MealTemplateApplyVO>(`/catalog/meal-templates/${templateId}/apply`, undefined, { date, mealType }),
  workoutTemplates: () => get<WorkoutTemplateInfo[]>('/catalog/workout-templates'),
  applyWorkoutTemplate: (dto: WorkoutTemplateApplyDTO) =>
    post<WorkoutTemplateApplyVO>('/catalog/workout-templates/apply', dto),
}

/** AOP 审计（/api/observability）。 */
export const observabilityApi = {
  toolLogs: (userId: Id, limit = 50) =>
    get<ToolAuditSummaryVO>('/observability/tool-logs', { userId, limit }),
}

/** AI 能力（/api/ai）。 */
export const aiApi = {
  chat: (dto: AiChatRequest) => post<string>('/ai/chat', dto),
  clearMemory: (userId: Id) => del<string>(`/ai/chat/memory/${userId}`),
  ragSearch: (query: string, topK = 3) => get<RagSearchResultVO>('/ai/rag/search', { query, topK }),
  generatePlan: (dto: AiPlanGenerateRequest) => post<AiPlanGenerationResultVO>('/ai/plan/generate', dto),
  /** AI 估算自定义食物营养：只返回建议值填表，不落库；用户确认后才走打卡接口。 */
  estimateFood: (dto: FoodNutritionEstimateRequest) =>
    post<FoodNutritionEstimateVO>('/ai/food-estimate', dto),
}

/** 供「每日调度」等后台任务结果展示预留。 */
export type { DailyMacroScheduleResultVO }

export const api = {
  profile: profileApi,
  dashboard: dashboardApi,
  progression: progressionApi,
  workout: workoutApi,
  nutrition: nutritionApi,
  catalog: catalogApi,
  observability: observabilityApi,
  ai: aiApi,
}