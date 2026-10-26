<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  Activity,
  AlertTriangle,
  CalendarCheck,
  Flame,
  Info,
  Loader2,
  Ruler,
  Target,
} from 'lucide-vue-next'
import BentoTile from '@/components/BentoTile.vue'
import CalorieRing from '@/components/CalorieRing.vue'
import ProgressionPanel from '@/components/ProgressionPanel.vue'
import StatusChip from '@/components/StatusChip.vue'
import MuscleVolumeChart from '@/components/charts/MuscleVolumeChart.vue'
import NutritionTrendChart from '@/components/charts/NutritionTrendChart.vue'
import StrengthTrendChart from '@/components/charts/StrengthTrendChart.vue'
import { dashboardApi, nutritionApi, progressionApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { dayTypeLabel, goalLabel } from '@/theme/tokens'
import { formatInt, formatVolume, formatWeight, toIsoDate } from '@/utils/format'
import type {
  DailyNutritionSummaryVO,
  DashboardOverviewVO,
  MuscleVolumeVO,
  NutritionTrendVO,
  ProgressionRecommendationVO,
  StrengthTrendVO,
} from '@/api/types'

const state = appStore.state

const overview = ref<DashboardOverviewVO | null>(null)
const summary = ref<DailyNutritionSummaryVO | null>(null)
const trend = ref<StrengthTrendVO | null>(null)
const volume = ref<MuscleVolumeVO | null>(null)
const nutrition = ref<NutritionTrendVO | null>(null)
const recommendations = ref<ProgressionRecommendationVO[]>([])
const loading = ref(true)
const loadError = ref('')

/** 环形图与胶囊需要的三大营养素：优先用当日营养汇总，缺失时退化为 0。 */
const macros = computed(() => ({
  protein: { consumed: summary.value?.consumedProteinG ?? 0, target: summary.value?.targetProteinG ?? null },
  carbs: { consumed: summary.value?.consumedCarbsG ?? 0, target: summary.value?.targetCarbsG ?? null },
  fat: { consumed: summary.value?.consumedFatG ?? 0, target: summary.value?.targetFatG ?? null },
}))

const volumeFormatted = computed(() => formatVolume(overview.value?.totalVolumeLast30Days))

const weekCompletion = computed(() => {
  const done = overview.value?.completedWorkoutsThisWeek ?? 0
  const planned = overview.value?.plannedTrainingDaysThisWeek ?? 0
  if (planned === 0) return 0
  return Math.min(1, done / planned)
})

async function loadAll(): Promise<void> {
  loading.value = true
  loadError.value = ''
  const userId = state.userId
  const results = await Promise.allSettled([
    dashboardApi.overview(userId),
    nutritionApi.summary(userId, toIsoDate(new Date())),
    dashboardApi.strengthTrend(userId),
    dashboardApi.muscleVolume(userId),
    dashboardApi.nutritionTrend(userId),
    progressionApi.recommendations(userId),
  ])

  const [overviewResult, summaryResult, trendResult, volumeResult, nutritionResult, progressionResult] = results
  if (overviewResult.status === 'fulfilled') overview.value = overviewResult.value
  if (summaryResult.status === 'fulfilled') summary.value = summaryResult.value
  if (trendResult.status === 'fulfilled') trend.value = trendResult.value
  if (volumeResult.status === 'fulfilled') volume.value = volumeResult.value
  if (nutritionResult.status === 'fulfilled') nutrition.value = nutritionResult.value
  if (progressionResult.status === 'fulfilled') recommendations.value = progressionResult.value

  const failures = results.filter((item) => item.status === 'rejected')
  if (failures.length > 0) {
    loadError.value = toErrorMessage((failures[0] as PromiseRejectedResult).reason)
    appStore.pushToast('error', `有 ${failures.length} 个看板接口加载失败`, loadError.value)
  }
  loading.value = false
}

onMounted(loadAll)
// 播种演示数据 / 采纳进阶建议后，全局 refreshToken 自增 → 看板重新拉数
watch(() => state.refreshToken, loadAll)
</script>

<template>
  <div class="space-y-3">
    <!-- 加载失败的显式提示：不允许空白假装成功 -->
    <div
      v-if="loadError && !loading"
      class="flex items-center gap-2 rounded-chip border border-signal-coral/40 bg-signal-coral/10 px-3.5 py-2.5 text-xs text-signal-coral"
      role="alert"
    >
      <AlertTriangle :size="14" :stroke-width="2.2" aria-hidden="true" />
      {{ loadError }}
      <button type="button" class="ml-auto cursor-pointer underline" @click="loadAll">重试</button>
    </div>

    <div class="grid-bento">
      <!-- ① 今日热量环与营养缺口 -->
      <BentoTile
        class="lg:col-span-4"
        title="今日营养缺口"
        :hint="summary?.dayType ? `${dayTypeLabel[summary.dayType] ?? summary.dayType} · ${summary.date}` : '今日'"
      >
        <CalorieRing
          :loading="loading"
          :consumed="overview?.consumedCaloriesToday ?? 0"
          :target="overview?.targetCaloriesToday ?? null"
          :remaining="overview?.remainingCaloriesToday ?? null"
          :protein="macros.protein"
          :carbs="macros.carbs"
          :fat="macros.fat"
        />
      </BentoTile>

      <!-- ② 本周训练完成度 -->
      <BentoTile class="lg:col-span-3" title="本周训练完成度" hint="按 ISO 周（周一起算）统计">
        <div v-if="loading" class="skeleton h-[120px] w-full" />
        <template v-else>
          <div class="flex items-baseline gap-1.5">
            <span class="readout text-5xl font-semibold text-ink-primary">
              {{ overview?.completedWorkoutsThisWeek ?? 0 }}
            </span>
            <span class="readout text-2xl text-ink-muted">/ {{ overview?.plannedTrainingDaysThisWeek ?? 0 }}</span>
            <span class="ml-1 text-xs text-ink-secondary">天</span>
          </div>

          <div class="mt-3 h-1.5 w-full overflow-hidden rounded-full" style="background: rgba(255, 255, 255, 0.07)">
            <div
              class="h-full rounded-full bg-acid transition-[width] duration-700 ease-kinetic"
              :style="{ width: `${weekCompletion * 100}%` }"
            />
          </div>

          <dl class="mt-4 space-y-2.5">
            <div class="flex items-center justify-between gap-2">
              <dt class="flex items-center gap-1.5 text-xs text-ink-secondary">
                <Flame :size="13" :stroke-width="2" class="text-signal-orange" aria-hidden="true" />
                近 30 天累计容量
              </dt>
              <dd class="readout text-lg text-ink-primary">
                {{ volumeFormatted.text }}<span class="ml-0.5 text-xs text-ink-secondary">{{ volumeFormatted.unit }}</span>
              </dd>
            </div>
            <div class="flex items-center justify-between gap-2">
              <dt class="flex items-center gap-1.5 text-xs text-ink-secondary">
                <CalendarCheck :size="13" :stroke-width="2" class="text-signal-cyan" aria-hidden="true" />
                打卡条数
              </dt>
              <dd class="tnum text-sm text-ink-primary">{{ overview?.workoutLogCountLast30Days ?? 0 }} 条</dd>
            </div>
          </dl>
        </template>
      </BentoTile>

      <!-- ③ 身体引擎指标 -->
      <BentoTile
        class="lg:col-span-2"
        title="身体引擎"
        :hint="overview ? (goalLabel[overview.fitnessGoal] ?? overview.fitnessGoal) : ''"
      >
        <div v-if="loading" class="skeleton h-[120px] w-full" />
        <template v-else>
          <div class="space-y-3.5">
            <div>
              <p class="flex items-center gap-1.5 text-xs text-ink-secondary">
                <Ruler :size="12" :stroke-width="2" aria-hidden="true" /> 当前体重
              </p>
              <p class="mt-1 flex items-baseline gap-1">
                <span class="readout text-3xl font-semibold text-ink-primary">{{ formatWeight(overview?.weightKg) }}</span>
                <span class="text-xs text-ink-secondary">kg</span>
              </p>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <p class="text-xs text-ink-secondary">BMR</p>
                <p class="readout mt-1 text-2xl font-semibold text-ink-primary">{{ formatInt(overview?.bmr) }}</p>
                <p class="text-[11px] text-ink-muted">kcal 基础代谢</p>
              </div>
              <div>
                <p class="text-xs text-ink-secondary">TDEE</p>
                <p class="readout mt-1 text-2xl font-semibold text-ink-primary">{{ formatInt(overview?.tdee) }}</p>
                <p class="text-[11px] text-ink-muted">kcal 总消耗</p>
              </div>
            </div>
            <div v-if="overview?.bodyFatPct" class="flex items-center justify-between gap-2">
              <span class="text-xs text-ink-secondary">体脂率</span>
              <span class="tnum text-sm text-ink-primary">{{ overview.bodyFatPct.toFixed(1) }}%</span>
            </div>
          </div>
        </template>
      </BentoTile>

      <!-- ④ 双渐进算法（亮眼卡片） -->
      <BentoTile
        class="lg:col-span-3"
        title="双渐进 · 今日进阶建议"
        hint="基准 = max(课表重量, 最近实际重量)"
      >
        <template #action>
          <StatusChip tone="acid">
            <Target :size="12" :stroke-width="2.2" aria-hidden="true" />
            {{ recommendations.length }} 个动作
          </StatusChip>
        </template>
        <ProgressionPanel :recommendations="recommendations" :loading="loading" @applied="loadAll" />
      </BentoTile>

      <!-- ⑤ 1RM 力量进阶曲线 -->
      <BentoTile class="lg:col-span-8" title="三大项估算 1RM 进阶曲线" hint="Epley 公式由后端确定性计算，不是模型估算">
        <template #action>
          <StatusChip tone="muted">近 90 天</StatusChip>
        </template>
        <StrengthTrendChart :trend="trend" :loading="loading" />
      </BentoTile>

      <!-- ⑥ 肌群容量均衡度 -->
      <BentoTile class="lg:col-span-4" title="肌群容量均衡度" hint="近 30 天，检查是否练偏科">
        <MuscleVolumeChart :volume="volume" :loading="loading" />
      </BentoTile>

      <!-- ⑦ 营养趋势 -->
      <BentoTile class="lg:col-span-12" title="热量与三大营养素摄入对比" hint="近 14 天 · 柱为营养素克数，线为热量 kcal">
        <template #action>
          <span class="hidden items-center gap-1.5 text-xs text-ink-muted sm:inline-flex">
            <Info :size="12" :stroke-width="2" aria-hidden="true" />
            虚线为当日目标
          </span>
        </template>
        <NutritionTrendChart :trend="nutrition" :loading="loading" />
      </BentoTile>
    </div>

    <p v-if="loading" class="flex items-center justify-center gap-2 py-2 text-xs text-ink-muted">
      <Loader2 :size="13" :stroke-width="2.2" class="animate-spin" aria-hidden="true" />
      正在读取看板数据…
    </p>
    <p v-else class="flex items-center justify-center gap-2 py-2 text-xs text-ink-muted">
      <Activity :size="13" :stroke-width="2" aria-hidden="true" />
      数据来自 Java 确定性聚合接口，AI 不参与任何数值计算
    </p>
  </div>
</template>