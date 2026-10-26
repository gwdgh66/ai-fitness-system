/** 数字与文案格式化 —— 全站统一，避免各视图各写一套。 */

/**
 * 千分位整数（热量、组数、计数器）。
 *
 * 参数放宽到 string：后端把 Java long 序列化为 JSON 字符串（避免雪花 ID 精度问题），
 * 像 totalCalls / costMs 这类计数器会以字符串形式返回。**不要用它格式化雪花 ID**
 * —— 内部会走 Number()，大整数会被四舍五入。
 */
export function formatInt(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const numeric = typeof value === 'string' ? Number(value) : value
  if (Number.isNaN(numeric)) return typeof value === 'string' ? value : '—'
  return Math.round(numeric).toLocaleString('zh-CN')
}

/** 一位小数（重量、容量）。 */
export function formatKg(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return value.toFixed(digits)
}

/** 容量换算：≥1000kg 显示为吨，否则显示 kg（看板要求「吨/kg 换算展示」）。 */
export function formatVolume(value: number | null | undefined): { text: string; unit: string } {
  if (value === null || value === undefined || Number.isNaN(value)) return { text: '—', unit: '' }
  if (value >= 1000) {
    return { text: (value / 1000).toFixed(value >= 10000 ? 0 : 1), unit: 't' }
  }
  return { text: value.toFixed(0), unit: 'kg' }
}

/** 带符号的幅度（+2.5kg / −10%）。 */
export function formatDelta(value: number | null | undefined, unit = 'kg', digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  const sign = value > 0 ? '+' : value < 0 ? '−' : '±'
  return `${sign}${Math.abs(value).toFixed(digits)}${unit}`
}

/** 百分比。 */
export function formatPercent(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return `${value.toFixed(digits)}%`
}

/** 重量展示：整数不带小数（40 而不是 40.0），有小数则保留。 */
export function formatWeight(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

/** 'YYYY-MM-DD' → 'M月D日'。 */
export function formatMonthDay(iso: string): string {
  const [, month, day] = iso.split('-')
  return `${Number(month)}月${Number(day)}日`
}

/** 'YYYY-MM-DD' → 'D日'（图表 X 轴用，紧凑）。 */
export function formatDayOnly(iso: string): string {
  const parts = iso.split('-')
  return `${Number(parts[2])}`
}

/** ISO 时间 → 'HH:mm'。 */
export function formatClock(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

/** 本周周一到周日的日期串（与后端「本周」口径一致：ISO 周，周一起算）。 */
export function currentWeekStart(today = new Date()): string {
  const date = new Date(today)
  const day = date.getDay() === 0 ? 7 : date.getDay()
  date.setDate(date.getDate() - (day - 1))
  return toIsoDate(date)
}

export function toIsoDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export const WEEKDAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] as const

export function weekdayLabel(dayOfWeek: number): string {
  return WEEKDAY_LABELS[dayOfWeek - 1] ?? `周${dayOfWeek}`
}