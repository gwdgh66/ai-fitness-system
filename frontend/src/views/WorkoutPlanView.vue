<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  AlertTriangle,
  CalendarDays,
  CheckCircle2,
  ClipboardList,
  Dumbbell,
  Loader2,
  Pencil,
  RefreshCw,
  ShieldAlert,
  Sparkles,
  Trash2,
  Wand2,
  Wrench,
  Zap,
} from 'lucide-vue-next'
import AppModal from '@/components/AppModal.vue'
import BentoTile from '@/components/BentoTile.vue'
import CustomExerciseDialog from '@/components/CustomExerciseDialog.vue'
import PlanBuilder from '@/components/PlanBuilder.vue'
import StatusChip from '@/components/StatusChip.vue'
import { aiApi, catalogApi, workoutApi } from '@/api'
import { ApiError, toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { bodyPartLabel, muscleLabel } from '@/theme/tokens'
import { formatInt, formatWeight, weekdayLabel } from '@/utils/format'
import type {
  AiPlanGenerationResultVO,
  ExerciseDict,
  Id,
  WorkoutLog,
  WorkoutPlanItem,
  WorkoutPlanItemDTO,
  WorkoutPlanVO,
  WorkoutTemplateInfo,
} from '@/api/types'

const state = appStore.state

// ---- 页面数据 ----
const plan = ref<WorkoutPlanVO | null>(null)
const planMissing = ref(false)
const templates = ref<WorkoutTemplateInfo[]>([])
const logs = ref<WorkoutLog[]>([])
const loading = ref(true)
const loadError = ref('')

// ---- 排课控制台 ----
const applyingTemplate = ref<string | null>(null)
const aiPanelOpen = ref(false)
const aiRequirement = ref('排一套每周 4 练的上下肢分化增肌计划，重点照顾我的左肩伤病，组数控制在安全范围内')
const generating = ref(false)
const aiResult = ref<AiPlanGenerationResultVO | null>(null)

// ---- 模式三：完全自定义排课 ----
const builderOpen = ref(false)

/** 打开自定义排课前先确保动作库已加载（安全库 + 全量库）。 */
async function openBuilder(): Promise<void> {
  await loadExercises()
  builderOpen.value = true
}

// ---- 日切换 ----
const selectedDay = ref(1)

// ---- 编辑（换动作 / 改计划）----
const editOpen = ref(false)
const editTarget = ref<WorkoutPlanItem | null>(null)
const editForm = ref<{ dayOfWeek: number; exerciseId: Id; targetSets: number; targetReps: number; targetWeightKg: number; targetRpe: number; notes: string }>({
  dayOfWeek: 1,
  exerciseId: 0,
  targetSets: 3,
  targetReps: 8,
  targetWeightKg: 0,
  targetRpe: 8,
  notes: '',
})
const safeExercises = ref<ExerciseDict[]>([])
const allExercises = ref<ExerciseDict[]>([])
const showForbidden = ref(false)
const exercisesLoading = ref(false)
const saving = ref(false)
const editError = ref('')

// ---- 打卡 ----
const logOpen = ref(false)
const logTarget = ref<WorkoutPlanItem | null>(null)
const logForm = ref({ actualSets: 4, actualReps: 8, actualWeightKg: 60, actualRpe: 8, feelingNotes: '' })
const logging = ref(false)
const logResult = ref<WorkoutLog | null>(null)
const logError = ref('')

/** 等长收缩动作：次数应显示为「秒」。 */
const ISOMETRIC_KEYWORDS = ['平板支撑', '侧桥', '静蹲']

function isIsometric(name: string | null | undefined): boolean {
  if (!name) return false
  return ISOMETRIC_KEYWORDS.some((keyword) => name.includes(keyword))
}

function repUnit(item: { exerciseName?: string | null }): string {
  return isIsometric(item.exerciseName) ? '秒' : '次'
}

const days = [1, 2, 3, 4, 5, 6, 7]

const itemsByDay = computed(() => {
  const map = new Map<number, WorkoutPlanItem[]>()
  for (const day of days) {
    map.set(
      day,
      (plan.value?.items ?? [])
        .filter((item) => item.dayOfWeek === day)
        .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)),
    )
  }
  return map
})

function setsOfDay(day: number): number {
  return (itemsByDay.value.get(day) ?? []).reduce((sum, item) => sum + (item.targetSets ?? 0), 0)
}

const currentItems = computed(() => itemsByDay.value.get(selectedDay.value) ?? [])

// ---- 编辑弹窗里的护栏预览：改完后的当日总组数 ----
const editedDaySets = computed(() => {
  if (!editTarget.value) return 0
  const others = (itemsByDay.value.get(editTarget.value.dayOfWeek) ?? [])
    .filter((item) => item.id !== editTarget.value?.id)
    .reduce((sum, item) => sum + (item.targetSets ?? 0), 0)
  return others + Number(editForm.value.targetSets || 0)
})

const exerciseOptions = computed(() => (showForbidden.value ? allExercises.value : safeExercises.value))

