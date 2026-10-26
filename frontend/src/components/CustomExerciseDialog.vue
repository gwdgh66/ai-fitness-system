<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AlertTriangle, Loader2, Plus, ShieldAlert, Trash2, Wrench } from 'lucide-vue-next'
import AppModal from '@/components/AppModal.vue'
import StatusChip from '@/components/StatusChip.vue'
import { workoutApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { INJURY_BODY_PARTS, bodyPartLabel, muscleLabel } from '@/theme/tokens'
import type { CustomExerciseCreateDTO, ExerciseDict, Id, MuscleGroup } from '@/api/types'

const props = defineProps<{
  open: boolean
  userId: Id
  /** 当前已声明的活跃伤病部位：用于提示新建动作是否会与既有伤病冲突 */
  activeInjuryParts: string[]
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'created', exercise: ExerciseDict): void
}>()

const MUSCLES: MuscleGroup[] = ['CHEST', 'BACK', 'LEGS', 'SHOULDERS', 'ARMS', 'CORE']
const EQUIPMENTS = ['BARBELL', 'DUMBBELL', 'MACHINE', 'CABLE', 'BODYWEIGHT', 'OTHER']
const EQUIPMENT_LABEL: Record<string, string> = {
  BARBELL: '杠铃',
  DUMBBELL: '哑铃',
  MACHINE: '固定器械',
  CABLE: '绳索/滑轮',
  BODYWEIGHT: '自重',
  OTHER: '其他/无器械',
}
const DIFFICULTIES = ['BEGINNER', 'INTERMEDIATE', 'ADVANCED']
const DIFFICULTY_LABEL: Record<string, string> = {
  BEGINNER: '入门',
  INTERMEDIATE: '进阶',
  ADVANCED: '高阶',
}

const name = ref('')
const targetMuscle = ref<MuscleGroup>('CHEST')
const equipment = ref('OTHER')
const difficulty = ref('INTERMEDIATE')
const avoidParts = ref<string[]>([])
const cues = ref('')
const submitting = ref(false)
const formError = ref('')

const myExercises = ref<ExerciseDict[]>([])
const listLoading = ref(false)
const deletingId = ref<Id | null>(null)

const noAvoidDeclared = computed(() => avoidParts.value.length === 0)

/** 未声明禁忌时的额外提醒：如果用户当前有活跃伤病，护栏将无法拦这个新动作。 */
const conflictsWithActive = computed(() =>
  props.activeInjuryParts.filter((part) => avoidParts.value.includes(part)),
)

async function loadMine(): Promise<void> {
  listLoading.value = true
  try {
    const all = await workoutApi.allExercises(props.userId)
    myExercises.value = all.filter((row) => row.userId !== null)
  } catch (error) {
    appStore.pushToast('error', '自定义动作列表加载失败', toErrorMessage(error))
  } finally {
    listLoading.value = false
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      name.value = ''
      targetMuscle.value = 'CHEST'
      equipment.value = 'OTHER'
      difficulty.value = 'INTERMEDIATE'
      avoidParts.value = []
      cues.value = ''
      formError.value = ''
      void loadMine()
    }
  },
)

function togglePart(part: string): void {
  const index = avoidParts.value.indexOf(part)
  if (index >= 0) {
    avoidParts.value.splice(index, 1)
  } else {
    avoidParts.value.push(part)
  }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  formError.value = ''
  const trimmed = name.value.trim()
  if (trimmed.length < 2 || trimmed.length > 32) {
    formError.value = '动作名称长度需在 2 ~ 32 个字符之间'
    return
  }
  submitting.value = true
  try {
    const dto: CustomExerciseCreateDTO = {
      userId: props.userId,
      name: trimmed,
      targetMuscle: targetMuscle.value,
      equipment: equipment.value,
      difficulty: difficulty.value,
      avoidInjuryParts: avoidParts.value.length > 0 ? avoidParts.value.join(',') : null,
      cues: cues.value.trim() || null,
    }
    const created = await workoutApi.createCustomExercise(dto)
    appStore.pushToast(
      'success',
      `已创建自定义动作「${created.name}」`,
      `${muscleLabel[created.targetMuscle] ?? created.targetMuscle} · ${EQUIPMENT_LABEL[created.equipment] ?? created.equipment} · ` +
        (created.avoidInjuryParts
          ? `禁忌 ${created.avoidInjuryParts.split(',').map((part) => bodyPartLabel[part] ?? part).join('/')}`
          : '未声明禁忌部位'),
    )
    emit('created', { ...created, custom: true })
    await loadMine()
    name.value = ''
    cues.value = ''
  } catch (error) {
    formError.value = toErrorMessage(error)
    appStore.pushToast('error', '创建自定义动作失败', formError.value)
  } finally {
    submitting.value = false
  }
}

