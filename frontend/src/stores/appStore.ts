import { reactive } from 'vue'
import { dashboardApi, profileApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import type { UserDigitalTwinVO } from '@/api/types'

export type ToastTone = 'success' | 'error' | 'info'

export interface Toast {
  id: number
  tone: ToastTone
  title: string
  detail?: string
}

interface AppState {
  userId: number
  twin: UserDigitalTwinVO | null
  twinLoading: boolean
  twinError: string
  seeding: boolean
  lastRefreshedAt: Date | null
  /** 演示数据播种后自增：各视图 watch 它来重新拉取自己的数据 */
  refreshToken: number
  toasts: Toast[]
}

const state = reactive<AppState>({
  // Step 8.1 为单用户演示模式；接入登录后由认证态注入
  userId: 1,
  twin: null,
  twinLoading: false,
  twinError: '',
  seeding: false,
  lastRefreshedAt: null,
  refreshToken: 0,
  toasts: [],
})

let toastSeq = 0

function pushToast(tone: ToastTone, title: string, detail?: string): void {
  const id = ++toastSeq
  state.toasts.push({ id, tone, title, detail })
  // 错误停留久一点，成功/信息 5s
  const ttl = tone === 'error' ? 9000 : 5000
  window.setTimeout(() => dismissToast(id), ttl)
}

function dismissToast(id: number): void {
  const index = state.toasts.findIndex((item) => item.id === id)
  if (index >= 0) {
    state.toasts.splice(index, 1)
  }
}

/** 拉取数字孪生（顶栏伤病胶囊与数字孪生页共用）。 */
async function loadTwin(): Promise<void> {
  state.twinLoading = true
  state.twinError = ''
  try {
    state.twin = await profileApi.getTwin(state.userId)
    state.lastRefreshedAt = new Date()
  } catch (error) {
    state.twinError = toErrorMessage(error)
  } finally {
    state.twinLoading = false
  }
}

/** 一键播种 14 天演示数据，然后让全站重新拉数据。 */
async function seedDemoData(): Promise<void> {
  if (state.seeding) return
  state.seeding = true
  try {
    const result = await dashboardApi.demoSeed(state.userId)
    pushToast(
      'success',
      '演示数据已播种',
      `课表 ${result.planItems} 个动作 · 训练打卡 ${result.workoutLogs} 条 · 饮食打卡 ${result.dietLogs} 条 · 营养目标 ${result.macroTargets} 天`,
    )
    state.refreshToken += 1
    await loadTwin()
  } catch (error) {
    pushToast('error', '播种失败', toErrorMessage(error))
  } finally {
    state.seeding = false
  }
}

/** 视图侧统一调用：刷新顶栏数据 + 广播刷新信号。 */
async function refreshAll(): Promise<void> {
  await loadTwin()
  state.refreshToken += 1
}

export const appStore = {
  /** 只读使用：视图通过 action 修改状态，不要直接改这个对象 */
  state,
  pushToast,
  dismissToast,
  loadTwin,
  seedDemoData,
  refreshAll,
}

export type { AppState }