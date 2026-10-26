<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { formatInt, formatPercent } from '@/utils/format'
import { dataColors, palette } from '@/theme/tokens'

const props = defineProps<{
  consumed: number
  target: number | null
  remaining: number | null
  protein: { consumed: number; target: number | null }
  carbs: { consumed: number; target: number | null }
  fat: { consumed: number; target: number | null }
  loading?: boolean
}>()

const RADIUS = 62
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/** 进度比例：目标缺失时按 0 处理，避免出现「假进度」 */
const ratio = computed(() => {
  if (!props.target || props.target <= 0) return 0
  return Math.min(1, props.consumed / props.target)
})

const overTarget = computed(() => props.target !== null && props.consumed > props.target)

// 唯一编排时刻：首屏进度环从 0 扫到目标比例（900ms），reduced-motion 下直接到终态
const animatedRatio = ref(0)
onMounted(() => {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (reduce) {
    animatedRatio.value = ratio.value
    return
  }
  const start = performance.now()
  const duration = 900
  const tick = (now: number) => {
    const progress = Math.min(1, (now - start) / duration)
    // cubic-bezier(.16,1,.3,1) 近似：先快后缓
    animatedRatio.value = ratio.value * (1 - Math.pow(1 - progress, 3))
    if (progress < 1) requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
})

const dashOffset = computed(() => CIRCUMFERENCE * (1 - animatedRatio.value))
const ringColor = computed(() => (overTarget.value ? dataColors.danger : palette.acidLime))

interface Capsule {
  key: string
  label: string
  consumed: number
  target: number | null
  color: string
}

const capsules = computed<Capsule[]>(() => [
  { key: 'protein', label: '蛋白质', consumed: props.protein.consumed, target: props.protein.target, color: dataColors.macro.protein },
  { key: 'carbs', label: '碳水', consumed: props.carbs.consumed, target: props.carbs.target, color: dataColors.macro.carbs },
  { key: 'fat', label: '脂肪', consumed: props.fat.consumed, target: props.fat.target, color: dataColors.macro.fat },
])

function capsuleRatio(item: Capsule): number {
  if (!item.target || item.target <= 0) return 0
  return Math.min(1, item.consumed / item.target)
}

const ariaSummary = computed(() => {
  const base = `今日已摄入 ${formatInt(props.consumed)} 千卡，目标 ${props.target ? formatInt(props.target) : '未设置'} 千卡`
  const left = props.remaining === null ? '' : `，剩余 ${formatInt(props.remaining)} 千卡`
  const macros = capsules.value
    .map((item) => `${item.label} ${item.consumed.toFixed(1)} 克${item.target ? ` / 目标 ${item.target} 克` : '（无目标）'}`)
    .join('，')
  return `${base}${left}。${macros}`
})
</script>

<template>
  <div class="flex h-full flex-col">
    <div v-if="loading" class="skeleton h-[190px] w-full" />
    <template v-else>
      <div class="flex flex-col items-center gap-4 sm:flex-row sm:items-center sm:gap-6">
        <!-- 进度环 -->
        <div class="relative shrink-0" role="img" :aria-label="ariaSummary">
          <svg width="152" height="152" viewBox="0 0 152 152" aria-hidden="true">
            <circle cx="76" cy="76" :r="RADIUS" fill="none" stroke="rgba(255,255,255,0.07)" stroke-width="11" />
            <circle
              cx="76"
              cy="76"
              :r="RADIUS"
              fill="none"
              :stroke="ringColor"
              stroke-width="11"
              stroke-linecap="round"
              :stroke-dasharray="CIRCUMFERENCE"
              :stroke-dashoffset="dashOffset"
              transform="rotate(-90 76 76)"
            />
          </svg>
          <div class="absolute inset-0 flex flex-col items-center justify-center">
            <span class="readout text-[40px] font-semibold text-ink-primary">{{ formatInt(consumed) }}</span>
            <span class="mt-0.5 text-xs text-ink-secondary">
              / {{ target ? formatInt(target) : '未设目标' }} kcal
            </span>
          </div>
        </div>

        <!-- 缺口与三色胶囊 -->
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2">
            <span
              v-if="remaining !== null"
              :class="[
                'chip',
                overTarget ? 'border-signal-coral/45 bg-signal-coral/10 text-signal-coral' : 'border-acid/45 bg-acid-dim text-acid',
              ]"
              role="status"
              aria-atomic="true"
            >
              {{ overTarget ? '已超目标' : '还可摄入' }}
              <span class="tnum font-semibold">{{ formatInt(Math.abs(remaining)) }}</span>
              kcal
            </span>
            <span v-else class="chip border-hairline text-ink-secondary">尚未生成今日营养目标</span>
          </div>

          <ul class="mt-4 space-y-3">
            <li v-for="item in capsules" :key="item.key">
              <div class="mb-1.5 flex items-baseline justify-between gap-2 text-xs">
                <span class="text-ink-secondary">{{ item.label }}</span>
                <span class="tnum text-ink-primary">
                  {{ item.consumed.toFixed(1) }}
                  <span class="text-ink-muted">/ {{ item.target ?? '—' }} g</span>
                </span>
              </div>
              <div class="h-1.5 w-full overflow-hidden rounded-full" style="background: rgba(255, 255, 255, 0.07)">
                <div
                  class="h-full rounded-full transition-[width] duration-700 ease-kinetic"
                  :style="{ width: `${capsuleRatio(item) * 100}%`, backgroundColor: item.color }"
                  :title="`${item.label} ${formatPercent(capsuleRatio(item) * 100, 0)}`"
                />
              </div>
            </li>
          </ul>
        </div>
      </div>
    </template>
  </div>
</template>