function conflictParts(exercise: ExerciseDict | undefined): string[] {
  if (!exercise?.avoidInjuryParts) return []
  const active = new Set((state.twin?.activeInjuries ?? []).map((item) => item.bodyPart))
  return exercise.avoidInjuryParts
    .split(',')
    .map((part) => part.trim().toUpperCase())
    .filter((part) => part.length > 0 && active.has(part))
}

function selectedExercise(): ExerciseDict | undefined {
  // 注意：id 是雪花 ID（可能超过 2^53），必须按字符串比较，不能用 Number() 转换
  return exerciseOptions.value.find((item) => String(item.id) === String(editForm.value.exerciseId))
}

// ------------------------------------------------------------------
// 数据加载
// ------------------------------------------------------------------

async function loadPlan(): Promise<void> {
  try {
    plan.value = await workoutApi.activePlan(state.userId)
    planMissing.value = false
  } catch (error) {
    // 没有激活课表属于正常状态，不当作错误
    plan.value = null
    planMissing.value = true
    if (!(error instanceof ApiError)) {
      loadError.value = toErrorMessage(error)
    }
  }
}

async function loadAll(): Promise<void> {
  loading.value = true
  loadError.value = ''
  const results = await Promise.allSettled([
    loadPlan(),
    workoutApi.logs(state.userId, 7).then((value) => (logs.value = value)),
    catalogApi.workoutTemplates().then((value) => (templates.value = value)),
  ])
  const failures = results.filter((item) => item.status === 'rejected')
  if (failures.length > 0) {
    loadError.value = toErrorMessage((failures[0] as PromiseRejectedResult).reason)
  }
  // 默认落在今天；今天没有安排则落到第一个有安排的日子
  const today = new Date().getDay() === 0 ? 7 : new Date().getDay()
  if ((itemsByDay.value.get(today) ?? []).length > 0) {
    selectedDay.value = today
  } else {
    const firstWithItems = days.find((day) => (itemsByDay.value.get(day) ?? []).length > 0)
    selectedDay.value = firstWithItems ?? today
  }
  loading.value = false
}

onMounted(loadAll)
watch(() => state.refreshToken, loadAll)

// ------------------------------------------------------------------
// 排课：模板套用 + AI 定制
// ------------------------------------------------------------------

async function applyTemplate(code: string): Promise<void> {
  if (applyingTemplate.value) return
  applyingTemplate.value = code
  try {
    const result = await catalogApi.applyWorkoutTemplate({ userId: state.userId, templateCode: code })
    const skipped = result.skippedSlots.length > 0 ? `，跳过 ${result.skippedSlots.length} 个伤病冲突槽位` : ''
    appStore.pushToast('success', `已套用「${result.planName}」`, `生成 ${result.itemCount} 个动作${skipped}`)
    if (result.skippedSlots.length > 0) {
      result.skippedSlots.forEach((slot) => appStore.pushToast('info', '因伤病跳过槽位', slot))
    }
    await loadAll()
  } catch (error) {
    appStore.pushToast('error', '模板套用失败', toErrorMessage(error))
  } finally {
    applyingTemplate.value = null
  }
}

async function generateAiPlan(): Promise<void> {
  if (generating.value || aiRequirement.value.trim().length === 0) return
  generating.value = true
  aiResult.value = null
  try {
    const result = await aiApi.generatePlan({ userId: state.userId, requirement: aiRequirement.value.trim() })
    aiResult.value = result
    appStore.pushToast('success', 'AI 排课已落库并激活', result.message)
    await loadAll()
  } catch (error) {
    appStore.pushToast('error', 'AI 排课失败', toErrorMessage(error))
  } finally {
    generating.value = false
  }
}

// ------------------------------------------------------------------
// 编辑 / 替换动作
// ------------------------------------------------------------------

async function loadExercises(force = false): Promise<void> {
  if (exercisesLoading.value) return
  if (!force && safeExercises.value.length > 0) return
  exercisesLoading.value = true
  try {
    const [safe, all] = await Promise.all([
      workoutApi.safeExercises(state.userId),
      workoutApi.allExercises(state.userId),
    ])
    safeExercises.value = safe
    allExercises.value = all
  } catch (error) {
    appStore.pushToast('error', '动作库加载失败', toErrorMessage(error))
  } finally {
    exercisesLoading.value = false
  }
}

// ---- 自定义动作（自己起名字）----
const customDialogOpen = ref(false)

/** 新建自定义动作后：刷新动作库并直接选中它（不在安全库里就自动打开「显示全部」）。 */
async function onCustomCreated(exercise: ExerciseDict): Promise<void> {
  customDialogOpen.value = false
  await loadExercises(true)
  editForm.value.exerciseId = exercise.id
  const inSafe = safeExercises.value.some((row) => String(row.id) === String(exercise.id))
  if (!inSafe) {
    showForbidden.value = true
    appStore.pushToast('info', `「${exercise.name}」不在当前安全动作库中`,
      '它可能与你声明了禁忌的部位相关；已自动切换为「显示全部动作」以便你选择')
  }
}

