<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { echarts, type EChartsOption } from '@/theme/echarts'

const props = withDefaults(
  defineProps<{
    option: EChartsOption
    height?: string
    /**
     * 屏幕阅读器可读的图表摘要（无障碍：图形不能是唯一信息载体）。
     * 注意：不要命名为 ariaSummary —— Vue 会把 `aria-*` 属性当 ARIA 透传，导致类型检查不匹配。
     */
    summary: string
  }>(),
  { height: '260px' },
)

const container = ref<HTMLDivElement | null>(null)
// 用 ReturnType 取实例类型，避免依赖 echarts 内部类型导出名（5.x 与 6.x 命名不同）
type ChartInstance = ReturnType<typeof echarts.init>
const chart = shallowRef<ChartInstance | null>(null)
let observer: ResizeObserver | null = null

function render(): void {
  if (!chart.value) return
  // notMerge=true：切换动作/指标时避免残留上一次的系列
  chart.value.setOption(props.option, true)
}

onMounted(() => {
  if (!container.value) return
  chart.value = echarts.init(container.value, undefined, { renderer: 'canvas' })
  render()
  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(container.value)
})

watch(() => props.option, render, { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  chart.value?.dispose()
  chart.value = null
})
</script>

<template>
  <div class="w-full">
    <div ref="container" :style="{ height }" role="img" :aria-label="summary" />
    <p class="sr-only">{{ summary }}</p>
  </div>
</template>