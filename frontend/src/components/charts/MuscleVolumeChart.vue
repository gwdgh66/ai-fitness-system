<script setup lang="ts">
import { computed, ref } from 'vue'
import EChartBase from './EChartBase.vue'
import StatusChip from '@/components/StatusChip.vue'
import type { MuscleVolumeVO } from '@/api/types'
import { baseOption, glassTooltip, muscleColor, type EChartsOption } from '@/theme/echarts'
import { formatVolume, formatPercent } from '@/utils/format'
import { muscleLabel, palette } from '@/theme/tokens'

const props = defineProps<{ volume: MuscleVolumeVO | null; loading?: boolean }>()

const mode = ref<'rose' | 'radar'>('rose')

const muscles = computed(() => props.volume?.muscles ?? [])

const roseOption = computed<EChartsOption>(() => ({
  ...baseOption(),
  tooltip: {
    ...glassTooltip,
    trigger: 'item',
    formatter: (params: unknown) => {
      const item = params as { name: string; value: number; percent: number }
      return `${item.name}<br/>容量 ${item.value.toFixed(1)} kg（${item.percent}%）`
    },
  },
  series: [
    {
      type: 'pie',
      roseType: 'radius',
      radius: ['34%', '80%'],
      center: ['50%', '52%'],
      itemStyle: { borderColor: palette.bgSurface, borderWidth: 2, borderRadius: 4 },
      label: {
        color: palette.inkSecondary,
        fontSize: 11,
        formatter: '{b}',
      },
      labelLine: { lineStyle: { color: 'rgba(255,255,255,0.18)' }, length: 6, length2: 8 },
      data: muscles.value.map((item) => ({
        name: muscleLabel[item.targetMuscle] ?? item.targetMuscle,
        value: Number(item.totalVolume.toFixed(1)),
        itemStyle: { color: muscleColor(item.targetMuscle) },
      })),
    },
  ],
}))

const radarOption = computed<EChartsOption>(() => {
  const maxPercent = Math.max(20, ...muscles.value.map((item) => item.volumePercent))
  return {
    ...baseOption(),
    tooltip: { ...glassTooltip, trigger: 'item' },
    radar: {
      indicator: muscles.value.map((item) => ({
        name: `${muscleLabel[item.targetMuscle] ?? item.targetMuscle}`,
        max: Math.ceil(maxPercent / 5) * 5,
      })),
      center: ['50%', '54%'],
      radius: '66%',
      axisName: { color: palette.inkSecondary, fontSize: 11 },
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } },
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } },
      splitArea: { show: false },
    },
    series: [
      {
        type: 'radar',
        symbolSize: 5,
        data: [
          {
            value: muscles.value.map((item) => item.volumePercent),
            name: '容量占比',
            lineStyle: { color: palette.acidLime, width: 2 },
            itemStyle: { color: palette.acidLime },
            areaStyle: { color: 'rgba(204,255,0,0.16)' },
          },
        ],
      },
    ],
  }
})

const ariaSummary = computed(() => {
  if (muscles.value.length === 0) return '暂无肌群容量数据'
  return `近 ${props.volume?.from} 至 ${props.volume?.to} 各肌群训练容量：${muscles.value
    .map((item) => `${muscleLabel[item.targetMuscle] ?? item.targetMuscle} ${item.totalVolume}kg（${item.totalSets} 组，占 ${item.volumePercent}%）`)
    .join('；')}。总容量 ${props.volume?.totalVolume}kg，共 ${props.volume?.totalSets} 组。`
})
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="mb-3 flex items-center justify-between gap-2">
      <div class="flex items-center gap-1 rounded-full border border-hairline p-0.5">
        <button
          type="button"
          :class="[
            'cursor-pointer rounded-full px-3 py-1 text-xs transition duration-200 ease-kinetic',
            mode === 'rose' ? 'bg-acid-dim text-acid' : 'text-ink-secondary hover:text-ink-primary',
          ]"
          :aria-pressed="mode === 'rose'"
          @click="mode = 'rose'"
        >
          玫瑰图
        </button>
        <button
          type="button"
          :class="[
            'cursor-pointer rounded-full px-3 py-1 text-xs transition duration-200 ease-kinetic',
            mode === 'radar' ? 'bg-acid-dim text-acid' : 'text-ink-secondary hover:text-ink-primary',
          ]"
          :aria-pressed="mode === 'radar'"
          @click="mode = 'radar'"
        >
          均衡雷达
        </button>
      </div>
      <StatusChip tone="muted">
        共 {{ volume?.totalSets ?? 0 }} 组 ·
        {{ formatVolume(volume?.totalVolume).text }}{{ formatVolume(volume?.totalVolume).unit }}
      </StatusChip>
    </div>

    <div v-if="loading" class="skeleton h-[190px] w-full" />
    <div v-else-if="muscles.length === 0" class="flex h-[190px] items-center justify-center text-sm text-ink-muted">
      暂无容量数据
    </div>
    <EChartBase v-else :option="mode === 'rose' ? roseOption : radarOption" :summary="ariaSummary" height="190px" />

    <!-- 数值列表：图形之外的第二信息载体（不能只靠颜色区分肌群） -->
    <ul v-if="muscles.length > 0" class="mt-3 space-y-1.5">
      <li v-for="item in muscles" :key="item.targetMuscle" class="flex items-center gap-2 text-xs">
        <span
          class="h-2 w-2 shrink-0 rounded-full"
          :style="{ backgroundColor: muscleColor(item.targetMuscle) }"
          aria-hidden="true"
        />
        <span class="w-12 text-ink-secondary">{{ muscleLabel[item.targetMuscle] ?? item.targetMuscle }}</span>
        <span class="tnum flex-1 text-ink-primary">{{ item.totalSets }} 组</span>
        <span class="tnum text-ink-muted">{{ formatPercent(item.volumePercent) }}</span>
      </li>
    </ul>
  </div>
</template>