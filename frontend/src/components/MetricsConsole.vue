<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Loader2, RotateCcw, Save, TrendingDown, TrendingUp } from 'lucide-vue-next'
import StatusChip from '@/components/StatusChip.vue'
import { profileApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { ACTIVITY_LEVELS, equipmentLabel, goalLabel } from '@/theme/tokens'
import { formatInt, formatWeight } from '@/utils/format'
import type { EquipmentEnv, FitnessGoal, Gender, UserDigitalTwinVO, UserProfileUpdateDTO } from '@/api/types'

const props = defineProps<{ twin: UserDigitalTwinVO | null; loading?: boolean }>()
const emit = defineEmits<{ (event: 'saved'): void }>()

interface FormState {
  nickname: string
  gender: Gender
  age: number
  heightCm: number
  weightKg: number
  bodyFatPct: number | null
  activityLevel: number
  fitnessGoal: FitnessGoal
  equipmentEnv: EquipmentEnv
}

const form = ref<FormState>(emptyForm())
const saving = ref(false)
/** 保存后后端重算的 BMR/TDEE 变化（用于「跳动」提示） */
const recalc = ref<{ bmr: number; tdee: number; bmrDelta: number; tdeeDelta: number } | null>(null)

function emptyForm(): FormState {
  return {
    nickname: '',
    gender: 'MALE',
    age: 21,
    heightCm: 178,
    weightKg: 75,
    bodyFatPct: null,
    activityLevel: 1.55,
    fitnessGoal: 'MUSCLE_GAIN',
    equipmentEnv: 'GYM',
  }
}

function syncFromTwin(twin: UserDigitalTwinVO | null): void {
  if (!twin) return
  form.value = {
    nickname: twin.nickname ?? '',
    gender: twin.gender,
    age: twin.age,
    heightCm: twin.heightCm,
    weightKg: twin.weightKg,
    bodyFatPct: twin.bodyFatPct,
    activityLevel: twin.activityLevel,
    fitnessGoal: twin.fitnessGoal,
    equipmentEnv: twin.equipmentEnv,
  }
}

watch(() => props.twin, syncFromTwin, { immediate: true })

/** 是否与后端档案存在差异（决定「保存」是否可点）。 */
const dirty = computed(() => {
  const twin = props.twin
  if (!twin) return false
  const current = form.value
  return (
    (current.nickname ?? '') !== (twin.nickname ?? '') ||
    current.gender !== twin.gender ||
    current.age !== twin.age ||
    Number(current.heightCm) !== Number(twin.heightCm) ||
    Number(current.weightKg) !== Number(twin.weightKg) ||
    (current.bodyFatPct ?? null) !== (twin.bodyFatPct ?? null) ||
    Number(current.activityLevel) !== Number(twin.activityLevel) ||
    current.fitnessGoal !== twin.fitnessGoal ||
    current.equipmentEnv !== twin.equipmentEnv
  )
})

function reset(): void {
  syncFromTwin(props.twin)
  recalc.value = null
}

async function save(): Promise<void> {
  if (!props.twin || saving.value) return
  saving.value = true
  const previousBmr = props.twin.bmr
  const previousTdee = props.twin.tdee
  try {
    const payload: UserProfileUpdateDTO = {
      nickname: form.value.nickname || undefined,
      gender: form.value.gender,
      age: Number(form.value.age),
      heightCm: Number(form.value.heightCm),
      weightKg: Number(form.value.weightKg),
      bodyFatPct: form.value.bodyFatPct === null ? undefined : Number(form.value.bodyFatPct),
      activityLevel: Number(form.value.activityLevel),
      fitnessGoal: form.value.fitnessGoal,
      equipmentEnv: form.value.equipmentEnv,
    }
    const updated = await profileApi.update(props.twin.userId, payload)
    recalc.value = {
      bmr: updated.bmr,
      tdee: updated.tdee,
      bmrDelta: updated.bmr - previousBmr,
      tdeeDelta: updated.tdee - previousTdee,
    }
    appStore.pushToast('success', '档案已保存', `后端已重算 BMR ${formatInt(updated.bmr)} kcal / TDEE ${formatInt(updated.tdee)} kcal`)
    emit('saved')
  } catch (error) {
    appStore.pushToast('error', '保存失败', toErrorMessage(error))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="flex h-full flex-col">
    <div v-if="loading && !twin" class="space-y-3">
      <div v-for="index in 5" :key="index" class="skeleton h-10 w-full" />
    </div>

    <template v-else>
      <!-- 后端重算结果：数值跳动 + 变化幅度 -->
      <div class="mb-4 grid grid-cols-2 gap-3 rounded-chip border border-hairline bg-obsidian/40 p-3.5">
        <div>
          <p class="text-xs text-ink-secondary">BMR 基础代谢</p>
          <p class="mt-1 flex items-baseline gap-1.5">
            <span class="readout text-3xl font-semibold text-ink-primary">{{ formatInt(twin?.bmr) }}</span>
            <span class="text-xs text-ink-muted">kcal</span>
          </p>
          <p v-if="recalc && recalc.bmrDelta !== 0" class="mt-1 flex items-center gap-1 text-xs text-acid">
            <component :is="recalc.bmrDelta > 0 ? TrendingUp : TrendingDown" :size="12" :stroke-width="2.4" aria-hidden="true" />
            {{ recalc.bmrDelta > 0 ? '+' : '' }}{{ formatInt(recalc.bmrDelta) }} kcal（本次保存后重算）
          </p>
        </div>
        <div>
          <p class="text-xs text-ink-secondary">TDEE 总消耗</p>
          <p class="mt-1 flex items-baseline gap-1.5">
            <span class="readout text-3xl font-semibold text-ink-primary">{{ formatInt(twin?.tdee) }}</span>
            <span class="text-xs text-ink-muted">kcal</span>
          </p>
          <p v-if="recalc && recalc.tdeeDelta !== 0" class="mt-1 flex items-center gap-1 text-xs text-acid">
            <component :is="recalc.tdeeDelta > 0 ? TrendingUp : TrendingDown" :size="12" :stroke-width="2.4" aria-hidden="true" />
            {{ recalc.tdeeDelta > 0 ? '+' : '' }}{{ formatInt(recalc.tdeeDelta) }} kcal（本次保存后重算）
          </p>
        </div>
      </div>

      <form class="flex-1 space-y-4" @submit.prevent="save">
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="field-label" for="twin-nickname">昵称</label>
            <input id="twin-nickname" v-model="form.nickname" class="field-input" type="text" placeholder="训练者" />
          </div>
          <div>
            <label class="field-label" for="twin-gender">性别</label>
            <select id="twin-gender" v-model="form.gender" class="field-input">
              <option value="MALE">男</option>
              <option value="FEMALE">女</option>
            </select>
          </div>
        </div>

        <div class="grid grid-cols-3 gap-3">
          <div>
            <label class="field-label" for="twin-age">年龄</label>
            <input id="twin-age" v-model.number="form.age" class="field-input" type="number" min="14" max="90" />
          </div>
          <div>
            <label class="field-label" for="twin-height">身高 cm</label>
            <input id="twin-height" v-model.number="form.heightCm" class="field-input" type="number" min="120" max="230" step="0.5" />
          </div>
          <div>
            <label class="field-label" for="twin-weight">体重 kg</label>
            <input id="twin-weight" v-model.number="form.weightKg" class="field-input" type="number" min="30" max="250" step="0.5" />
          </div>
        </div>

        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="field-label" for="twin-bodyfat">体脂率 %（可空）</label>
            <input id="twin-bodyfat" v-model.number="form.bodyFatPct" class="field-input" type="number" min="3" max="60" step="0.1" placeholder="未测量" />
          </div>
          <div>
            <label class="field-label" for="twin-activity">活动系数</label>
            <select id="twin-activity" v-model.number="form.activityLevel" class="field-input">
              <option v-for="level in ACTIVITY_LEVELS" :key="level.value" :value="level.value">
                {{ level.value }} · {{ level.label }}
              </option>
            </select>
          </div>
        </div>

        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="field-label" for="twin-goal">训练目标</label>
            <select id="twin-goal" v-model="form.fitnessGoal" class="field-input">
              <option v-for="(label, key) in goalLabel" :key="key" :value="key">{{ label }}</option>
            </select>
          </div>
          <div>
            <label class="field-label" for="twin-equipment">器械环境</label>
            <select id="twin-equipment" v-model="form.equipmentEnv" class="field-input">
              <option v-for="(label, key) in equipmentLabel" :key="key" :value="key">{{ label }}</option>
            </select>
          </div>
        </div>

        <div class="flex flex-wrap items-center justify-between gap-3 border-t border-hairline pt-4">
          <div class="flex items-center gap-2">
            <StatusChip :tone="dirty ? 'orange' : 'muted'">
              {{ dirty ? '有未保存的修改' : '与后端档案一致' }}
            </StatusChip>
            <p class="hidden text-xs text-ink-muted sm:block">
              BMR / TDEE 一律由后端 Java 公式计算，前端不做任何推算
            </p>
          </div>
          <div class="flex items-center gap-2">
            <button type="button" class="btn-ghost !min-h-[40px] !px-3.5 text-xs" :disabled="!dirty || saving" @click="reset">
              <RotateCcw :size="13" :stroke-width="2.2" aria-hidden="true" />
              还原
            </button>
            <button type="submit" class="btn-primary !min-h-[40px] !px-4 text-xs" :disabled="!dirty || saving">
              <Loader2 v-if="saving" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
              <Save v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
              {{ saving ? '保存中…' : '保存档案' }}
            </button>
          </div>
        </div>

        <dl class="grid grid-cols-3 gap-3 rounded-chip border border-hairline bg-obsidian/30 p-3.5 text-xs">
          <div>
            <dt class="text-ink-muted">当前体重</dt>
            <dd class="tnum mt-1 text-ink-primary">{{ formatWeight(twin?.weightKg) }} kg</dd>
          </div>
          <div>
            <dt class="text-ink-muted">档案创建</dt>
            <dd class="tnum mt-1 text-ink-primary">{{ twin?.createdAt?.slice(0, 10) ?? '—' }}</dd>
          </div>
          <div>
            <dt class="text-ink-muted">最后更新</dt>
            <dd class="tnum mt-1 text-ink-primary">{{ twin?.updatedAt?.slice(0, 10) ?? '—' }}</dd>
          </div>
        </dl>
      </form>
    </template>
  </div>
</template>