async function openEdit(item: WorkoutPlanItem): Promise<void> {
  editTarget.value = item
  editForm.value = {
    dayOfWeek: item.dayOfWeek,
    exerciseId: item.exerciseId,
    targetSets: item.targetSets ?? 3,
    targetReps: item.targetReps ?? 8,
    targetWeightKg: item.targetWeightKg ?? 0,
    targetRpe: item.targetRpe ?? 8,
    notes: item.notes ?? '',
  }
  editError.value = ''
  showForbidden.value = false
  editOpen.value = true
  await loadExercises()
}

async function submitEdit(): Promise<void> {
  if (!editTarget.value || saving.value) return
  saving.value = true
  editError.value = ''
  const dto: WorkoutPlanItemDTO = {
    dayOfWeek: Number(editForm.value.dayOfWeek),
    // 雪花 ID 原样回传（字符串），交给后端精确解析为 Long
    exerciseId: editForm.value.exerciseId,
    targetSets: Number(editForm.value.targetSets),
    targetReps: Number(editForm.value.targetReps),
    targetWeightKg: Number(editForm.value.targetWeightKg),
    targetRpe: Number(editForm.value.targetRpe),
    sortOrder: editTarget.value.sortOrder ?? 1,
    notes: editForm.value.notes || null,
  }
  try {
    const updated = await workoutApi.updatePlanItem(editTarget.value.id, dto)
    appStore.pushToast(
      'success',
      `已更新「${updated.exerciseName}」`,
      `${updated.targetSets} 组 × ${updated.targetReps} ${repUnit(updated)} × ${formatWeight(updated.targetWeightKg)}kg · RPE ${updated.targetRpe}`,
    )
    editOpen.value = false
    await loadAll()
  } catch (error) {
    // 重点：把后端 Java 护栏的拦截原文完整展示给使用者
    const message = toErrorMessage(error)
    editError.value = message
    appStore.pushToast('error', '护栏拦截：修改未落库', message)
  } finally {
    saving.value = false
  }
}

// ------------------------------------------------------------------
// 打卡
// ------------------------------------------------------------------

function openLog(item: WorkoutPlanItem): void {
  logTarget.value = item
  logForm.value = {
    actualSets: item.targetSets ?? 3,
    actualReps: item.targetReps ?? 8,
    actualWeightKg: item.targetWeightKg ?? 0,
    actualRpe: item.targetRpe ?? 8,
    feelingNotes: '',
  }
  logResult.value = null
  logError.value = ''
  logOpen.value = true
}

async function submitLog(): Promise<void> {
  if (!logTarget.value || logging.value) return
  logging.value = true
  logError.value = ''
  try {
    const result = await workoutApi.logWorkout({
      userId: state.userId,
      planItemId: logTarget.value.id,
      logDate: new Date().toISOString().slice(0, 10),
      exerciseId: logTarget.value.exerciseId,
      actualSets: Number(logForm.value.actualSets),
      actualReps: Number(logForm.value.actualReps),
      actualWeightKg: Number(logForm.value.actualWeightKg),
      actualRpe: Number(logForm.value.actualRpe),
      feelingNotes: logForm.value.feelingNotes || null,
    })
    logResult.value = result
    appStore.pushToast(
      'success',
      `已记录「${result.exerciseName}」打卡`,
      `估算 1RM ${formatWeight(result.estimated1rm)}kg · 总容量 ${formatWeight(result.totalVolume)}kg（Epley 由后端计算）`,
    )
    await loadAll()
  } catch (error) {
    const message = toErrorMessage(error)
    logError.value = message
    appStore.pushToast('error', '打卡被拦截', message)
  } finally {
    logging.value = false
  }
}

/**
 * 删除当前课表里的这个动作（只影响课表，历史打卡记录不会被删）。
 */
const deleteOpen = ref(false)
const deleteTarget = ref<WorkoutPlanItem | null>(null)
const deleting = ref(false)

function openDelete(item: WorkoutPlanItem): void {
  deleteTarget.value = item
  deleteOpen.value = true
}

async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value || deleting.value) return
  deleting.value = true
  try {
    const result = await workoutApi.deletePlanItem(deleteTarget.value.id)
    appStore.pushToast('success', `已删除「${result.exerciseName}」`, result.message)
    deleteOpen.value = false
    deleteTarget.value = null
    await loadAll()
  } catch (error) {
    appStore.pushToast('error', '删除失败', toErrorMessage(error))
  } finally {
    deleting.value = false
  }
}

const totalLoggedVolume = computed(() =>
  logs.value.reduce((sum, item) => sum + (item.totalVolume ?? 0), 0),
)
</script>

