<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowUpRight, Loader2, Sparkles } from 'lucide-vue-next'
import StatusChip from '@/components/StatusChip.vue'
import { progressionApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { progressionMeta, muscleLabel } from '@/theme/tokens'
import type { Id, ProgressionRecommendationVO, ProgressionStatus } from '@/api/types'
import { formatKg, formatWeight } from '@/utils/format'

const props = defineProps<{ recommendations: ProgressionRecommendationVO[]; loading?: boolean }>()
const emit = defineEmits<{ (event: 'applied'): void }>()

const applyingItemId = ref<Id | null>(null)

const TONE: Record<ProgressionStatus, 'acid' | 'cyan' | 'coral' | 'muted'> = {
  OVERLOAD_READY: 'acid',
  MAINTAIN: 'cyan',
  DELOAD_NEEDED: 'coral',
  INSUFFICIENT_DATA: 'muted',
}

const sorted = computed(() =>
  [...props.recommendations].sort((a, b) => {
    const rank: Record<ProgressionStatus, number> = {
      OVERLOAD_READY: 0,
      DELOAD_NEEDED: 1,
      MAINTAIN: 2,
      INSUFFICIENT_DATA: 3,
    }
    return rank[a.status] - rank[b.status]
  }),
)

const actionableCount = computed(
  () => sorted.value.filter((item) => item.status === 'OVERLOAD_READY' || item.status === 'DELOAD_NEEDED').length,
)

/** 建议幅度 = 建议重量 − 计算基准（基准已把「最近一次实际重量」算进去）。 */
function delta(item: ProgressionRecommendationVO): number | null {
  if (item.suggestedWeightKg === null || item.workingWeightKg === null) return null
  return item.suggestedWeightKg - item.workingWeightKg
}

function canApply(item: ProgressionRecommendationVO): boolean {
  return item.status !== 'INSUFFICIENT_DATA' && item.suggestedWeightKg !== null
}

async function apply(item: ProgressionRecommendationVO): Promise<void> {
  if (applyingItemId.value !== null) return
  applyingItemId.value = item.itemId
  try {
    const result = await progressionApi.apply(item.itemId)
    appStore.pushToast('success', `已采纳「${result.exerciseName}」的进阶建议`, result.message)
    emit('applied')
  } catch (error) {
    appStore.pushToast('error', '采纳失败', toErrorMessage(error))
  } finally {
    applyingItemId.value = null
  }
}
</script>

<template>
  <div class="flex h-full flex-col">
    <div v-if="loading" class="space-y-2">
      <div v-for="index in 3" :key="index" class="skeleton h-16 w-full" />
    </div>

    <div v-else-if="sorted.length === 0" class="flex h-[160px] flex-col items-center justify-center gap-2 text-center">
      <p class="text-sm text-ink-secondary">还没有可分析的课表</p>
      <p class="text-xs text-ink-muted">需要「激活的周课表 + 至少 2 次同动作打卡」才能算出进阶建议</p>
    </div>

    <template v-else>
      <div class="mb-3 flex items-center gap-2">
        <Sparkles :size="14" :stroke-width="2" class="text-acid" aria-hidden="true" />
        <p class="text-xs text-ink-secondary">
          共 <span class="tnum text-ink-primary">{{ sorted.length }}</span> 个动作参与判定，其中
          <span class="tnum text-acid">{{ actionableCount }}</span> 个可立即采纳
        </p>
      </div>

      <ul class="-mr-2 max-h-[248px] space-y-2 overflow-y-auto pr-2">
        <li
          v-for="item in sorted"
          :key="item.itemId"
          class="rounded-chip border border-hairline bg-obsidian/40 p-3.5 transition duration-200 ease-kinetic hover:border-hairline-strong"
        >
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="flex min-w-0 items-center gap-2">
              <span class="truncate text-sm font-medium text-ink-primary">{{ item.exerciseName }}</span>
              <span v-if="item.targetMuscle" class="text-xs text-ink-muted">
                {{ muscleLabel[item.targetMuscle] ?? item.targetMuscle }}
              </span>
            </div>
            <StatusChip :tone="TONE[item.status]">
              {{ progressionMeta[item.status].label }}
            </StatusChip>
          </div>

          <div class="mt-2.5 flex flex-wrap items-baseline gap-x-4 gap-y-1">
            <p class="flex items-baseline gap-1">
              <span class="readout text-xl font-semibold text-ink-primary">
                {{ formatWeight(item.workingWeightKg) }}
              </span>
              <span class="text-xs text-ink-muted">kg 基准</span>
            </p>
            <p v-if="delta(item) !== null" class="flex items-baseline gap-1" :class="delta(item)! >= 0 ? 'text-acid' : 'text-signal-coral'">
              <ArrowUpRight :size="13" :stroke-width="2.4" aria-hidden="true" />
              <span class="readout text-xl font-semibold">
                {{ delta(item)! > 0 ? '+' : '' }}{{ formatKg(delta(item)) }}
              </span>
              <span class="text-xs text-ink-muted">kg</span>
            </p>
            <p class="text-xs text-ink-secondary">
              下次建议
              <span class="tnum text-ink-primary">{{ formatWeight(item.suggestedWeightKg) }}kg × {{ item.suggestedReps }}次</span>
            </p>
          </div>

          <p class="mt-2 text-xs leading-relaxed text-ink-muted">{{ item.reason }}</p>

          <div class="mt-3 flex items-center justify-between gap-3">
            <span class="text-xs text-ink-muted">{{ item.sampleSize }} 次打卡样本</span>
            <button
              type="button"
              class="btn-primary !min-h-[36px] !px-3.5 text-xs"
              :disabled="!canApply(item) || applyingItemId !== null"
              @click="apply(item)"
            >
              <Loader2
                v-if="applyingItemId === item.itemId"
                :size="13"
                :stroke-width="2.4"
                class="animate-spin"
                aria-hidden="true"
              />
              {{ applyingItemId === item.itemId ? '落库中' : '一键采纳晋级' }}
            </button>
          </div>
        </li>
      </ul>
    </template>
  </div>
</template>