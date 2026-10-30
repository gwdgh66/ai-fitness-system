<script setup lang="ts">
import { ref } from 'vue'
import { AlertTriangle, Loader2, ShieldAlert, Trash2 } from 'lucide-vue-next'
import AppModal from '@/components/AppModal.vue'
import { nutritionApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { formatInt } from '@/utils/format'
import type { Id } from '@/api/types'

/**
 * 「清空今日饮食流水」按钮 + 二次确认弹窗。
 *
 * 语义：只删 **当前用户 + 指定日期** 的 diet_log（宏量目标、训练数据都不动）。
 * 抽成组件是为了让顶部导航栏与饮食页复用同一份确认逻辑，避免两处行为不一致。
 *
 * @property logCount     该日流水条数；不传则不做禁用判断（用于导航栏这种拿不到明细的位置）
 * @property logCalories  该日合计热量；不传则确认框只做通用提示
 * @property compact      紧凑模式（导航栏用）
 */
const props = defineProps<{
  userId: Id
  date: string
  logCount?: number
  logCalories?: number
  compact?: boolean
}>()

const emit = defineEmits<{ (event: 'cleared'): void }>()

const open = ref(false)
const clearing = ref(false)

const knownEmpty = () => props.logCount === 0

async function confirm(): Promise<void> {
  if (clearing.value) return
  clearing.value = true
  try {
    const result = await nutritionApi.clearDayLogs(props.userId, props.date)
    appStore.pushToast('success', `已清空 ${result.logDate} 的饮食流水`, result.message)
    open.value = false
    // 让全站重新拉数据（看板、饮食页、AI 舱的上下文都跟着刷新）
    appStore.state.refreshToken += 1
    emit('cleared')
  } catch (error) {
    appStore.pushToast('error', '清空失败', toErrorMessage(error))
  } finally {
    clearing.value = false
  }
}
</script>

<template>
  <button
    type="button"
    :class="[
      'chip cursor-pointer border-hairline text-ink-secondary transition duration-200 ease-kinetic hover:border-signal-coral/50 hover:text-signal-coral disabled:cursor-not-allowed disabled:opacity-40',
      compact ? '!px-2.5' : '',
    ]"
    :disabled="knownEmpty()"
    :title="knownEmpty() ? '今天还没有饮食流水' : `清空 ${date} 的饮食流水（只删当天、只删当前用户）`"
    @click="open = true"
  >
    <Trash2 :size="12" :stroke-width="2.2" aria-hidden="true" />
    <span v-if="compact" class="hidden lg:inline">清空今日流水</span>
    <span v-else>清空今日流水</span>
  </button>

  <AppModal
    :open="open"
    title="清空今日饮食流水"
    hint="只删当前用户、这一天的饮食打卡；宏量目标与训练数据都不受影响"
    width="max-w-md"
    @close="open = false"
  >
    <div class="space-y-4">
      <div class="rounded-chip border border-signal-coral/45 bg-signal-coral/10 p-3.5">
        <p class="flex items-center gap-1.5 text-xs font-medium text-signal-coral">
          <ShieldAlert :size="13" :stroke-width="2.2" aria-hidden="true" />
          <template v-if="logCount !== undefined">即将删除 {{ logCount }} 条流水</template>
          <template v-else>即将清空该日全部饮食流水</template>
        </p>
        <p class="mt-1.5 text-[11px] leading-relaxed text-signal-coral/90">
          {{ date }}
          <template v-if="logCalories !== undefined">
            · 合计 <span class="tnum">{{ formatInt(logCalories) }}</span> kcal
          </template>
        </p>
      </div>

      <ul class="space-y-2 text-xs text-ink-secondary">
        <li class="flex items-start gap-2">
          <span class="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-acid" aria-hidden="true" />
          只删除 <span class="tnum">{{ date }}</span> 这一天、当前用户（user {{ userId }}）的数据
        </li>
        <li class="flex items-start gap-2">
          <span class="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-acid" aria-hidden="true" />
          宏量目标、训练打卡与套餐模板都不会被删除
        </li>
        <li class="flex items-start gap-2 text-signal-coral">
          <AlertTriangle :size="12" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
          删除后不可恢复（本系统没有回收站），确认后今日账单从 0 开始
        </li>
      </ul>
    </div>

    <template #footer>
      <div class="flex items-center justify-end gap-2">
        <button type="button" class="btn-ghost !min-h-[40px] !px-4 text-xs" @click="open = false">取消</button>
        <button
          type="button"
          class="btn-danger-outline !min-h-[40px] !px-4 text-xs"
          :disabled="clearing"
          @click="confirm"
        >
          <Loader2 v-if="clearing" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
          <Trash2 v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
          {{ clearing ? '清空中…' : '确认清空今日流水' }}
        </button>
      </div>
    </template>
  </AppModal>
</template>