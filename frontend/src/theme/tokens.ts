/**
 * 设计 Token 的 JS 侧镜像 —— 必须与 `design-system/MASTER.md` §2/§3 以及
 * `tailwind.config.js` 保持一致。组件与图表**禁止**写裸 hex，一律从这里取。
 */

export const palette = {
  bgObsidian: '#090A0F',
  bgSurface: '#12151E',
  bgElevated: '#1A1F2E',
  acidLime: '#CCFF00',
  acidLimeBright: '#D9FF3D',
  signalOrange: '#FF5E36',
  signalCyan: '#00E5FF',
  signalCoral: '#FF3366',
  signalViolet: '#A78BFA',
  inkPrimary: '#F4F6FA',
  inkSecondary: '#9BA3B4',
  inkMuted: '#5C6478',
  hairline: 'rgba(255,255,255,0.07)',
  hairlineStrong: 'rgba(255,255,255,0.14)',
} as const

/** 数据色板：同一语义在全站必须同色（MASTER.md §2.3）。 */
export const dataColors = {
  muscle: {
    LEGS: palette.signalOrange,
    BACK: palette.signalOrange,
    CHEST: palette.signalCyan,
    SHOULDERS: palette.signalCyan,
    ARMS: palette.acidLime,
    CORE: palette.acidLime,
    UNKNOWN: palette.inkMuted,
  } as Record<string, string>,
  macro: {
    protein: palette.signalCyan,
    carbs: palette.signalViolet,
    fat: palette.signalOrange,
    calories: palette.acidLime,
  },
  target: 'rgba(255,255,255,0.32)',
  danger: palette.signalCoral,
} as const

/** 进阶状态 → 视觉语义（徽章颜色与文案）。 */
export const progressionMeta = {
  OVERLOAD_READY: { label: '可加重', tone: 'acid' },
  MAINTAIN: { label: '维持', tone: 'cyan' },
  DELOAD_NEEDED: { label: '需减载', tone: 'coral' },
  INSUFFICIENT_DATA: { label: '样本不足', tone: 'muted' },
} as const

export const muscleLabel: Record<string, string> = {
  CHEST: '胸',
  BACK: '背',
  LEGS: '腿',
  SHOULDERS: '肩',
  ARMS: '手臂',
  CORE: '核心',
  UNKNOWN: '未标注',
}

export const bodyPartLabel: Record<string, string> = {
  LEFT_SHOULDER: '左肩',
  RIGHT_SHOULDER: '右肩',
  KNEE: '膝盖',
  LOWER_BACK: '下背部',
  WRIST: '手腕',
}

export const goalLabel: Record<string, string> = {
  MUSCLE_GAIN: '增肌',
  FAT_LOSS: '减脂',
  STRENGTH: '力量',
}

export const equipmentLabel: Record<string, string> = {
  GYM: '健身房',
  HOME_DUMBBELL: '家庭哑铃',
  BODYWEIGHT: '自重',
}

export const severityLabel: Record<string, string> = {
  MILD: '轻度',
  MODERATE: '中度',
  SEVERE: '重度',
}

export const mealTypeLabel: Record<string, string> = {
  BREAKFAST: '早餐',
  LUNCH: '午餐',
  DINNER: '晚餐',
  SNACK: '加餐',
}

export const dayTypeLabel: Record<string, string> = {
  HIGH_CARB: '高碳日',
  MEDIUM_CARB: '中碳日',
  LOW_CARB: '低碳日',
}

/** 可被伤病锁定的人体部位（与 exercise_dict.avoid_injury_parts 的取值域一致）。 */
export const INJURY_BODY_PARTS = ['LEFT_SHOULDER', 'RIGHT_SHOULDER', 'KNEE', 'LOWER_BACK', 'WRIST'] as const

export const ACTIVITY_LEVELS = [
  { value: 1.2, label: '久坐（几乎不运动）' },
  { value: 1.375, label: '轻度活动（每周 1~3 练）' },
  { value: 1.55, label: '中度活动（每周 3~5 练）' },
  { value: 1.725, label: '高度活动（每周 6~7 练）' },
  { value: 1.9, label: '极高活动（体力劳动/每日两练）' },
] as const