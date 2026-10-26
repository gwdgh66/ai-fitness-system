<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  Loader2,
  Plus,
  Save,
  Sparkles,
  Trash2,
  Utensils,
} from 'lucide-vue-next'
import AppModal from '@/components/AppModal.vue'
import StatusChip from '@/components/StatusChip.vue'
import { aiApi, catalogApi, nutritionApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { mealTypeLabel } from '@/theme/tokens'
import { formatInt, toIsoDate } from '@/utils/format'
import type { DietLog, DietSource, FoodNutritionDict, MealItemDTO, MealType } from '@/api/types'

const props = defineProps<{
  open: boolean
  mealType: MealType
  /** 已加载的食物字典：用于「输入名字自动匹配」 */
  dictionary: FoodNutritionDict[]
}>()

const emit = defineEmits<{ (event: 'close'): void; (event: 'done'): void }>()

const MEAL_TYPES: MealType[] = ['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK']

interface Row {
  key: number
  foodName: string
  weightG: number
  caloriesPer100g: number | null
  proteinPer100g: number | null
  carbsPer100g: number | null
  fatPer100g: number | null
  source: DietSource
  /** 是否命中本地字典（命中时以字典为准，不允许手改营养值） */
  matched: boolean
  note: string
  estimating: boolean
  estimateBasis: string
  estimateWarning: string
  error: string
}

let rowSeq = 0

function newRow(): Row {
  return {
    key: ++rowSeq,
    foodName: '',
    weightG: 100,
    caloriesPer100g: null,
    proteinPer100g: null,
    carbsPer100g: null,
    fatPer100g: null,
    source: 'CUSTOM',
    matched: false,
    note: '',
    estimating: false,
    estimateBasis: '',
    estimateWarning: '',
    error: '',
  }
}

const rows = ref<Row[]>([newRow()])
const templateName = ref('')
const mealType = ref<MealType>(props.mealType)
const submitting = ref(false)

watch(
  () => props.open,
  (open) => {
    if (open) {
      rows.value = [newRow()]
      templateName.value = ''
      mealType.value = props.mealType
    }
  },
)
watch(
  () => props.mealType,
  (value) => {
    if (!props.open) return
    mealType.value = value
  },
)

/** 输入食物名时自动匹配字典：命中则填入每 100g 数据并锁定为字典来源。 */
function matchDictionary(row: Row): void {
  const name = row.foodName.trim()
  const food = props.dictionary.find((item) => item.foodName === name)
  if (food) {
    row.caloriesPer100g = food.caloriesPer100g
    row.proteinPer100g = food.proteinPer100g
    row.carbsPer100g = food.carbsPer100g
    row.fatPer100g = food.fatPer100g
    row.source = 'DICTIONARY'
    row.matched = true
    row.estimateBasis = ''
    row.estimateWarning = ''
  } else {
    row.matched = false
    if (row.source === 'DICTIONARY') {
      row.source = 'CUSTOM'
    }
  }
}

/** 让 AI 给出「每 100g」参考值（不落库，只填表）。 */
async function useAiEstimate(row: Row): Promise<void> {
  if (row.estimating || row.foodName.trim().length === 0) return
  row.estimating = true
  row.error = ''
  try {
    const estimate = await aiApi.estimateFood({
      userId: appStore.state.userId,
      foodName: row.foodName.trim(),
      weightG: Number(row.weightG) || null,
      note: row.note.trim() || null,
    })
    row.caloriesPer100g = estimate.caloriesPer100g
    row.proteinPer100g = estimate.proteinPer100g
    row.carbsPer100g = estimate.carbsPer100g
    row.fatPer100g = estimate.fatPer100g
    row.source = 'AI_ESTIMATED'
    row.estimateBasis = `${estimate.basis}（把握度 ${estimate.confidence}）`
    row.estimateWarning = estimate.warning ?? ''
    appStore.pushToast(
      estimate.consistent ? 'info' : 'error',
      estimate.consistent ? 'AI 已给出参考值（请确认后落库）' : 'AI 参考值自洽性存疑',
      estimate.warning ?? estimate.disclaimer,
    )
  } catch (error) {
    row.error = toErrorMessage(error)
    appStore.pushToast('error', 'AI 估算失败', row.error)
  } finally {
    row.estimating = false
  }
}

function addRow(): void {
  rows.value.push(newRow())
}

function removeRow(index: number): void {
  rows.value.splice(index, 1)
  if (rows.value.length === 0) addRow()
}

/** 前端折算预览：仅用于显示，落库数值由后端按每 100g × 克重 ÷ 100 计算。 */
function rowCalories(row: Row): number {
  if (row.caloriesPer100g === null) return 0
  return Math.round((row.caloriesPer100g * Number(row.weightG || 0)) / 100)
}

const previewTotals = computed(() =>
  rows.value.reduce(
    (acc, row) => {
      const ratio = Number(row.weightG || 0) / 100
      acc.calories += rowCalories(row)
      acc.protein += (row.proteinPer100g ?? 0) * ratio
      acc.carbs += (row.carbsPer100g ?? 0) * ratio
      acc.fat += (row.fatPer100g ?? 0) * ratio
      return acc
    },
    { calories: 0, protein: 0, carbs: 0, fat: 0 },
  ),
)

const filledRows = computed(() => rows.value.filter((row) => row.foodName.trim().length > 0))

/** 逐行校验；自定义食物必须给出热量数值。 */
function validate(): boolean {
  let ok = true
  for (const row of rows.value) {
    row.error = ''
    if (row.foodName.trim().length === 0) {
      if (rows.value.length > 1) {
        // 多行模式下允许多余空行，直接忽略
        continue
      }
      row.error = '请填写食物名称'
      ok = false
      continue
    }
    if (!Number(row.weightG) || Number(row.weightG) <= 0) {
      row.error = '克重必须是大于 0 的数字'
      ok = false
      continue
    }
    if (row.caloriesPer100g === null) {
      row.error = '字典里没有这个食物，请填写每 100g 热量，或点右侧「AI 估算」'
      ok = false
    }
  }
  if (filledRows.value.length === 0) {
    ok = false
    if (rows.value[0]) rows.value[0].error = '至少填写一种食物'
  }
  return ok
}

function buildItems(): MealItemDTO[] {
  return filledRows.value.map((row) => ({
    foodName: row.foodName.trim(),
    weightG: Number(row.weightG),
    caloriesPer100g: row.caloriesPer100g,
    proteinPer100g: row.proteinPer100g,
    carbsPer100g: row.carbsPer100g,
    fatPer100g: row.fatPer100g,
    source: row.source,
  }))
}

/** ① 直接打卡到今天（不需要先建套餐）。 */
async function logNow(): Promise<void> {
  if (submitting.value || !validate()) return
  submitting.value = true
  const today = toIsoDate(new Date())
  const saved: DietLog[] = []
  try {
    for (const item of buildItems()) {
      saved.push(
        await nutritionApi.addLog({
          userId: appStore.state.userId,
          logDate: today,
          mealType: mealType.value,
          foodName: item.foodName,
          weightG: item.weightG,
          caloriesPer100g: item.caloriesPer100g,
          proteinPer100g: item.proteinPer100g,
          carbsPer100g: item.carbsPer100g,
          fatPer100g: item.fatPer100g,
          source: item.source,
        }),
      )
    }
    const totalCalories = saved.reduce((sum, item) => sum + (item.calories ?? 0), 0)
    appStore.pushToast(
      'success',
      `已打卡 ${saved.length} 条到${mealTypeLabel[mealType.value]}`,
      `合计 ${formatInt(totalCalories)} kcal（后端按每 100g × 克重折算）· 无需创建套餐`,
    )
    emit('done')
    emit('close')
  } catch (error) {
    appStore.pushToast('error', '打卡失败', toErrorMessage(error))
  } finally {
    submitting.value = false
  }
}

/** ② 保存为常吃套餐（可复用、可一键打卡）。 */
async function saveAsTemplate(): Promise<void> {
  if (submitting.value || !validate()) return
  submitting.value = true
  try {
    const created = await catalogApi.createMealTemplate({
      userId: appStore.state.userId,
      templateName: templateName.value.trim() || '我的自选套餐',
      mealType: mealType.value,
      items: buildItems(),
    })
    appStore.pushToast(
      'success',
      `已保存套餐「${created.templateName}」`,
      `后端汇总 ${created.totalCalories} kcal · P${created.totalProteinG} / C${created.totalCarbsG} / F${created.totalFatG} g`,
    )
    emit('done')
    emit('close')
  } catch (error) {
    appStore.pushToast('error', '保存套餐失败', toErrorMessage(error))
  } finally {
    submitting.value = false
  }
}

const SOURCE_LABEL: Record<string, string> = {
  DICTIONARY: '字典命中',
  CUSTOM: '自定义',
  AI_ESTIMATED: 'AI 估算',
}
</script>

<template>
  <AppModal
    :open="open"
    title="自选食物组合"
    hint="可以直接打卡（无需先建套餐），也可以顺手存成常吃套餐；自定义食物支持手填或让 AI 给参考值"
    width="max-w-3xl"
    @close="emit('close')"
  >
    <div class="space-y-4">
      <div class="grid gap-3 sm:grid-cols-3">
        <div>
          <label class="field-label" for="composer-meal">餐次</label>
          <select id="composer-meal" v-model="mealType" class="field-input">
            <option v-for="type in MEAL_TYPES" :key="type" :value="type">{{ mealTypeLabel[type] }}</option>
          </select>
        </div>
        <div class="sm:col-span-2">
          <label class="field-label" for="composer-name">套餐名称（仅「保存为套餐」时需要）</label>
          <input
            id="composer-name"
            v-model="templateName"
            class="field-input"
            type="text"
            placeholder="例如：我的高蛋白早餐"
          />
        </div>
      </div>

      <ul class="space-y-3">
        <li
          v-for="(row, index) in rows"
          :key="row.key"
          class="rounded-chip border border-hairline bg-obsidian/40 p-3.5"
        >
          <div class="flex flex-wrap items-end gap-2">
            <div class="min-w-[180px] flex-1">
              <label class="field-label" :for="`food-${row.key}`">食物名称（可直接输入自定义食物）</label>
              <input
                :id="`food-${row.key}`"
                v-model="row.foodName"
                class="field-input"
                type="text"
                list="composer-food-list"
                placeholder="例如：佛跳墙 / 自制蛋白奶昔"
                @change="matchDictionary(row)"
                @blur="matchDictionary(row)"
              />
            </div>
            <div class="w-[92px]">
              <label class="field-label" :for="`weight-${row.key}`">克重</label>
              <input :id="`weight-${row.key}`" v-model.number="row.weightG" class="field-input" type="number" min="1" step="10" />
            </div>
            <div class="flex items-center gap-2 pb-0.5">
              <StatusChip :tone="row.matched ? 'acid' : row.source === 'AI_ESTIMATED' ? 'orange' : 'muted'">
                {{ SOURCE_LABEL[row.source] ?? row.source }}
              </StatusChip>
              <button
                type="button"
                class="icon-btn !h-9 !w-9"
                :aria-label="`删除第 ${index + 1} 行`"
                :disabled="rows.length === 1"
                @click="removeRow(index)"
              >
                <Trash2 :size="14" :stroke-width="2" aria-hidden="true" />
              </button>
            </div>
          </div>

          <!-- 字典命中：只读展示；自定义：可编辑 + AI 估算 -->
          <div v-if="row.matched" class="mt-2.5 text-[11px] text-ink-muted">
            每 100g：{{ row.caloriesPer100g }} kcal · P{{ row.proteinPer100g }} / C{{ row.carbsPer100g }} / F{{ row.fatPer100g }} g
            <span class="text-acid">（命中本地字典，以字典为准）</span>
          </div>

          <template v-else>
            <div class="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <div>
                <label class="field-label" :for="`kcal-${row.key}`">每 100g 热量</label>
                <input :id="`kcal-${row.key}`" v-model.number="row.caloriesPer100g" class="field-input !py-2 text-xs" type="number" min="0" max="1000" placeholder="kcal" />
              </div>
              <div>
                <label class="field-label" :for="`p-${row.key}`">蛋白 /100g</label>
                <input :id="`p-${row.key}`" v-model.number="row.proteinPer100g" class="field-input !py-2 text-xs" type="number" min="0" max="100" step="0.1" placeholder="g" />
              </div>
              <div>
                <label class="field-label" :for="`c-${row.key}`">碳水 /100g</label>
                <input :id="`c-${row.key}`" v-model.number="row.carbsPer100g" class="field-input !py-2 text-xs" type="number" min="0" max="100" step="0.1" placeholder="g" />
              </div>
              <div>
                <label class="field-label" :for="`f-${row.key}`">脂肪 /100g</label>
                <input :id="`f-${row.key}`" v-model.number="row.fatPer100g" class="field-input !py-2 text-xs" type="number" min="0" max="100" step="0.1" placeholder="g" />
              </div>
            </div>

            <div class="mt-2 flex flex-wrap items-end gap-2">
              <div class="min-w-[160px] flex-1">
                <label class="field-label" :for="`note-${row.key}`">补充说明（给 AI 的提示，可空）</label>
                <input :id="`note-${row.key}`" v-model="row.note" class="field-input !py-2 text-xs" type="text" placeholder="例如：家里做的，一碗约 300g，偏油" />
              </div>
              <button
                type="button"
                class="btn-ghost !min-h-[38px] !px-3 text-xs"
                :disabled="row.estimating || row.foodName.trim().length === 0"
                @click="useAiEstimate(row)"
              >
                <Loader2 v-if="row.estimating" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
                <Bot v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
                {{ row.estimating ? 'AI 估算中…' : 'AI 估算营养' }}
              </button>
            </div>

            <p v-if="row.estimateBasis" class="mt-2 flex items-start gap-1.5 text-[11px] text-signal-cyan">
              <Sparkles :size="11" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
              {{ row.estimateBasis }}
            </p>
            <p v-if="row.estimateWarning" class="mt-1 flex items-start gap-1.5 text-[11px] text-signal-orange">
              <AlertTriangle :size="11" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
              {{ row.estimateWarning }}
            </p>
          </template>

          <p v-if="row.error" class="mt-2 flex items-center gap-1.5 text-[11px] text-signal-coral">
            <AlertTriangle :size="11" :stroke-width="2.2" aria-hidden="true" />
            {{ row.error }}
          </p>

          <p v-if="row.foodName.trim()" class="mt-2 text-[11px] text-ink-secondary">
            本条折算 ≈ <span class="tnum text-ink-primary">{{ formatInt(rowCalories(row)) }}</span> kcal
          </p>
        </li>
      </ul>

      <datalist id="composer-food-list">
        <option v-for="food in dictionary" :key="food.id" :value="food.foodName" />
      </datalist>

      <div class="flex flex-wrap items-center justify-between gap-3">
        <button type="button" class="chip cursor-pointer border-hairline text-ink-secondary transition hover:text-ink-primary" @click="addRow">
          <Plus :size="12" :stroke-width="2.2" aria-hidden="true" />
          再加一种食物
        </button>
        <p class="text-[11px] text-ink-muted">
          预览合计 <span class="tnum text-ink-primary">{{ formatInt(previewTotals.calories) }}</span> kcal ·
          P{{ previewTotals.protein.toFixed(1) }} / C{{ previewTotals.carbs.toFixed(1) }} / F{{ previewTotals.fat.toFixed(1) }} g
          <br />（预览仅供填表参考，落库数值由后端按每 100g × 克重 ÷ 100 计算）
        </p>
      </div>
    </div>

    <template #footer>
      <div class="flex flex-wrap items-center justify-end gap-2">
        <button type="button" class="btn-ghost !min-h-[40px] !px-4 text-xs" @click="emit('close')">取消</button>
        <button type="button" class="btn-ghost !min-h-[40px] !px-4 text-xs" :disabled="submitting" @click="saveAsTemplate">
          <Loader2 v-if="submitting" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
          <Save v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
          保存为常吃套餐
        </button>
        <button type="button" class="btn-primary !min-h-[40px] !px-5 text-xs" :disabled="submitting" @click="logNow">
          <Loader2 v-if="submitting" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
          <Utensils v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
          直接打卡到今天（不需要套餐）
        </button>
      </div>
      <p class="mt-2 flex items-center gap-1.5 text-[11px] text-ink-muted">
        <CheckCircle2 :size="11" :stroke-width="2.2" aria-hidden="true" />
        字典命中的食物以字典数据为准；自定义/AI 估算的食物会在账单上标注来源。
      </p>
    </template>
  </AppModal>
</template>