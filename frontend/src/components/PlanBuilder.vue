<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  AlertTriangle,
  Download,
  Dumbbell,
  Loader2,
  Plus,
  Save,
  ShieldAlert,
  Trash2,
  Wrench,
} from 'lucide-vue-next'
import AppModal from '@/components/AppModal.vue'
import CustomExerciseDialog from '@/components/CustomExerciseDialog.vue'
import StatusChip from '@/components/StatusChip.vue'
import { workoutApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { bodyPartLabel, muscleLabel } from '@/theme/tokens'
import { formatInt, toIsoDate, weekdayLabel } from '@/utils/format'
import type { ExerciseDict, Id, SplitType, WorkoutPlanCreateDTO, WorkoutPlanItemDTO, WorkoutPlanVO } from '@/api/types'

const props = defineProps<{
  open: boolean
  /** 安全动作库（已过滤 ACTIVE 伤病） */
  safeExercises: ExerciseDict[]
  /** 全量动作库（含禁忌，用于主动踩护栏/记录历史动作） */
  allExercises: ExerciseDict[]
  /** 当前激活课表：用于「载入当前课表」继续编辑 */
  currentPlan: WorkoutPlanVO | null
  activeInjuryParts: string[]
}>()

const emit = defineEmits<{ (event: 'close'): void; (event: 'saved'): void }>()

const MAX_SETS_PER_DAY = 30
const MAX_SETS_PER_ITEM = 15

interface BuilderGroup {
  key: number
  weightKg: number
  sets: number
  reps: number
  rpe: number
}

interface BuilderExercise {
  key: number
  exerciseId: Id
  exerciseName: string
  notes: string
  groups: BuilderGroup[]
}

interface BuilderDay {
  dayOfWeek: number
  exercises: BuilderExercise[]
}

let seq = 0

function newGroup(): BuilderGroup {
  return { key: ++seq, weightKg: 40, sets: 3, reps: 8, rpe: 8 }
}

function newExercise(exercise?: ExerciseDict): BuilderExercise {
  return {
    key: ++seq,
    exerciseId: exercise ? exercise.id : 0,
    exerciseName: exercise ? exercise.name : '',
    notes: '',
    groups: [newGroup()],
  }
}

function emptyDays(): BuilderDay[] {
  return [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({ dayOfWeek, exercises: [] }))
}

const days = ref<BuilderDay[]>(emptyDays())
const activeDay = ref(1)
const planName = ref('我的自定义周计划')
const splitType = ref<SplitType>('FULL_BODY')
const startDate = ref(toIsoDate(new Date()))
const endDate = ref(toIsoDate(new Date(Date.now() + 8 * 7 * 24 * 3600 * 1000)))
const showForbidden = ref(false)
const saving = ref(false)
const formError = ref('')

const exerciseOptions = computed(() => {
  const merged = [...(showForbidden.value ? props.allExercises : props.safeExercises), ...localCustom.value]
  const seen = new Set<string>()
  return merged.filter((row) => {
    const key = String(row.id)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
})

// ---- 自定义动作（自己起名字）----
const customDialogOpen = ref(false)
/** 哪个动作行发起了「新建自定义动作」 */
const customForExerciseKey = ref<number | null>(null)
/** 本次会话内新建的动作，立即并入下拉选项（无需等父组件刷新） */
const localCustom = ref<ExerciseDict[]>([])

function openCustomDialog(exercise: BuilderExercise): void {
  customForExerciseKey.value = exercise.key
  customDialogOpen.value = true
}

function onCustomCreated(exercise: ExerciseDict): void {
  localCustom.value.push({ ...exercise, custom: true })
  const target = customForExerciseKey.value
  const row = target === null
    ? null
    : days.value.flatMap((day) => day.exercises).find((item) => item.key === target)
  if (row) {
    row.exerciseId = exercise.id
    row.exerciseName = exercise.name
  }
  customForExerciseKey.value = null
}

/** 所有可查禁忌的动作来源（含刚新建的） */
function allKnownExercises(): ExerciseDict[] {
  return [...props.allExercises, ...localCustom.value]
}
const currentDay = computed(() => days.value.find((day) => day.dayOfWeek === activeDay.value) ?? days.value[0])

watch(
  () => props.open,
  (open) => {
    if (open) {
      formError.value = ''
      activeDay.value = 1
      days.value = emptyDays()
      planName.value = '我的自定义周计划'
      splitType.value = 'FULL_BODY'
      startDate.value = toIsoDate(new Date())
      endDate.value = toIsoDate(new Date(Date.now() + 8 * 7 * 24 * 3600 * 1000))
    }
  },
)

// ------------------------------------------------------------------
// 编辑操作
// ------------------------------------------------------------------

function setsOfDay(day: BuilderDay): number {
  return day.exercises.reduce(
    (sum, exercise) => sum + exercise.groups.reduce((groupSum, group) => groupSum + Number(group.sets || 0), 0),
    0,
  )
}

const totalItems = computed(() =>
  days.value.reduce((sum, day) => sum + day.exercises.reduce((n, ex) => n + ex.groups.length, 0), 0),
)

const overLimitDays = computed(() => days.value.filter((day) => setsOfDay(day) > MAX_SETS_PER_DAY))

function addExercise(): void {
  currentDay.value.exercises.push(newExercise())
}

function removeExercise(index: number): void {
  currentDay.value.exercises.splice(index, 1)
}

function addGroup(exercise: BuilderExercise): void {
  const last = exercise.groups[exercise.groups.length - 1]
  exercise.groups.push({ key: ++seq, weightKg: last ? last.weightKg : 40, sets: last ? last.sets : 3, reps: last ? last.reps : 8, rpe: last ? last.rpe : 8 })
}

function removeGroup(exercise: BuilderExercise, index: number): void {
  exercise.groups.splice(index, 1)
  if (exercise.groups.length === 0) {
    exercise.groups.push(newGroup())
  }
}

function onExerciseChange(exercise: BuilderExercise): void {
  const found = exerciseOptions.value.find((item) => String(item.id) === String(exercise.exerciseId))
  exercise.exerciseName = found ? found.name : ''
}

/** 载入当前课表：同一动作的多条明细会合并成一个动作 + 多个重量组。 */
function loadCurrentPlan(): void {
  const plan = props.currentPlan
  if (!plan) {
    appStore.pushToast('info', '当前没有激活课表', '先用模板或 AI 排课生成，或直接在这里从零自建')
    return
  }
  const built = emptyDays()
  const byKey = new Map<string, BuilderExercise>()
  const sorted = [...plan.items].sort(
    (a, b) => (a.dayOfWeek - b.dayOfWeek) || ((a.sortOrder ?? 0) - (b.sortOrder ?? 0)),
  )
  for (const item of sorted) {
    const day = built.find((row) => row.dayOfWeek === item.dayOfWeek)
    if (!day) continue
    const groupKey = `${item.dayOfWeek}-${String(item.exerciseId)}-${item.notes ?? ''}`
    let exercise = byKey.get(groupKey)
    if (!exercise) {
      exercise = newExercise()
      exercise.exerciseId = item.exerciseId
      exercise.exerciseName = item.exerciseName
      exercise.notes = item.notes ?? ''
      exercise.groups = []
      byKey.set(groupKey, exercise)
      day.exercises.push(exercise)
    }
    exercise.groups.push({
      key: ++seq,
      weightKg: item.targetWeightKg ?? 0,
      sets: item.targetSets ?? 3,
      reps: item.targetReps ?? 8,
      rpe: item.targetRpe ?? 8,
    })
  }
  days.value = built
  planName.value = plan.planName
  splitType.value = (plan.splitType as SplitType) ?? 'FULL_BODY'
  startDate.value = plan.startDate ?? startDate.value
  endDate.value = plan.endDate ?? endDate.value
  appStore.pushToast(
    'info',
    `已载入「${plan.planName}」`,
    `共 ${plan.items.length} 条明细已按「动作 + 重量组」合并，可直接改完再保存`,
  )
}

function clearAll(): void {
  days.value = emptyDays()
  planName.value = '我的自定义周计划'
  formError.value = ''
}

// ------------------------------------------------------------------
// 保存
// ------------------------------------------------------------------

function conflictParts(exerciseId: Id): string[] {
  const found = allKnownExercises().find((item) => String(item.id) === String(exerciseId))
  if (!found?.avoidInjuryParts) return []
  const active = new Set(props.activeInjuryParts.map((part) => part.toUpperCase()))
  return found.avoidInjuryParts
    .split(',')
    .map((part) => part.trim().toUpperCase())
    .filter((part) => part.length > 0 && active.has(part))
}

function validate(): boolean {
  formError.value = ''
  if (planName.value.trim().length === 0) {
    formError.value = '请填写计划名称'
    return false
  }
  if (totalItems.value === 0) {
    formError.value = '至少给某一天安排一个动作'
    return false
  }
  for (const day of days.value) {
    for (const exercise of day.exercises) {
      if (!exercise.exerciseId || exercise.exerciseId === 0) {
        formError.value = `${weekdayLabel(day.dayOfWeek)} 有一个动作还没选择，请先选动作或删除该行`
        return false
      }
      for (const group of exercise.groups) {
        if (!Number(group.sets) || Number(group.sets) < 1 || Number(group.sets) > MAX_SETS_PER_ITEM) {
          formError.value = `${exercise.exerciseName} 的组数必须在 1~${MAX_SETS_PER_ITEM} 之间`
          return false
        }
        if (!Number(group.reps) || Number(group.reps) < 1 || Number(group.reps) > 100) {
          formError.value = `${exercise.exerciseName} 的次数必须在 1~100 之间`
          return false
        }
        if (Number(group.rpe) < 1 || Number(group.rpe) > 10) {
          formError.value = `${exercise.exerciseName} 的 RPE 必须在 1~10 之间`
          return false
        }
        if (Number(group.weightKg) < 0) {
          formError.value = `${exercise.exerciseName} 的重量不能为负数`
          return false
        }
      }
    }
    if (setsOfDay(day) > MAX_SETS_PER_DAY) {
      formError.value = `${weekdayLabel(day.dayOfWeek)} 共 ${setsOfDay(day)} 组，超过单日 ${MAX_SETS_PER_DAY} 组的安全上限`
      return false
    }
  }
  return true
}

/** 摊平成后端明细：一个重量组 = 一条明细，sortOrder 为当天内顺序。 */
function buildItems(): WorkoutPlanItemDTO[] {
  const items: WorkoutPlanItemDTO[] = []
  for (const day of days.value) {
    let order = 1
    for (const exercise of day.exercises) {
      for (const group of exercise.groups) {
        items.push({
          dayOfWeek: day.dayOfWeek,
          exerciseId: exercise.exerciseId,
          targetSets: Number(group.sets),
          targetReps: Number(group.reps),
          targetWeightKg: Number(group.weightKg),
          targetRpe: Number(group.rpe),
          sortOrder: order,
          notes: exercise.notes || null,
        })
        order += 1
      }
    }
  }
  return items
}

async function save(): Promise<void> {
  if (saving.value || !validate()) return
  saving.value = true
  try {
    const dto: WorkoutPlanCreateDTO = {
      userId: appStore.state.userId,
      planName: planName.value.trim(),
      splitType: splitType.value,
      startDate: startDate.value,
      endDate: endDate.value,
      items: buildItems(),
    }
    const plan = await workoutApi.createPlan(dto)
    appStore.pushToast(
      'success',
      `自定义课表已激活：${plan.planName}`,
      `${plan.items.length} 条明细（${totalItems.value} 个重量组）已落库；原课表已自动归档，可直接打卡`,
    )
    emit('saved')
    emit('close')
  } catch (error) {
    formError.value = toErrorMessage(error)
    appStore.pushToast('error', '保存自定义课表失败', formError.value)
  } finally {
    saving.value = false
  }
}

const SPLIT_TYPES: { value: SplitType; label: string }[] = [
  { value: 'FULL_BODY', label: '全身分化' },
  { value: 'PPL', label: '推拉腿（PPL）' },
  { value: 'UPPER_LOWER', label: '上下肢分化' },
]
</script>

<template>
  <AppModal
    :open="open"
    title="自定义周课表"
    hint="每天自己决定练什么；同一个动作可以挂多个「重量组」（例如 80kg×1组 / 90kg×2组 / 100kg×3组），保存后即可打卡"
    width="max-w-4xl"
    @close="emit('close')"
  >
    <div class="space-y-4">
      <!-- 计划信息 -->
      <div class="grid gap-3 sm:grid-cols-4">
        <div class="sm:col-span-2">
          <label class="field-label" for="builder-name">计划名称</label>
          <input id="builder-name" v-model="planName" class="field-input" type="text" placeholder="例如：我的五分化增肌计划" />
        </div>
        <div>
          <label class="field-label" for="builder-split">分化类型</label>
          <select id="builder-split" v-model="splitType" class="field-input">
            <option v-for="option in SPLIT_TYPES" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </div>
        <div>
          <label class="field-label" for="builder-start">开始日期</label>
          <input id="builder-start" v-model="startDate" class="field-input" type="date" />
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <button type="button" class="btn-ghost !min-h-[38px] !px-3 text-xs" @click="loadCurrentPlan">
          <Download :size="13" :stroke-width="2.2" aria-hidden="true" />
          载入当前课表继续编辑
        </button>
        <button type="button" class="btn-ghost !min-h-[38px] !px-3 text-xs" @click="clearAll">清空重排</button>
        <label class="ml-auto flex cursor-pointer items-center gap-1.5 text-[11px] text-ink-muted">
          <input v-model="showForbidden" type="checkbox" class="accent-[#CCFF00]" />
          显示全部动作（含伤病禁忌，用于实测护栏）
        </label>
      </div>

      <!-- 日切换 -->
      <div class="flex flex-wrap gap-2">
        <button
          v-for="day in days"
          :key="day.dayOfWeek"
          type="button"
          :class="[
            'chip cursor-pointer transition duration-200 ease-kinetic',
            activeDay === day.dayOfWeek ? 'border-acid/50 bg-acid-dim text-acid' : 'border-hairline text-ink-secondary hover:text-ink-primary',
          ]"
          :aria-pressed="activeDay === day.dayOfWeek"
          @click="activeDay = day.dayOfWeek"
        >
          {{ weekdayLabel(day.dayOfWeek) }}
          <span class="tnum text-ink-muted">{{ day.exercises.length }} 动作</span>
          <span
            v-if="setsOfDay(day) > 0"
            :class="['tnum', setsOfDay(day) > MAX_SETS_PER_DAY ? 'text-signal-coral' : 'text-ink-muted']"
          >
            {{ setsOfDay(day) }} 组
          </span>
        </button>
      </div>

      <!-- 当日编辑区 -->
      <div class="rounded-chip border border-hairline bg-obsidian/40 p-4">
        <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
          <p class="text-xs font-medium text-ink-primary">{{ weekdayLabel(activeDay) }} 安排</p>
          <div class="flex items-center gap-2">
            <StatusChip :tone="setsOfDay(currentDay) > MAX_SETS_PER_DAY ? 'coral' : 'muted'">
              当日合计 <span class="tnum">{{ setsOfDay(currentDay) }}</span> / {{ MAX_SETS_PER_DAY }} 组
            </StatusChip>
            <button type="button" class="chip cursor-pointer border-acid/35 bg-acid-dim text-acid transition hover:border-acid/60" @click="addExercise">
              <Plus :size="12" :stroke-width="2.2" aria-hidden="true" />
              添加动作
            </button>
          </div>
        </div>

        <div v-if="currentDay.exercises.length === 0" class="flex h-[100px] flex-col items-center justify-center gap-1 text-center">
          <Dumbbell :size="20" :stroke-width="1.8" class="text-ink-muted" aria-hidden="true" />
          <p class="text-sm text-ink-secondary">{{ weekdayLabel(activeDay) }} 还是空的</p>
          <p class="text-xs text-ink-muted">点右上角「添加动作」，或把它留空作为休息日</p>
        </div>

        <ul v-else class="space-y-3">
          <li
            v-for="(exercise, exerciseIndex) in currentDay.exercises"
            :key="exercise.key"
            class="rounded-chip border border-hairline bg-surface/50 p-3.5"
          >
            <div class="flex flex-wrap items-end gap-2">
              <div class="min-w-[200px] flex-1">
                <label class="field-label" :for="`ex-${exercise.key}`">动作</label>
                <select :id="`ex-${exercise.key}`" v-model="exercise.exerciseId" class="field-input" @change="onExerciseChange(exercise)">
                  <option :value="0" disabled>请选择动作</option>
                  <option v-for="option in exerciseOptions" :key="String(option.id)" :value="option.id">
                    {{ option.name }} · {{ muscleLabel[option.targetMuscle] ?? option.targetMuscle }}
                    <template v-if="option.userId !== null && option.userId !== undefined">[自定义]</template>
                    <template v-if="conflictParts(option.id).length > 0">
                      [禁忌 {{ conflictParts(option.id).map((part) => bodyPartLabel[part] ?? part).join('/') }}]
                    </template>
                  </option>
                </select>
                <button
                  type="button"
                  class="mt-1.5 flex cursor-pointer items-center gap-1 text-[11px] text-ink-muted transition hover:text-acid"
                  @click="openCustomDialog(exercise)"
                >
                  <Wrench :size="11" :stroke-width="2.2" aria-hidden="true" />
                  列表里没有？自己起名字新建动作
                </button>
              </div>
              <div class="min-w-[160px] flex-1">
                <label class="field-label" :for="`note-${exercise.key}`">备注（可选，作用于该动作所有重量组）</label>
                <input :id="`note-${exercise.key}`" v-model="exercise.notes" class="field-input" type="text" placeholder="例如：肩胛后缩下沉" />
              </div>
              <button
                type="button"
                class="icon-btn !h-10 !w-10 text-signal-coral"
                :aria-label="`删除动作 ${exercise.exerciseName || exerciseIndex + 1}`"
                @click="removeExercise(exerciseIndex)"
              >
                <Trash2 :size="15" :stroke-width="2" aria-hidden="true" />
              </button>
            </div>

            <p v-if="conflictParts(exercise.exerciseId).length > 0" class="mt-2 flex items-start gap-1.5 text-[11px] text-signal-coral">
              <ShieldAlert :size="11" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
              该动作与你的 ACTIVE 伤病（{{ conflictParts(exercise.exerciseId).map((part) => bodyPartLabel[part] ?? part).join('、') }}）冲突，保存时会被后端护栏拦截。
            </p>

            <!-- 重量组 -->
            <div class="mt-3 space-y-2">
              <div
                v-for="(group, groupIndex) in exercise.groups"
                :key="group.key"
                class="flex flex-wrap items-end gap-2 rounded-chip border border-hairline bg-obsidian/40 p-2.5"
              >
                <span class="readout mb-1 text-xs text-ink-muted">组 {{ groupIndex + 1 }}</span>
                <div class="w-[86px]">
                  <label class="field-label" :for="`w-${group.key}`">重量 kg</label>
                  <input :id="`w-${group.key}`" v-model.number="group.weightKg" class="field-input !py-2 text-xs" type="number" min="0" step="0.5" />
                </div>
                <span class="mb-2 text-xs text-ink-muted">×</span>
                <div class="w-[70px]">
                  <label class="field-label" :for="`s-${group.key}`">组数</label>
                  <input :id="`s-${group.key}`" v-model.number="group.sets" class="field-input !py-2 text-xs" type="number" min="1" :max="MAX_SETS_PER_ITEM" />
                </div>
                <span class="mb-2 text-xs text-ink-muted">×</span>
                <div class="w-[70px]">
                  <label class="field-label" :for="`r-${group.key}`">次数</label>
                  <input :id="`r-${group.key}`" v-model.number="group.reps" class="field-input !py-2 text-xs" type="number" min="1" max="100" />
                </div>
                <div class="w-[76px]">
                  <label class="field-label" :for="`rpe-${group.key}`">目标 RPE</label>
                  <input :id="`rpe-${group.key}`" v-model.number="group.rpe" class="field-input !py-2 text-xs" type="number" min="1" max="10" step="0.5" />
                </div>
                <button
                  type="button"
                  class="icon-btn !h-9 !w-9 text-ink-muted hover:text-signal-coral"
                  :aria-label="`删除 ${exercise.exerciseName || '该动作'} 的第 ${groupIndex + 1} 个重量组`"
                  @click="removeGroup(exercise, groupIndex)"
                >
                  <Trash2 :size="13" :stroke-width="2" aria-hidden="true" />
                </button>
              </div>

              <button type="button" class="chip cursor-pointer border-hairline text-ink-secondary transition hover:text-ink-primary" @click="addGroup(exercise)">
                <Plus :size="12" :stroke-width="2.2" aria-hidden="true" />
                再加一个重量组
              </button>
            </div>
          </li>
        </ul>
      </div>

      <!-- 汇总与错误 -->
      <div class="flex flex-wrap items-center justify-between gap-3 text-[11px] text-ink-muted">
        <span>
          全周 <span class="tnum text-ink-primary">{{ totalItems }}</span> 条明细（即 {{ totalItems }} 个重量组）·
          合计 <span class="tnum text-ink-primary">{{ days.reduce((sum, day) => sum + setsOfDay(day), 0) }}</span> 组
        </span>
        <span v-if="overLimitDays.length > 0" class="text-signal-coral">
          {{ overLimitDays.map((day) => weekdayLabel(day.dayOfWeek)).join('、') }} 超过单日 {{ MAX_SETS_PER_DAY }} 组上限
        </span>
        <span v-else>单日组数均在 {{ MAX_SETS_PER_DAY }} 组护栏内</span>
      </div>

      <p v-if="formError" class="flex items-start gap-1.5 rounded-chip border border-signal-coral/50 bg-signal-coral/10 p-3 text-[11px] text-signal-coral" role="alert">
        <AlertTriangle :size="12" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
        {{ formError }}
      </p>
      <p class="text-[11px] leading-relaxed text-ink-muted">
        保存会把原课表自动归档（永远只有一份激活课表）。同一个动作的多个重量组会存成多条明细，
        因此每个重量组都能单独打卡、单独看 1RM 与容量，双渐进建议也会按各自重量分别给出。
      </p>
    </div>

    <template #footer>
      <div class="flex flex-wrap items-center justify-end gap-2">
        <button type="button" class="btn-ghost !min-h-[40px] !px-4 text-xs" @click="emit('close')">取消</button>
        <button type="button" class="btn-primary !min-h-[40px] !px-5 text-xs" :disabled="saving" @click="save">
          <Loader2 v-if="saving" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
          <Save v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
          {{ saving ? '保存中…' : `保存并激活（${formatInt(totalItems)} 条明细）` }}
        </button>
      </div>
    </template>
  </AppModal>

  <!-- 自定义动作（与排课弹窗同级渲染，避免嵌套层级问题） -->
  <CustomExerciseDialog
    :open="customDialogOpen"
    :user-id="appStore.state.userId"
    :active-injury-parts="activeInjuryParts"
    @close="customDialogOpen = false"
    @created="onCustomCreated"
  />
</template>