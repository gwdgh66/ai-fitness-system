<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  AlertTriangle,
  CalendarClock,
  Flame,
  Loader2,
  Plus,
  Search,
  ShieldAlert,
  Sparkles,
  Utensils,
} from 'lucide-vue-next'
import BentoTile from '@/components/BentoTile.vue'
import MealComposer from '@/components/MealComposer.vue'
import StatusChip from '@/components/StatusChip.vue'
import { catalogApi, nutritionApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { dataColors, dayTypeLabel, mealTypeLabel } from '@/theme/tokens'
import { formatInt, toIsoDate } from '@/utils/format'
import type {
  DailyNutritionSummaryVO,
  DayType,
  DietLog,
  DietSource,
  FoodNutritionDict,
  Id,
  MealTemplate,
  MealType,
} from '@/api/types'

const state = appStore.state
const MEAL_TYPES: MealType[] = ['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK']
const DAY_TYPES: { value: DayType; tone: 'orange' | 'muted' | 'cyan'; desc: string }[] = [
  { value: 'HIGH_CARB', tone: 'orange', desc: '练腿 / 大肌群日 · 补肌糖原' },
  { value: 'MEDIUM_CARB', tone: 'muted', desc: '常规训练日 · 均衡配比' },
  { value: 'LOW_CARB', tone: 'cyan', desc: '休息日 · 低碳高脂提升胰岛素敏感性' },
]

const today = toIsoDate(new Date())
const summary = ref<DailyNutritionSummaryVO | null>(null)
const templates = ref<MealTemplate[]>([])
const foods = ref<FoodNutritionDict[]>([])
const loading = ref(true)
const loadError = ref('')

// 记录餐次（套餐打卡与单品录入共用）
const mealType = ref<MealType>(defaultMealType())

// 日型切换
const switching = ref(false)
const dayTypeError = ref('')

// 套餐
const applyingTemplate = ref<Id | null>(null)
/** 自选食物组合弹窗（既能直接打卡，也能存成套餐） */
const composerOpen = ref(false)

// 食物检索与单品录入
const keyword = ref('')
const searching = ref(false)
const pickedFood = ref<FoodNutritionDict | null>(null)
const pickedWeight = ref(150)
const loggingFood = ref(false)

function defaultMealType(): MealType {
  const hour = new Date().getHours()
  if (hour < 10) return 'BREAKFAST'
  if (hour < 15) return 'LUNCH'
  if (hour < 21) return 'DINNER'
  return 'SNACK'
}

/** 账单上的来源标注：字典命中不标（默认可信），自定义与 AI 估算需要显式区分。 */
function sourceLabel(source: DietSource | string): string {
  if (source === 'AI_ESTIMATED') return 'AI 估算'
  if (source === 'CUSTOM') return '自定义'
  return '字典'
}

const currentDayType = computed<DayType>(
  () => (summary.value?.dayType as DayType | null) ?? 'MEDIUM_CARB',
)

/** 安全底线：后端热量下限 = max(1000, BMR × 0.8)。 */
const calorieFloor = computed(() => {
  const bmr = state.twin?.bmr ?? 0
  return Math.max(1000, Math.round(bmr * 0.8))
})

const macros = computed(() => [
  {
    key: 'protein',
    label: '蛋白质',
    consumed: summary.value?.consumedProteinG ?? 0,
    target: summary.value?.targetProteinG ?? null,
    remaining: summary.value?.remainingProteinG ?? null,
    color: dataColors.macro.protein,
  },
  {
    key: 'carbs',
    label: '碳水',
    consumed: summary.value?.consumedCarbsG ?? 0,
    target: summary.value?.targetCarbsG ?? null,
    remaining: summary.value?.remainingCarbsG ?? null,
    color: dataColors.macro.carbs,
  },
  {
    key: 'fat',
    label: '脂肪',
    consumed: summary.value?.consumedFatG ?? 0,
    target: summary.value?.targetFatG ?? null,
    remaining: summary.value?.remainingFatG ?? null,
    color: dataColors.macro.fat,
  },
])

const calorieRatio = computed(() => {
  const target = summary.value?.targetCalories ?? 0
  if (!target) return 0
  return Math.min(1, (summary.value?.consumedCalories ?? 0) / target)
})

/** 按餐次分组的今日流水。 */
const ledger = computed(() => {
  const logs = summary.value?.logs ?? []
  return MEAL_TYPES.map((type) => {
    const items = logs.filter((log) => log.mealType === type)
    return {
      type,
      label: mealTypeLabel[type] ?? type,
      items,
      calories: items.reduce((sum, item) => sum + (item.calories ?? 0), 0),
    }
  })
})

// ------------------------------------------------------------------
// 加载
// ------------------------------------------------------------------

async function refreshSummary(): Promise<void> {
  summary.value = await nutritionApi.summary(state.userId, today)
}

async function loadAll(): Promise<void> {
  loading.value = true
  loadError.value = ''
  const results = await Promise.allSettled([
    refreshSummary(),
    catalogApi.mealTemplates(state.userId).then((value) => (templates.value = value)),
    nutritionApi.foods().then((value) => (foods.value = value)),
  ])
  const failures = results.filter((item) => item.status === 'rejected')
  if (failures.length > 0) {
    loadError.value = toErrorMessage((failures[0] as PromiseRejectedResult).reason)
  }
  loading.value = false
}

onMounted(loadAll)
watch(() => state.refreshToken, loadAll)

// ------------------------------------------------------------------
// 碳水循环日型切换
// ------------------------------------------------------------------

async function switchDayType(dayType: DayType): Promise<void> {
  if (switching.value || dayType === currentDayType.value) return
  switching.value = true
  dayTypeError.value = ''
  try {
    summary.value = await nutritionApi.generateTarget({ userId: state.userId, targetDate: today, dayType })
    appStore.pushToast(
      'success',
      `今日目标已重算为${dayTypeLabel[dayType]}`,
      `热量 ${formatInt(summary.value.targetCalories)} kcal · 蛋白 ${summary.value.targetProteinG}g / 碳水 ${summary.value.targetCarbsG}g / 脂肪 ${summary.value.targetFatG}g`,
    )
  } catch (error) {
    // 例如低于 BMR 80% 的热量底线护栏
    const message = toErrorMessage(error)
    dayTypeError.value = message
    appStore.pushToast('error', '目标重算被护栏拦截', message)
  } finally {
    switching.value = false
  }
}

// ------------------------------------------------------------------
// 套餐
// ------------------------------------------------------------------

async function applyTemplate(template: MealTemplate): Promise<void> {
  if (applyingTemplate.value !== null) return
  applyingTemplate.value = template.id
  try {
    const result = await catalogServiceApply(template)
    appStore.pushToast(
      'success',
      `已一键打卡「${result.templateName}」`,
      `写入 ${result.loggedItems} 条饮食记录 · 合计 ${result.addedCalories} kcal（${mealTypeLabel[mealType.value] ?? mealType.value}）`,
    )
    await refreshSummary()
  } catch (error) {
    appStore.pushToast('error', '套餐打卡失败', toErrorMessage(error))
  } finally {
    applyingTemplate.value = null
  }
}

async function catalogServiceApply(template: MealTemplate) {
  return catalogApi.applyMealTemplate(template.id, today, mealType.value)
}

/** 自选组合弹窗完成（打卡或存套餐）后刷新套餐清单与当日账单。 */
async function onComposerDone(): Promise<void> {
  templates.value = await catalogApi.mealTemplates(state.userId)
  await refreshSummary()
}

// ------------------------------------------------------------------
// 食物检索与单品录入
// ------------------------------------------------------------------

let searchTimer: number | null = null

watch(keyword, (value) => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(async () => {
    searching.value = true
    try {
      foods.value = await nutritionApi.foods(value.trim() || undefined)
    } catch (error) {
      appStore.pushToast('error', '食物字典检索失败', toErrorMessage(error))
    } finally {
      searching.value = false
    }
  }, 240)
})

