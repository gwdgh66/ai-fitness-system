<script setup lang="ts">
import { computed } from 'vue'
import EChartBase from './EChartBase.vue'
import type { NutritionTrendVO } from '@/api/types'
import { baseOption, categoryAxis, glassTooltip, valueAxis, type EChartsOption } from '@/theme/echarts'
import { dataColors, palette } from '@/theme/tokens'
import { formatDayOnly } from '@/utils/format'

const props = defineProps<{ trend: NutritionTrendVO | null; loading?: boolean }>()

const option = computed<EChartsOption>(() => {
  const dates = props.trend?.dates ?? []
  const target = props.trend?.targetCalories ?? []
  return {
    ...baseOption(),
    grid: { left: 4, right: 16, top: 34, bottom: 2, containLabel: true },
    legend: {
      top: 0,
      left: 0,
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 14,
      icon: 'roundRect',
      textStyle: { color: palette.inkSecondary, fontSize: 11 },
      data: ['蛋白质', '碳水', '脂肪', '摄入热量', '目标热量'],
    },
    tooltip: {
      ...glassTooltip,
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(255,255,255,0.03)' } },
    },
    xAxis: categoryAxis(dates, (value: string) => formatDayOnly(value)),
    yAxis: [
      valueAxis('营养素 g'),
      {
        ...valueAxis('热量 kcal'),
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '蛋白质',
        type: 'bar',
        stack: 'macro',
        barMaxWidth: 18,
        itemStyle: { color: dataColors.macro.protein, borderRadius: [0, 0, 0, 0] },
        data: props.trend?.consumedProteinG ?? [],
      },
      {
        name: '碳水',
        type: 'bar',
        stack: 'macro',
        barMaxWidth: 18,
        itemStyle: { color: dataColors.macro.carbs },
        data: props.trend?.consumedCarbsG ?? [],
      },
      {
        name: '脂肪',
        type: 'bar',
        stack: 'macro',
        barMaxWidth: 18,
        itemStyle: { color: dataColors.macro.fat, borderRadius: [4, 4, 0, 0] },
        data: props.trend?.consumedFatG ?? [],
      },
      {
        name: '摄入热量',
        type: 'line',
        yAxisIndex: 1,
        smooth: 0.3,
        symbol: 'circle',
        symbolSize: 6,
        showSymbol: false,
        lineStyle: { color: dataColors.macro.calories, width: 2 },
        itemStyle: { color: dataColors.macro.calories },
        data: props.trend?.consumedCalories ?? [],
      },
      {
        name: '目标热量',
        type: 'line',
        yAxisIndex: 1,
        smooth: 0,
        symbol: 'none',
        lineStyle: { color: dataColors.target, width: 1.6, type: 'dashed' },
        data: target,
      },
    ],
  }
})

const ariaSummary = computed(() => {
  const trend = props.trend
  if (!trend || trend.dates.length === 0) return '暂无营养趋势数据'
  const rows = trend.dates.map((date, index) => {
    const target = trend.targetCalories[index]
    return `${date}：摄入 ${trend.consumedCalories[index]} kcal${
      target ? ` / 目标 ${target} kcal` : '（无目标）'
    }，蛋白质 ${trend.consumedProteinG[index]}g、碳水 ${trend.consumedCarbsG[index]}g、脂肪 ${trend.consumedFatG[index]}g`
  })
  return `近 ${trend.dates.length} 天营养对比：${rows.join('；')}`
})

const daysWithTarget = computed(
  () => (props.trend?.targetCalories ?? []).filter((item) => item !== null && item !== undefined).length,
)
</script>

<template>
  <div class="flex h-full flex-col">
    <div v-if="loading" class="skeleton h-[250px] w-full" />
    <div v-else-if="!trend || trend.dates.length === 0" class="flex h-[250px] items-center justify-center text-sm text-ink-muted">
      暂无饮食打卡数据
    </div>
    <template v-else>
      <EChartBase :option="option" :summary="ariaSummary" height="250px" />
      <p class="mt-2 text-xs text-ink-muted">
        左轴为三大营养素克数（堆叠柱），右轴为热量 kcal（实线为实际摄入，虚线为当日目标）；
        {{ daysWithTarget }} / {{ trend.dates.length }} 天存在营养目标。
      </p>
    </template>
  </div>
</template>