async function remove(exercise: ExerciseDict): Promise<void> {
  if (deletingId.value !== null) return
  deletingId.value = exercise.id
  try {
    const result = await workoutApi.deleteCustomExercise(exercise.id, props.userId)
    appStore.pushToast('success', '已删除自定义动作', result.message)
    await loadMine()
  } catch (error) {
    appStore.pushToast('error', '删除失败', toErrorMessage(error))
  } finally {
    deletingId.value = null
  }
}
</script>

<template>
  <AppModal
    :open="open"
    title="自定义动作"
    hint="自己起名字建动作；建好后会出现在「换动作 / 自定义排课」的动作列表里，可直接排课并打卡"
    width="max-w-2xl"
    @close="emit('close')"
  >
    <div class="space-y-4">
      <div class="grid gap-3 sm:grid-cols-2">
        <div class="sm:col-span-2">
          <label class="field-label" for="ce-name">动作名称（全局唯一，2~32 字）</label>
          <input id="ce-name" v-model="name" class="field-input" type="text" placeholder="例如：自创：单腿罗马尼亚硬拉 / 战绳交替甩" />
        </div>
        <div>
          <label class="field-label" for="ce-muscle">目标肌群</label>
          <select id="ce-muscle" v-model="targetMuscle" class="field-input">
            <option v-for="muscle in MUSCLES" :key="muscle" :value="muscle">{{ muscleLabel[muscle] ?? muscle }}（{{ muscle }}）</option>
          </select>
        </div>
        <div>
          <label class="field-label" for="ce-equipment">所需器械</label>
          <select id="ce-equipment" v-model="equipment" class="field-input">
            <option v-for="item in EQUIPMENTS" :key="item" :value="item">{{ EQUIPMENT_LABEL[item] ?? item }}</option>
          </select>
        </div>
        <div>
          <label class="field-label" for="ce-difficulty">难度</label>
          <select id="ce-difficulty" v-model="difficulty" class="field-input">
            <option v-for="item in DIFFICULTIES" :key="item" :value="item">{{ DIFFICULTY_LABEL[item] ?? item }}</option>
          </select>
        </div>
        <div>
          <label class="field-label" for="ce-cues">动作要领（可选，≤500 字）</label>
          <input id="ce-cues" v-model="cues" class="field-input" type="text" placeholder="例如：髋主导，脊柱中立" />
        </div>
      </div>

      <!-- 禁忌部位：护栏的关键字段 -->
      <div class="rounded-chip border p-3.5" :class="noAvoidDeclared ? 'border-signal-orange/45 bg-signal-orange/[0.07]' : 'border-hairline bg-obsidian/40'">
        <p class="mb-2 flex items-center gap-1.5 text-xs font-medium text-ink-primary">
          <ShieldAlert :size="13" :stroke-width="2.2" :class="noAvoidDeclared ? 'text-signal-orange' : 'text-acid'" aria-hidden="true" />
          禁忌受伤部位（伤病护栏靠它判断该动作是否安全）
        </p>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="part in INJURY_BODY_PARTS"
            :key="part"
            type="button"
            :class="[
              'chip cursor-pointer transition duration-200 ease-kinetic',
              avoidParts.includes(part)
                ? 'border-signal-coral/50 bg-signal-coral/10 text-signal-coral'
                : 'border-hairline text-ink-secondary hover:text-ink-primary',
            ]"
            :aria-pressed="avoidParts.includes(part)"
            @click="togglePart(part)"
          >
            {{ bodyPartLabel[part] ?? part }}
          </button>
        </div>
        <p v-if="noAvoidDeclared" class="mt-2.5 flex items-start gap-1.5 text-[11px] leading-relaxed text-signal-orange">
          <AlertTriangle :size="11" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
          没有勾选任何部位 → 系统会认为这个动作「对所有伤病都安全」，护栏将<b>无法</b>在你受伤时拦截它。
          如果这个动作会牵涉某个部位（例如弯腰类动作涉及下背部），请务必勾上。
        </p>
        <p v-else-if="conflictsWithActive.length > 0" class="mt-2.5 flex items-start gap-1.5 text-[11px] leading-relaxed text-signal-coral">
          <ShieldAlert :size="11" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
          你声明了该动作禁忌
          {{ conflictsWithActive.map((part) => bodyPartLabel[part] ?? part).join('、') }}，
          而这正是你当前的 ACTIVE 伤病 → 建好后它会被安全过滤挡在课表之外（这是预期行为）。
        </p>
      </div>

      <p v-if="formError" class="flex items-start gap-1.5 rounded-chip border border-signal-coral/50 bg-signal-coral/10 p-3 text-[11px] text-signal-coral" role="alert">
        <AlertTriangle :size="12" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
        {{ formError }}
      </p>

      <!-- 我已创建的动作 -->
      <div class="border-t border-hairline pt-4">
        <div class="mb-2 flex items-center justify-between gap-2">
          <p class="text-xs font-medium text-ink-primary">我创建的动作</p>
          <StatusChip tone="muted">{{ myExercises.length }} 个</StatusChip>
        </div>
        <div v-if="listLoading" class="skeleton h-12 w-full" />
        <p v-else-if="myExercises.length === 0" class="text-[11px] text-ink-muted">还没有自定义动作</p>
        <ul v-else class="space-y-1.5">
          <li
            v-for="exercise in myExercises"
            :key="String(exercise.id)"
            class="flex items-center justify-between gap-2 rounded-chip border border-hairline bg-obsidian/40 px-3 py-2"
          >
            <span class="flex min-w-0 flex-wrap items-center gap-1.5">
              <span class="truncate text-xs text-ink-primary">{{ exercise.name }}</span>
              <StatusChip tone="cyan">{{ muscleLabel[exercise.targetMuscle] ?? exercise.targetMuscle }}</StatusChip>
              <StatusChip v-if="exercise.avoidInjuryParts" tone="coral">
                禁忌 {{ exercise.avoidInjuryParts.split(',').map((part) => bodyPartLabel[part] ?? part).join('/') }}
              </StatusChip>
              <StatusChip v-else tone="orange">未声明禁忌</StatusChip>
            </span>
            <button
              type="button"
              class="icon-btn !h-9 !w-9 text-ink-muted hover:text-signal-coral"
              :aria-label="`删除自定义动作 ${exercise.name}`"
              :disabled="deletingId !== null"
              @click="remove(exercise)"
            >
              <Loader2 v-if="deletingId === exercise.id" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
              <Trash2 v-else :size="13" :stroke-width="2" aria-hidden="true" />
            </button>
          </li>
        </ul>
        <p class="mt-2 text-[11px] leading-relaxed text-ink-muted">
          已被课表或打卡记录引用的动作不能删除（否则训练历史会失去动作信息）；请先从课表里移除它。
        </p>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-end gap-2">
        <button type="button" class="btn-ghost !min-h-[40px] !px-4 text-xs" @click="emit('close')">关闭</button>
        <button type="button" class="btn-primary !min-h-[40px] !px-5 text-xs" :disabled="submitting" @click="submit">
          <Loader2 v-if="submitting" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
          <Plus v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
          {{ submitting ? '创建中…' : '创建并用于排课' }}
        </button>
      </div>
      <p class="mt-2 flex items-center gap-1.5 text-[11px] text-ink-muted">
        <Wrench :size="11" :stroke-width="2.2" aria-hidden="true" />
        自定义动作与内置动作同等对待：可排课、可打卡、可看 1RM 与双渐进建议。
      </p>
    </template>
  </AppModal>
</template>