function pickFood(food: FoodNutritionDict): void {
  pickedFood.value = food
  pickedWeight.value = 100
}

/** 前端仅做「线性折算预览」，落库数值仍以后端为准（AGENTS.md 第 1 条）。 */
const previewCalories = computed(() => {
  if (!pickedFood.value) return 0
  return Math.round((pickedFood.value.caloriesPer100g * Number(pickedWeight.value || 0)) / 100)
})

async function logPickedFood(): Promise<void> {
  if (!pickedFood.value || loggingFood.value) return
  loggingFood.value = true
  try {
    const saved: DietLog = await nutritionApi.addLog({
      userId: state.userId,
      logDate: today,
      mealType: mealType.value,
      foodName: pickedFood.value.foodName,
      weightG: Number(pickedWeight.value),
    })
    appStore.pushToast(
      'success',
      `已记入${mealTypeLabel[mealType.value] ?? mealType.value}：${saved.foodName}`,
      `${saved.weightG}g → ${saved.calories} kcal · P${saved.proteinG} / C${saved.carbsG} / F${saved.fatG} g（后端按字典折算）`,
    )
    pickedFood.value = null
    await refreshSummary()
  } catch (error) {
    appStore.pushToast('error', '食物录入失败', toErrorMessage(error))
  } finally {
    loggingFood.value = false
  }
}
</script>

