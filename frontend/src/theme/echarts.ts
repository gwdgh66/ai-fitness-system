import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, RadarChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'
import { dataColors, palette } from './tokens'

// 按需注册：只打包用到的图表与组件（ECharts 5 保持 bundle 可控）
echarts.use([
  LineChart,
  BarChart,
  PieChart,
  RadarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  MarkLineComponent,
  CanvasRenderer,
])

export { echarts }
export type { EChartsOption }

/** 玻璃拟态 Tooltip（暗色定制，MASTER.md §5.4）。 */
export const glassTooltip = {
  backgroundColor: 'rgba(10,12,18,0.92)',
  borderColor: 'rgba(255,255,255,0.10)',
  borderWidth: 1,
  padding: [10, 12] as [number, number],
  textStyle: { color: palette.inkPrimary, fontSize: 12, fontFamily: 'Outfit, system-ui, sans-serif' },
  extraCssText:
    'backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); border-radius: 12px; box-shadow: 0 12px 32px rgba(0,0,0,.5);',
  axisPointer: {
    type: 'line' as const,
    lineStyle: { color: palette.acidLime, width: 1, type: 'solid' as const, opacity: 0.6 },
  },
}

/** 去掉噪点的类目轴：无轴线、无刻度（MASTER.md §5.4）。 */
export function categoryAxis(dates: string[], labelFormatter?: (value: string) => string) {
  return {
    type: 'category' as const,
    data: dates,
    boundaryGap: true,
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: {
      color: palette.inkMuted,
      fontSize: 11,
      fontFamily: 'Outfit, system-ui, sans-serif',
      formatter: labelFormatter,
    },
    splitLine: { show: false },
  }
}

/** 数值轴：仅保留极淡虚线横向网格。 */
export function valueAxis(
  name?: string,
  options: { min?: number | ((value: { min: number; max: number }) => number); max?: number } = {},
) {
  return {
    type: 'value' as const,
    name,
    nameTextStyle: { color: palette.inkMuted, fontSize: 11, padding: [0, 0, 6, 0] },
    min: options.min,
    max: options.max,
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: palette.inkMuted, fontSize: 11, fontFamily: 'Outfit, system-ui, sans-serif' },
    splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)', type: 'dashed' as const } },
  }
}

/** 面积渐变填充。 */
export function areaGradient(color: string, topOpacity = 0.28) {
  const rgba = hexToRgba(color, topOpacity)
  return new echarts.graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: rgba },
    { offset: 1, color: hexToRgba(color, 0) },
  ])
}

/** #RRGGBB → rgba()。 */
export function hexToRgba(hex: string, alpha: number): string {
  const value = hex.replace('#', '')
  const r = parseInt(value.slice(0, 2), 16)
  const g = parseInt(value.slice(2, 4), 16)
  const b = parseInt(value.slice(4, 6), 16)
  return `rgba(${r},${g},${b},${alpha})`
}

export function muscleColor(muscle: string | null | undefined): string {
  return dataColors.muscle[muscle ?? 'UNKNOWN'] ?? palette.inkMuted
}

/** 统一图表容器基础配置。 */
export function baseOption(): EChartsOption {
  return {
    backgroundColor: 'transparent',
    animationDuration: 420,
    animationEasing: 'cubicOut',
    textStyle: { fontFamily: 'Outfit, system-ui, sans-serif', color: palette.inkSecondary },
    tooltip: glassTooltip,
    grid: { left: 8, right: 12, top: 28, bottom: 4, containLabel: true },
  }
}