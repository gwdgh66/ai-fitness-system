<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { TrendingUp } from 'lucide-vue-next'
import EChartBase from './EChartBase.vue'
import type { StrengthTrendVO } from '@/api/types'
import {
  areaGradient,
  baseOption,
  categoryAxis,
  glassTooltip,
  hexToRgba,
  muscleColor,
  valueAxis,
  type EChartsOption,
} from '@/theme/echarts'
import { palette } from '@/theme/tokens'
import { formatDayOnly, formatKg, formatWeight } from '@/utils/format'

const props = defineProps<{ trend: StrengthTrendVO | null; loading?: boolean }>()

const activeName = ref<string>('')

// 保持选中项在数据刷新后依然有效
watch(
  () => props.trend?.series.map((item) => item.exerciseName).join('|'),
  () => {
    const names = props.trend?.series.map((item) => item.exerciseName) ?? []
    if (names.length > 0 && !names.includes(activeName.value)) {
      activeName.value = names[0]
    }
  },
  { immediate: true },
)

const activeSeries = computed(
  () => props.trend?.series.find((item) => item.exerciseName === activeName.value) ?? null,
)

/** 1RM 序列里最后一个有值的点（用于「最新」读数与提升幅度）。 */
const latest = computed(() => {
  const values = activeSeries.value?.estimated1rm ?? []
  for (let index = values.length - 1; index >= 0; index -= 1) {
    const value = values[index]
    if (value !== null && value !== undefined) {
      const previous = values.slice(0, index).filter((item): item is number => item !== null && item !== undefined)
      const previousValue = previous.length > 0 ? previous[previous.length - 1] : null
      return { value, previousValue }
    }
  }
  return { value: null as number | null, previousValue: null as number | null }
})

const delta = computed(() => {
  const { value, previousValue } = latest.value
  if (value === null || previousValue === null) return null
  return value - previousValue
})

const color = computed(() => muscleColor(activeSeries.value?.targetMuscle))

const option = computed<EChartsOption>(() => {
  const dates = props.trend?.dates ?? []
  const values = activeSeries.value?.estimated1rm ?? []
  return {
    ...baseOption(),
    grid: { left: 4, right: 14, top: 18, bottom: 2, containLabel: true },
    tooltip: {
      ...glassTooltip,
      valueFormatter: (value) => `${formatKg(value as number)} kg`,
    },
    xAxis: categoryAxis(dates, (value: string) => formatDayOnly(value)),
    yAxis: valueAxis('kg', { min: ({ min }) => Math.max(0, Math.floor(min - 8)) }),
    series: [
      {
        name: activeSeries.value?.exerciseName ?? '1RM',
        type: 'line',
        smooth: 0.35,
        data: values,
        connectNulls: true,
        symbol: 'circle',
        symbolSize: 7,
        showSymbol: false,
        lineStyle: { width: 2.4, color: color.value },
        itemStyle: { color: color.value, borderColor: palette.bgObsidian, borderWidth: 2 },
        areaStyle: { color: areaGradient(color.value, 0.3) },
        emphasis: { focus: 'series', itemStyle: { borderColor: hexToRgba(color.value, 0.35) } },
      },
    ],
  }
})

const ariaSummary = computed(() => {
  const dates = props.trend?.dates ?? []
  const values = activeSeries.value?.estimated1rm ?? []
  const pairs = dates
    .map((date, index) => ({ date, value: values[index] }))
    .filter((item) => item.value !== null && item.value !== undefined)
    .map((item) => `${item.date} 为 ${formatKg(item.value)}kg`)
  if (pairs.length === 0) return `${activeName.value || '核心动作'} 暂无估算 1RM 数据`
  return `${activeName.value} 估算 1RM 趋势：${pairs.join('，')}`
})
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- 动作切换：胶囊 chip，选中态用酸橙绿 -->
    <div v-if="trend && trend.series.length > 0" class="mb-3 flex flex-wrap gap-2">
      <button
        v-for="item in trend.series"
        :key="item.exerciseName"
        type="button"
        :class="[
          'chip cursor-pointer transition duration-200 ease-kinetic',
          item.exerciseName === activeName
            ? 'border-acid/50 bg-acid-dim text-acid'
            : 'border-hairline text-ink-secondary hover:text-ink-primary',
        ]"
        :aria-pressed="item.exerciseName === activeName"
        @click="activeName = item.exerciseName"
      >
        {{ item.exerciseName }}
      </button>
    </div>

    <div class="mb-3 flex flex-wrap items-end gap-x-6 gap-y-2">
      <div>
        <p class="text-xs text-ink-muted">最新估算 1RM</p>
        <p class="mt-1 flex items-baseline gap-1">
          <span class="readout text-3xl font-semibold text-ink-primary">{{ formatWeight(latest.value) }}</span>
          <span class="text-xs text-ink-secondary">kg</span>
        </p>
      </div>
      <div v-if="delta !== null">
        <p class="text-xs text-ink-muted">较上次</p>
        <p class="mt-1 flex items-center gap-1">
          <TrendingUp :size="14" :stroke-width="2.2" class="text-acid" aria-hidden="true" />
          <span class="readout text-xl font-semibold text-acid">
            {{ delta > 0 ? '+' : '' }}{{ formatKg(delta) }}
          </span>
          <span class="text-xs text-ink-secondary">kg</span>
        </p>
      </div>
    </div>

    <div v-if="loading" class="skeleton h-[240px] w-full" />
    <div v-else-if="!trend || trend.dates.length === 0" class="flex h-[240px] items-center justify-center text-sm text-ink-muted">
      暂无训练打卡数据，点右上角「播种演示数据」可生成 14 天曲线
    </div>
    <EChartBase v-else :option="option" :summary="ariaSummary" height="240px" />
  </div>
</template>