<template>
  <div class="space-y-3">
    <div
      v-if="loadError"
      class="flex items-center gap-2 rounded-chip border border-signal-coral/40 bg-signal-coral/10 px-3.5 py-2.5 text-xs text-signal-coral"
      role="alert"
    >
      <AlertTriangle :size="14" :stroke-width="2.2" aria-hidden="true" />
      {{ loadError }}
      <button type="button" class="ml-auto cursor-pointer underline" @click="loadAll">重试</button>
    </div>

    <!-- 顶部：碳水循环 + 营养素仪表盘 -->
    <div class="grid-bento">
      <BentoTile class="lg:col-span-5" title="碳水循环日型" :hint="`${today} · 切换会立即重算今日目标`">
        <div class="space-y-2">
          <button
            v-for="option in DAY_TYPES"
            :key="option.value"
            type="button"
            :class="[
              'flex w-full cursor-pointer items-center justify-between gap-3 rounded-chip border px-3.5 py-3 text-left transition duration-200 ease-kinetic',
              currentDayType === option.value
                ? option.tone === 'orange'
                  ? 'border-signal-orange/50 bg-signal-orange/10'
                  : option.tone === 'cyan'
                    ? 'border-signal-cyan/50 bg-signal-cyan/10'
                    : 'border-hairline-strong bg-elevated'
                : 'border-hairline bg-obsidian/40 hover:border-hairline-strong',
            ]"
            :aria-pressed="currentDayType === option.value"
            :disabled="switching"
            @click="switchDayType(option.value)"
          >
            <span class="min-w-0">
              <span class="flex items-center gap-1.5 text-xs font-medium text-ink-primary">
                {{ dayTypeLabel[option.value] }}
                <StatusChip v-if="currentDayType === option.value" :tone="option.tone">当前</StatusChip>
              </span>
              <span class="mt-0.5 block text-[11px] text-ink-muted">{{ option.desc }}</span>
            </span>
            <Loader2 v-if="switching && currentDayType !== option.value" :size="14" :stroke-width="2.4" class="animate-spin text-ink-muted" aria-hidden="true" />
          </button>

          <p class="flex items-start gap-1.5 text-[11px] leading-relaxed text-ink-muted">
            <ShieldAlert :size="11" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
            热量安全底线 max(1000, BMR×0.8) = {{ formatInt(calorieFloor) }} kcal；低于该值会被后端护栏拦截。
          </p>

          <div v-if="dayTypeError" class="rounded-chip border border-signal-coral/50 bg-signal-coral/10 p-3" role="alert">
            <p class="text-[11px] font-medium text-signal-coral">目标重算被护栏拦截</p>
            <p class="mt-1 whitespace-pre-wrap text-[11px] leading-relaxed text-signal-coral/90">{{ dayTypeError }}</p>
          </div>
        </div>
      </BentoTile>

      <BentoTile class="lg:col-span-7" title="今日营养素仪表盘" :hint="summary?.dayType ? dayTypeLabel[summary.dayType] : ''">
        <template #action>
          <StatusChip :tone="(summary?.consumedCalories ?? 0) > (summary?.targetCalories ?? 0) ? 'coral' : 'acid'">
            <Flame :size="12" :stroke-width="2.2" aria-hidden="true" />
            热量 {{ formatInt(summary?.consumedCalories) }} / {{ formatInt(summary?.targetCalories) }} kcal
          </StatusChip>
        </template>

        <div v-if="loading" class="skeleton h-[150px] w-full" />
        <template v-else>
          <div class="mb-4">
            <div class="mb-1.5 flex items-baseline justify-between gap-2 text-xs">
              <span class="text-ink-secondary">总热量进度</span>
              <span class="tnum text-ink-primary">{{ (calorieRatio * 100).toFixed(1) }}%</span>
            </div>
            <div class="h-2.5 w-full overflow-hidden rounded-full" style="background: rgba(255, 255, 255, 0.07)">
              <div
                class="h-full rounded-full transition-[width] duration-700 ease-kinetic"
                :style="{
                  width: `${calorieRatio * 100}%`,
                  backgroundColor: (summary?.consumedCalories ?? 0) > (summary?.targetCalories ?? 0) ? dataColors.danger : dataColors.macro.calories,
                }"
              />
            </div>
            <p class="mt-2 text-xs text-ink-muted">
              剩余可摄入
              <span class="tnum text-ink-primary">{{ formatInt(summary?.remainingCalories) }}</span> kcal
              <span v-if="summary?.targetCalories"> · 目标 {{ summary.dayType ? dayTypeLabel[summary.dayType] : '' }}</span>
            </p>
          </div>

          <ul class="space-y-3.5">
            <li v-for="macro in macros" :key="macro.key">
              <div class="mb-1.5 flex flex-wrap items-baseline justify-between gap-2 text-xs">
                <span class="text-ink-secondary">{{ macro.label }}</span>
                <span class="tnum text-ink-primary">
                  {{ macro.consumed.toFixed(1) }}
                  <span class="text-ink-muted">/ {{ macro.target ?? '—' }} g</span>
                  <span class="ml-2 text-ink-muted">剩余 {{ macro.remaining === null ? '—' : macro.remaining.toFixed(1) }} g</span>
                </span>
              </div>
              <div class="h-1.5 w-full overflow-hidden rounded-full" style="background: rgba(255, 255, 255, 0.07)">
                <div
                  class="h-full rounded-full transition-[width] duration-700 ease-kinetic"
                  :style="{
                    width: `${macro.target ? Math.min(100, (macro.consumed / macro.target) * 100) : 0}%`,
                    backgroundColor: macro.color,
                  }"
                />
              </div>
            </li>
          </ul>
        </template>
      </BentoTile>
    </div>

    <!-- 记录餐次（供套餐打卡与单品录入共用） -->
    <section class="tile flex flex-wrap items-center gap-3 px-5 py-3.5">
      <span class="flex items-center gap-1.5 text-xs text-ink-secondary">
        <CalendarClock :size="13" :stroke-width="2.2" aria-hidden="true" />
        记录到哪一餐
      </span>
      <div class="flex flex-wrap gap-2">
        <button
          v-for="type in MEAL_TYPES"
          :key="type"
          type="button"
          :class="[
            'chip cursor-pointer transition duration-200 ease-kinetic',
            mealType === type ? 'border-acid/50 bg-acid-dim text-acid' : 'border-hairline text-ink-secondary hover:text-ink-primary',
          ]"
          :aria-pressed="mealType === type"
          @click="mealType = type"
        >
          {{ mealTypeLabel[type] }}
        </button>
      </div>
    </section>

    <!-- 套餐一键打卡 -->
    <BentoTile title="常吃套餐 · 一键打卡" hint="套餐热量与三大营养素由后端逐项查字典汇总">
      <template #action>
        <button type="button" class="chip cursor-pointer border-hairline text-ink-secondary transition hover:text-ink-primary" @click="composerOpen = true">
          <Plus :size="12" :stroke-width="2.2" aria-hidden="true" />
          自选组合 / 新建套餐
        </button>
      </template>

      <div v-if="loading" class="skeleton h-24 w-full" />
      <div v-else-if="templates.length === 0" class="flex h-[100px] flex-col items-center justify-center gap-1 text-sm text-ink-muted">
        <p>还没有常吃套餐</p>
        <p class="text-xs">点右上角「自选组合 / 新建套餐」创建；也可以不建套餐，直接用上面的「自定义食物并直接打卡」</p>
      </div>
      <div v-else class="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        <article
          v-for="template in templates"
          :key="template.id"
          class="rounded-chip border border-hairline bg-obsidian/40 p-3.5 transition duration-200 ease-kinetic hover:border-hairline-strong"
        >
          <div class="flex items-start justify-between gap-2">
            <p class="min-w-0 text-sm font-medium text-ink-primary">{{ template.templateName }}</p>
            <StatusChip tone="muted">{{ mealTypeLabel[template.mealType] ?? template.mealType }}</StatusChip>
          </div>
          <div class="mt-2.5 flex items-baseline gap-1">
            <span class="readout text-2xl font-semibold text-ink-primary">{{ formatInt(template.totalCalories) }}</span>
            <span class="text-xs text-ink-muted">kcal</span>
          </div>
          <p class="mt-1 text-[11px] text-ink-muted">
            P{{ template.totalProteinG }} / C{{ template.totalCarbsG }} / F{{ template.totalFatG }} g
          </p>
          <button
            type="button"
            class="btn-primary mt-3 w-full !min-h-[38px] text-xs"
            :disabled="applyingTemplate !== null"
            @click="applyTemplate(template)"
          >
            <Loader2 v-if="applyingTemplate === template.id" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
            <Utensils v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
            {{ applyingTemplate === template.id ? '写入中…' : `一键打卡套餐（记入${mealTypeLabel[mealType]}）` }}
          </button>
        </article>
      </div>
    </BentoTile>

    <!-- 食物字典检索 + 单品录入 -->
    <BentoTile title="食物字典检索与单品录入" hint="27 种本地食物字典 · 热量与三大营养素按每 100g 折算">
      <div class="grid gap-4 lg:grid-cols-2">
        <div>
          <label class="field-label" for="food-search">搜索食物</label>
          <div class="relative">
            <Search :size="14" :stroke-width="2" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-muted" aria-hidden="true" />
            <input
              id="food-search"
              v-model="keyword"
              class="field-input pl-9"
              type="search"
              placeholder="例如：鸡胸肉 / 米饭 / 燕麦"
            />
          </div>

          <div v-if="searching" class="mt-3 skeleton h-16 w-full" />
          <ul v-else class="mt-3 max-h-[280px] space-y-1.5 overflow-y-auto pr-1">
            <li v-for="food in foods" :key="food.id">
              <button
                type="button"
                :class="[
                  'w-full cursor-pointer rounded-chip border px-3 py-2.5 text-left transition duration-200 ease-kinetic',
                  pickedFood?.id === food.id ? 'border-acid/50 bg-acid-dim' : 'border-hairline bg-obsidian/40 hover:border-hairline-strong',
                ]"
                @click="pickFood(food)"
              >
                <span class="flex items-center justify-between gap-2">
                  <span class="truncate text-xs font-medium text-ink-primary">{{ food.foodName }}</span>
                  <span class="tnum shrink-0 text-xs text-acid">{{ food.caloriesPer100g }} kcal</span>
                </span>
                <span class="mt-0.5 block text-[11px] text-ink-muted">
                  每 100g · P{{ food.proteinPer100g }} / C{{ food.carbsPer100g }} / F{{ food.fatPer100g }} g
                  <template v-if="food.unitHint"> · {{ food.unitHint }}</template>
                </span>
              </button>
            </li>
            <li v-if="foods.length === 0" class="py-4 text-center text-xs text-ink-muted">没有匹配的食物</li>
          </ul>

          <!-- 字典没有的食物也能记：自定义食物 + 可让 AI 给参考值，且不需要先建套餐 -->
          <button
            type="button"
            class="btn-ghost mt-2 w-full !min-h-[38px] text-xs"
            @click="composerOpen = true"
          >
            <Plus :size="13" :stroke-width="2.2" aria-hidden="true" />
            字典里没有？自定义食物并直接打卡
          </button>
        </div>

        <div class="rounded-chip border border-hairline bg-obsidian/40 p-4">
          <p class="mb-3 text-xs font-medium text-ink-primary">录入明细</p>
          <div v-if="!pickedFood" class="flex h-[120px] items-center justify-center text-center text-xs text-ink-muted">
            先在左侧选择一个食物
          </div>
          <template v-else>
            <p class="text-sm text-ink-primary">{{ pickedFood.foodName }}</p>
            <p class="mt-0.5 text-[11px] text-ink-muted">
              每 100g：{{ pickedFood.caloriesPer100g }} kcal · P{{ pickedFood.proteinPer100g }} / C{{ pickedFood.carbsPer100g }} / F{{ pickedFood.fatPer100g }} g
            </p>

            <div class="mt-3">
              <label class="field-label" for="food-weight">克重 (g)</label>
              <input id="food-weight" v-model.number="pickedWeight" class="field-input" type="number" min="1" max="2000" step="10" />
            </div>

            <div class="mt-3 flex items-baseline gap-1.5 rounded-chip border border-hairline bg-obsidian/60 px-3 py-2.5">
              <span class="text-[11px] text-ink-muted">折算预览</span>
              <span class="readout text-2xl font-semibold text-acid">{{ formatInt(previewCalories) }}</span>
              <span class="text-xs text-ink-secondary">kcal</span>
              <span class="ml-auto text-[11px] text-ink-muted">最终数值以后端折算为准</span>
            </div>

            <button
              type="button"
              class="btn-primary mt-3 w-full !min-h-[40px] text-xs"
              :disabled="loggingFood"
              @click="logPickedFood"
            >
              <Loader2 v-if="loggingFood" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
              <Sparkles v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
              {{ loggingFood ? '写入中…' : `记入${mealTypeLabel[mealType]}` }}
            </button>
          </template>
        </div>
      </div>
    </BentoTile>

    <!-- 今日账单流水 -->
    <BentoTile title="今日三餐账单流水" :hint="`${today} · 按餐次分组`">
      <template #action>
        <StatusChip tone="acid">合计 {{ formatInt(summary?.consumedCalories) }} kcal</StatusChip>
      </template>

      <div v-if="loading" class="skeleton h-32 w-full" />
      <div v-else class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <div v-for="group in ledger" :key="group.type" class="rounded-chip border border-hairline bg-obsidian/40 p-3.5">
          <div class="mb-2.5 flex items-center justify-between gap-2">
            <p class="text-xs font-medium text-ink-primary">{{ group.label }}</p>
            <span class="tnum text-xs text-acid">{{ group.calories }} kcal</span>
          </div>
          <ul v-if="group.items.length > 0" class="space-y-1.5">
            <li v-for="item in group.items" :key="item.id" class="flex items-baseline justify-between gap-2 text-[11px]">
              <span class="flex min-w-0 items-center gap-1.5">
                <span class="truncate text-ink-secondary">{{ item.foodName }}</span>
                <span
                  v-if="item.source && item.source !== 'DICTIONARY'"
                  :class="[
                    'shrink-0 rounded px-1 py-0.5 text-[10px]',
                    item.source === 'AI_ESTIMATED'
                      ? 'bg-signal-orange/15 text-signal-orange'
                      : 'bg-elevated text-ink-muted',
                  ]"
                  :title="item.source === 'AI_ESTIMATED' ? 'AI 估算后经人工确认的数值' : '用户自填的每 100g 数值'"
                >
                  {{ sourceLabel(item.source) }}
                </span>
              </span>
              <span class="tnum shrink-0 text-ink-muted">{{ item.weightG }}g · {{ item.calories }}</span>
            </li>
          </ul>
          <p v-else class="text-[11px] text-ink-muted">暂无记录</p>
        </div>
      </div>
    </BentoTile>

    <!-- 自选食物组合：可直接打卡（不需要先建套餐），也可存成常吃套餐 -->
    <MealComposer
      :open="composerOpen"
      :meal-type="mealType"
      :dictionary="foods"
      @close="composerOpen = false"
      @done="onComposerDone"
    />
  </div>
</template>