<template>
  <div class="space-y-3">
    <!-- 排课控制台 -->
    <BentoTile title="排课控制台" hint="模板套用（毫秒级确定性）· AI 结构化排课（带护栏纠错重试）· 完全自定义（多重量组）">
      <template #action>
        <StatusChip v-if="plan" tone="acid">{{ plan.planName }}</StatusChip>
        <StatusChip v-else tone="muted">暂无激活课表</StatusChip>
      </template>

      <div class="grid gap-4 lg:grid-cols-3">
        <!-- 模式一：经典模板 -->
        <div class="rounded-chip border border-hairline bg-obsidian/40 p-4">
          <p class="mb-1 flex items-center gap-1.5 text-xs font-medium text-ink-primary">
            <Zap :size="13" :stroke-width="2.2" class="text-acid" aria-hidden="true" />
            经典模板套用
          </p>
          <p class="mb-3 text-[11px] leading-relaxed text-ink-muted">
            纯 Java 排课：自动从动作字典挑选避开当前活跃伤病的代表动作，毫秒级生成并激活周课表。
          </p>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="template in templates"
              :key="template.code"
              type="button"
              class="chip cursor-pointer border-acid/35 bg-acid-dim text-acid transition duration-200 ease-kinetic hover:border-acid/60 disabled:opacity-50"
              :disabled="applyingTemplate !== null"
              @click="applyTemplate(template.code)"
            >
              <Loader2 v-if="applyingTemplate === template.code" :size="12" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
              <Dumbbell v-else :size="12" :stroke-width="2.2" aria-hidden="true" />
              {{ template.name }}
            </button>
            <span v-if="templates.length === 0" class="text-xs text-ink-muted">模板清单加载中…</span>
          </div>
        </div>

        <!-- 模式二：AI 定制 -->
        <div class="rounded-chip border border-hairline bg-obsidian/40 p-4">
          <div class="flex items-start justify-between gap-2">
            <div>
              <p class="mb-1 flex items-center gap-1.5 text-xs font-medium text-ink-primary">
                <Sparkles :size="13" :stroke-width="2.2" class="text-acid" aria-hidden="true" />
                AI 定制结构化排课
              </p>
              <p class="text-[11px] leading-relaxed text-ink-muted">
                一句话诉求 → LLM 结合数字孪生 + 安全动作白名单 + 知识库摘要生成 JSON → Java 护栏校验 → 违规自动带错重试。
              </p>
            </div>
            <button
              type="button"
              class="chip shrink-0 cursor-pointer border-hairline text-ink-secondary transition hover:text-ink-primary"
              :aria-expanded="aiPanelOpen"
              @click="aiPanelOpen = !aiPanelOpen"
            >
              {{ aiPanelOpen ? '收起' : '展开' }}
            </button>
          </div>

          <div v-if="aiPanelOpen" class="mt-3 space-y-2">
            <label class="field-label" for="ai-requirement">排课诉求</label>
            <textarea
              id="ai-requirement"
              v-model="aiRequirement"
              class="field-input min-h-[76px] resize-none text-xs"
              placeholder="例如：排一套每周 4 练的上下肢分化增肌计划，重点照顾我的左肩伤病"
            />
            <button
              type="button"
              class="btn-primary !min-h-[40px] w-full text-xs"
              :disabled="generating || aiRequirement.trim().length === 0"
              @click="generateAiPlan"
            >
              <Loader2 v-if="generating" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
              <Wand2 v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
              {{ generating ? 'LLM 生成中（可能触发护栏重试）…' : '生成并激活新周课表' }}
            </button>
          </div>
        </div>

        <!-- 模式三：完全自定义（多重量组） -->
        <div class="rounded-chip border border-hairline bg-obsidian/40 p-4">
          <p class="mb-1 flex items-center gap-1.5 text-xs font-medium text-ink-primary">
            <Wrench :size="13" :stroke-width="2.2" class="text-acid" aria-hidden="true" />
            完全自定义
          </p>
          <p class="mb-3 text-[11px] leading-relaxed text-ink-muted">
            自己决定每天练什么。同一个动作可以挂多个<b class="text-ink-secondary">重量组</b>
            （例如 80kg×1组 / 90kg×2组 / 100kg×3组），保存后每个重量组都能单独打卡、单独看 1RM。
          </p>
          <button type="button" class="btn-primary !min-h-[38px] w-full text-xs" @click="openBuilder">
            <Wrench :size="13" :stroke-width="2.2" aria-hidden="true" />
            打开自定义排课
          </button>
        </div>
      </div>

      <!-- AI 排课理由 -->
      <div v-if="aiResult" class="mt-4 rounded-chip border border-acid/30 bg-acid-dim/40 p-4">
        <div class="mb-2 flex flex-wrap items-center gap-2">
          <StatusChip tone="acid">第 {{ aiResult.attempts }} 次尝试通过护栏</StatusChip>
          <StatusChip tone="muted">{{ aiResult.plan.planName }} · {{ aiResult.plan.splitType }}</StatusChip>
          <StatusChip tone="muted">{{ aiResult.plan.items.length }} 个动作</StatusChip>
        </div>
        <p class="text-xs font-medium text-ink-primary">AI 排课理由与注意事项</p>
        <p class="mt-1.5 whitespace-pre-wrap text-xs leading-relaxed text-ink-secondary">{{ aiResult.coachReasoning }}</p>
        <ul v-if="aiResult.retryHistory.length > 0" class="mt-3 space-y-1">
          <li v-for="(entry, index) in aiResult.retryHistory" :key="index" class="flex items-start gap-1.5 text-[11px] text-signal-orange">
            <ShieldAlert :size="11" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
            {{ entry }}
          </li>
        </ul>
      </div>
    </BentoTile>

    <div
      v-if="loadError"
      class="flex items-center gap-2 rounded-chip border border-signal-coral/40 bg-signal-coral/10 px-3.5 py-2.5 text-xs text-signal-coral"
      role="alert"
    >
      <AlertTriangle :size="14" :stroke-width="2.2" aria-hidden="true" />
      {{ loadError }}
      <button type="button" class="ml-auto cursor-pointer underline" @click="loadAll">重试</button>
    </div>

    <!-- 日切换 -->
    <BentoTile title="周课表明细" :hint="plan ? `${plan.planName} · ${plan.startDate} ~ ${plan.endDate}` : '每天最多 30 组（后端强制）'">
      <template #action>
        <button type="button" class="chip cursor-pointer border-hairline text-ink-secondary transition hover:text-ink-primary" @click="loadAll">
          <RefreshCw :size="12" :stroke-width="2.2" aria-hidden="true" />
          刷新
        </button>
      </template>

      <div v-if="loading" class="space-y-2">
        <div v-for="index in 3" :key="index" class="skeleton h-20 w-full" />
      </div>

      <div v-else-if="planMissing" class="flex h-[200px] flex-col items-center justify-center gap-2 text-center">
        <CalendarDays :size="22" :stroke-width="1.8" class="text-ink-muted" aria-hidden="true" />
        <p class="text-sm text-ink-secondary">当前没有激活的周课表</p>
        <p class="text-xs text-ink-muted">用上面的「经典模板套用」或「AI 定制排课」立即生成一套</p>
      </div>

      <template v-else>
        <!-- 周一到周日胶囊 -->
        <div class="mb-4 flex flex-wrap gap-2">
          <button
            v-for="day in days"
            :key="day"
            type="button"
            :class="[
              'chip cursor-pointer transition duration-200 ease-kinetic',
              selectedDay === day ? 'border-acid/50 bg-acid-dim text-acid' : 'border-hairline text-ink-secondary hover:text-ink-primary',
            ]"
            :aria-pressed="selectedDay === day"
            @click="selectedDay = day"
          >
            {{ weekdayLabel(day) }}
            <span class="tnum text-ink-muted">{{ (itemsByDay.get(day) ?? []).length }} 个</span>
            <span
              v-if="setsOfDay(day) > 0"
              :class="['tnum', setsOfDay(day) > 30 ? 'text-signal-coral' : 'text-ink-muted']"
            >
              {{ setsOfDay(day) }} 组
            </span>
          </button>
        </div>

        <!-- 当日动作 Bento 卡 -->
        <div v-if="currentItems.length === 0" class="flex h-[140px] items-center justify-center text-sm text-ink-muted">
          {{ weekdayLabel(selectedDay) }} 是休息日，没有安排动作
        </div>
        <div v-else class="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <article
            v-for="item in currentItems"
            :key="item.id"
            class="rounded-chip border border-hairline bg-obsidian/40 p-3.5 transition duration-200 ease-kinetic hover:border-hairline-strong"
          >
            <div class="flex items-start justify-between gap-2">
              <div class="min-w-0">
                <p class="truncate text-sm font-medium text-ink-primary">{{ item.exerciseName }}</p>
                <p class="mt-0.5 flex items-center gap-1.5 text-[11px] text-ink-muted">
                  <span>#{{ item.sortOrder ?? '-' }}</span>
                  <span v-if="item.notes" class="truncate">· {{ item.notes }}</span>
                </p>
              </div>
              <StatusChip v-if="isIsometric(item.exerciseName)" tone="cyan">等长收缩</StatusChip>
            </div>

            <div class="mt-3 flex items-baseline gap-3">
              <p class="flex items-baseline gap-1">
                <span class="readout text-2xl font-semibold text-ink-primary">{{ item.targetSets }}</span>
                <span class="text-[11px] text-ink-muted">组</span>
              </p>
              <p class="flex items-baseline gap-1">
                <span class="readout text-2xl font-semibold text-ink-primary">{{ item.targetReps }}</span>
                <span class="text-[11px] text-ink-muted">{{ repUnit(item) }}</span>
              </p>
              <p class="flex items-baseline gap-1">
                <span class="readout text-2xl font-semibold text-ink-primary">{{ formatWeight(item.targetWeightKg) }}</span>
                <span class="text-[11px] text-ink-muted">kg</span>
              </p>
              <StatusChip v-if="item.targetRpe" tone="orange">RPE {{ item.targetRpe }}</StatusChip>
            </div>

            <div class="mt-3 flex gap-2">
              <button type="button" class="btn-ghost flex-1 !min-h-[38px] text-xs" @click="openEdit(item)">
                <Pencil :size="12" :stroke-width="2.2" aria-hidden="true" />
                换动作 / 改计划
              </button>
              <button type="button" class="btn-primary !min-h-[38px] flex-1 text-xs" @click="openLog(item)">
                <CheckCircle2 :size="12" :stroke-width="2.2" aria-hidden="true" />
                完成打卡
              </button>
              <button
                type="button"
                class="btn-ghost !min-h-[38px] !px-3 text-xs text-signal-coral"
                :aria-label="`从课表中删除「${item.exerciseName}」`"
                title="从课表中删除该动作"
                @click="openDelete(item)"
              >
                <Trash2 :size="13" :stroke-width="2.2" aria-hidden="true" />
              </button>
            </div>
          </article>
        </div>
      </template>
    </BentoTile>

    <!-- 近期打卡流水 -->
    <BentoTile title="近期训练打卡流水" hint="近 7 天 · 1RM 与容量均由后端 Epley 公式确定性计算">
      <template #action>
        <StatusChip tone="orange">累计容量 {{ formatWeight(totalLoggedVolume) }} kg</StatusChip>
      </template>

      <div v-if="loading" class="skeleton h-24 w-full" />
      <div v-else-if="logs.length === 0" class="flex h-[100px] items-center justify-center gap-2 text-sm text-ink-muted">
        <ClipboardList :size="16" :stroke-width="1.8" aria-hidden="true" />
        近 7 天还没有打卡记录
      </div>
      <div v-else class="overflow-x-auto">
        <table class="w-full min-w-[720px] text-left text-xs">
          <thead class="text-ink-muted">
            <tr class="border-b border-hairline">
              <th class="py-2 pr-3 font-medium">日期</th>
              <th class="py-2 pr-3 font-medium">动作</th>
              <th class="py-2 pr-3 font-medium">实际组 × 次 × 重量</th>
              <th class="py-2 pr-3 font-medium">RPE</th>
              <th class="py-2 pr-3 font-medium">估算 1RM</th>
              <th class="py-2 font-medium">总容量</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in logs" :key="row.id" class="border-b border-hairline/60 last:border-0">
              <td class="tnum py-2 pr-3 text-ink-secondary">{{ row.logDate }}</td>
              <td class="py-2 pr-3 text-ink-primary">{{ row.exerciseName }}</td>
              <td class="tnum py-2 pr-3 text-ink-secondary">
                {{ row.actualSets }} × {{ row.actualReps }} × {{ formatWeight(row.actualWeightKg) }}kg
              </td>
              <td class="tnum py-2 pr-3 text-ink-secondary">{{ row.actualRpe ?? '—' }}</td>
              <td class="tnum py-2 pr-3 text-acid">{{ formatWeight(row.estimated1rm) }} kg</td>
              <td class="tnum py-2 text-signal-orange">{{ formatInt(row.totalVolume) }} kg</td>
            </tr>
          </tbody>
        </table>
      </div>
    </BentoTile>

    <!-- 换动作 / 改计划弹窗 -->
    <AppModal
      :open="editOpen"
      title="换动作 / 改计划"
      hint="保存时会重新触发后端伤病冲突与训练量护栏校验"
      width="max-w-2xl"
      @close="editOpen = false"
    >
      <div class="space-y-4">
        <div class="flex flex-wrap items-center gap-2">
          <StatusChip tone="muted">原动作：{{ editTarget?.exerciseName }}</StatusChip>
          <StatusChip tone="muted">{{ weekdayLabel(editForm.dayOfWeek) }}</StatusChip>
        </div>

        <div>
          <div class="mb-1.5 flex items-center justify-between gap-2">
            <label class="field-label !mb-0" for="edit-exercise">选择动作</label>
            <label class="flex cursor-pointer items-center gap-1.5 text-[11px] text-ink-muted">
              <input v-model="showForbidden" type="checkbox" class="accent-[#CCFF00]" />
              显示全部动作（含当前伤病禁忌，用于验证护栏）
            </label>
          </div>
          <!-- 不要用 v-model.number：雪花 ID 转成 number 会丢精度导致后端找不到动作 -->
          <select id="edit-exercise" v-model="editForm.exerciseId" class="field-input">
            <option v-for="exercise in exerciseOptions" :key="String(exercise.id)" :value="exercise.id">
              {{ exercise.name }} · {{ muscleLabel[exercise.targetMuscle] ?? exercise.targetMuscle }}
              <template v-if="exercise.userId !== null && exercise.userId !== undefined">[自定义]</template>
              <template v-if="conflictParts(exercise).length > 0">
                [禁忌] {{ conflictParts(exercise).map((part) => bodyPartLabel[part] ?? part).join('/') }}
              </template>
            </option>
          </select>
          <button
            type="button"
            class="mt-1.5 flex cursor-pointer items-center gap-1 text-[11px] text-ink-muted transition hover:text-acid"
            @click="customDialogOpen = true"
          >
            <Wrench :size="11" :stroke-width="2.2" aria-hidden="true" />
            列表里没有？自己起名字新建动作
          </button>
          <p v-if="conflictParts(selectedExercise()).length > 0" class="mt-1.5 flex items-start gap-1.5 text-[11px] text-signal-coral">
            <ShieldAlert :size="11" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
            该动作与你的 ACTIVE 伤病（{{ conflictParts(selectedExercise()).map((part) => bodyPartLabel[part] ?? part).join('、') }}）冲突，
            提交后应被后端护栏拦截 —— 这正是可以实测护栏的入口。
          </p>
          <p v-else-if="showForbidden" class="mt-1.5 text-[11px] text-ink-muted">
            已显示全部 {{ allExercises.length }} 个动作（安全 {{ safeExercises.length }} 个）。
          </p>
        </div>

        <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div>
            <label class="field-label" for="edit-sets">组数（1~15）</label>
            <input id="edit-sets" v-model.number="editForm.targetSets" class="field-input" type="number" min="1" max="15" />
          </div>
          <div>
            <label class="field-label" for="edit-reps">次数（1~100）</label>
            <input id="edit-reps" v-model.number="editForm.targetReps" class="field-input" type="number" min="1" max="100" />
          </div>
          <div>
            <label class="field-label" for="edit-weight">重量 kg</label>
            <input id="edit-weight" v-model.number="editForm.targetWeightKg" class="field-input" type="number" min="0" step="0.5" />
          </div>
          <div>
            <label class="field-label" for="edit-rpe">目标 RPE</label>
            <input id="edit-rpe" v-model.number="editForm.targetRpe" class="field-input" type="number" min="1" max="10" step="0.5" />
          </div>
        </div>

        <div>
          <label class="field-label" for="edit-notes">备注</label>
          <input id="edit-notes" v-model="editForm.notes" class="field-input" type="text" placeholder="例如：肩胛后缩下沉，离心 3 秒" />
        </div>

        <!-- 单日组数预览：>30 时珊瑚红预警 -->
        <div
          :class="[
            'flex items-center justify-between gap-2 rounded-chip border px-3.5 py-2.5 text-xs',
            editedDaySets > 30 ? 'border-signal-coral/45 bg-signal-coral/10 text-signal-coral' : 'border-hairline text-ink-secondary',
          ]"
        >
          <span class="flex items-center gap-1.5">
            <AlertTriangle v-if="editedDaySets > 30" :size="13" :stroke-width="2.2" aria-hidden="true" />
            {{ weekdayLabel(editForm.dayOfWeek) }}修改后单日总组数
          </span>
          <span class="tnum font-semibold">{{ editedDaySets }} 组{{ editedDaySets > 30 ? '（超过 30 组上限，会被后端拦截）' : '' }}</span>
        </div>

        <div v-if="editError" class="rounded-chip border border-signal-coral/50 bg-signal-coral/10 p-3.5" role="alert">
          <p class="flex items-center gap-1.5 text-xs font-medium text-signal-coral">
            <ShieldAlert :size="13" :stroke-width="2.2" aria-hidden="true" />
            后端护栏拦截（改动未落库）
          </p>
          <p class="mt-1.5 whitespace-pre-wrap text-[11px] leading-relaxed text-signal-coral/90">{{ editError }}</p>
        </div>
      </div>

      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <button type="button" class="btn-ghost !min-h-[40px] !px-4 text-xs" @click="editOpen = false">取消</button>
          <button type="button" class="btn-primary !min-h-[40px] !px-4 text-xs" :disabled="saving || exercisesLoading" @click="submitEdit">
            <Loader2 v-if="saving" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
            {{ saving ? '保存中…' : '保存修改' }}
          </button>
        </div>
      </template>
    </AppModal>

    <!-- 打卡弹窗 -->
    <AppModal
      :open="logOpen"
      title="完成打卡"
      :hint="logTarget ? `${logTarget.exerciseName} · ${weekdayLabel(logTarget.dayOfWeek)}` : ''"
      @close="logOpen = false"
    >
      <div class="space-y-4">
        <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div>
            <label class="field-label" for="log-sets">实际组数</label>
            <input id="log-sets" v-model.number="logForm.actualSets" class="field-input" type="number" min="1" max="15" />
          </div>
          <div>
            <label class="field-label" for="log-reps">
              实际{{ logTarget && isIsometric(logTarget.exerciseName) ? '秒数' : '次数' }}
            </label>
            <input id="log-reps" v-model.number="logForm.actualReps" class="field-input" type="number" min="1" max="100" />
          </div>
          <div>
            <label class="field-label" for="log-weight">实际重量 kg</label>
            <input id="log-weight" v-model.number="logForm.actualWeightKg" class="field-input" type="number" min="0" step="0.5" />
          </div>
          <div>
            <label class="field-label" for="log-rpe">实际 RPE（1~10）</label>
            <input id="log-rpe" v-model.number="logForm.actualRpe" class="field-input" type="number" min="1" max="10" step="0.5" />
          </div>
        </div>

        <div>
          <label class="field-label" for="log-notes">体感备注</label>
          <input id="log-notes" v-model="logForm.feelingNotes" class="field-input" type="text" placeholder="例如：最后一组 RPE 8.5，肩部无不适" />
        </div>

        <div v-if="logResult" class="rounded-chip border border-acid/35 bg-acid-dim/40 p-4">
          <p class="flex items-center gap-1.5 text-xs font-medium text-acid">
            <CheckCircle2 :size="13" :stroke-width="2.2" aria-hidden="true" />
            已落库（Epley 公式由后端计算）
          </p>
          <div class="mt-3 grid grid-cols-2 gap-3">
            <div>
              <p class="text-[11px] text-ink-muted">本次估算 1RM</p>
              <p class="flex items-baseline gap-1">
                <span class="readout text-3xl font-semibold text-acid">{{ formatWeight(logResult.estimated1rm) }}</span>
                <span class="text-xs text-ink-secondary">kg</span>
              </p>
            </div>
            <div>
              <p class="text-[11px] text-ink-muted">本次总容量</p>
              <p class="flex items-baseline gap-1">
                <span class="readout text-3xl font-semibold text-signal-orange">{{ formatInt(logResult.totalVolume) }}</span>
                <span class="text-xs text-ink-secondary">kg</span>
              </p>
            </div>
          </div>
          <p class="mt-2 text-[11px] text-ink-muted">
            {{ logResult.actualSets }} 组 × {{ logResult.actualReps }} {{ repUnit(logResult) }} ×
            {{ formatWeight(logResult.actualWeightKg) }}kg · {{ logResult.logDate }}
          </p>
        </div>

        <div v-if="logError" class="rounded-chip border border-signal-coral/50 bg-signal-coral/10 p-3.5" role="alert">
          <p class="flex items-center gap-1.5 text-xs font-medium text-signal-coral">
            <ShieldAlert :size="13" :stroke-width="2.2" aria-hidden="true" />
            打卡被护栏拦截
          </p>
          <p class="mt-1.5 whitespace-pre-wrap text-[11px] leading-relaxed text-signal-coral/90">{{ logError }}</p>
        </div>
      </div>

      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <button type="button" class="btn-ghost !min-h-[40px] !px-4 text-xs" @click="logOpen = false">关闭</button>
          <button type="button" class="btn-primary !min-h-[40px] !px-4 text-xs" :disabled="logging" @click="submitLog">
            <Loader2 v-if="logging" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
            {{ logging ? '写入中…' : '写入打卡记录' }}
          </button>
        </div>
      </template>
    </AppModal>

    <!-- 删除动作确认弹窗 -->
    <AppModal
      :open="deleteOpen"
      title="从课表中删除动作"
      hint="只影响课表安排，不会删除已经产生的训练打卡记录"
      width="max-w-md"
      @close="deleteOpen = false"
    >
      <div v-if="deleteTarget" class="space-y-4">
        <div class="rounded-chip border border-signal-coral/45 bg-signal-coral/10 p-3.5">
          <p class="flex items-center gap-1.5 text-xs font-medium text-signal-coral">
            <ShieldAlert :size="13" :stroke-width="2.2" aria-hidden="true" />
            即将删除「{{ deleteTarget.exerciseName }}」
          </p>
          <p class="mt-1.5 text-[11px] leading-relaxed text-signal-coral/90">
            {{ weekdayLabel(deleteTarget.dayOfWeek) }} · {{ deleteTarget.targetSets }} 组 ×
            {{ deleteTarget.targetReps }} {{ repUnit(deleteTarget) }} × {{ formatWeight(deleteTarget.targetWeightKg) }}kg
          </p>
        </div>

        <ul class="space-y-2 text-xs text-ink-secondary">
          <li class="flex items-start gap-2">
            <CheckCircle2 :size="13" :stroke-width="2.2" class="mt-0.5 shrink-0 text-acid" aria-hidden="true" />
            该动作已产生的打卡记录会保留（训练历史不可篡改，1RM 与容量曲线照常可查）
          </li>
          <li class="flex items-start gap-2">
            <CheckCircle2 :size="13" :stroke-width="2.2" class="mt-0.5 shrink-0 text-acid" aria-hidden="true" />
            删除后 {{ weekdayLabel(deleteTarget.dayOfWeek) }} 剩余动作会自动重排顺序（不留断号）
          </li>
          <li class="flex items-start gap-2">
            <AlertTriangle :size="13" :stroke-width="2.2" class="mt-0.5 shrink-0 text-signal-orange" aria-hidden="true" />
            删除后当日总组数将变为
            <span class="tnum text-ink-primary">
              {{ setsOfDay(deleteTarget.dayOfWeek) - (deleteTarget.targetSets ?? 0) }} 组
            </span>
            （{{ weekdayLabel(deleteTarget.dayOfWeek) }} 原为 {{ setsOfDay(deleteTarget.dayOfWeek) }} 组）
          </li>
        </ul>
      </div>

      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <button type="button" class="btn-ghost !min-h-[40px] !px-4 text-xs" @click="deleteOpen = false">取消</button>
          <button type="button" class="btn-danger-outline !min-h-[40px] !px-4 text-xs" :disabled="deleting" @click="confirmDelete">
            <Loader2 v-if="deleting" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
            <Trash2 v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
            {{ deleting ? '删除中…' : '确认删除' }}
          </button>
        </div>
      </template>
    </AppModal>
  <!-- 自定义周课表（多重量组） -->
    <PlanBuilder
      :open="builderOpen"
      :safe-exercises="safeExercises"
      :all-exercises="allExercises"
      :current-plan="plan"
      :active-injury-parts="(state.twin?.activeInjuries ?? []).map((injury) => injury.bodyPart)"
      @close="builderOpen = false"
      @saved="loadAll"
    />

    <!-- 自定义动作（自己起名字） -->
    <CustomExerciseDialog
      :open="customDialogOpen"
      :user-id="state.userId"
      :active-injury-parts="(state.twin?.activeInjuries ?? []).map((injury) => injury.bodyPart)"
      @close="customDialogOpen = false"
      @created="onCustomCreated"
    />
  </div>
</template>