<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { AlertTriangle, DatabaseZap, Loader2, RefreshCw } from 'lucide-vue-next'
import StatusChip from '@/components/StatusChip.vue'
import { appStore } from '@/stores/appStore'
import { bodyPartLabel, severityLabel } from '@/theme/tokens'
import { formatClock, toIsoDate } from '@/utils/format'

const route = useRoute()
const state = appStore.state

const pageTitle = computed(() => route.meta.title ?? '核心看板')
const activeInjuries = computed(() => state.twin?.activeInjuries ?? [])
const freshness = computed(() => formatClock(state.lastRefreshedAt?.toISOString() ?? null))
const today = computed(() => {
  const iso = toIsoDate(new Date())
  const [, month, day] = iso.split('-')
  return `${Number(month)}月${Number(day)}日`
})
</script>

<template>
  <header class="flex flex-wrap items-center justify-between gap-3 border-b border-hairline bg-obsidian/70 px-5 py-3 backdrop-blur-md">
    <div class="flex min-w-0 items-center gap-3">
      <h1 class="truncate text-base font-medium text-ink-primary">{{ pageTitle }}</h1>
      <span class="hidden text-xs text-ink-muted sm:inline">{{ today }}</span>
    </div>

    <div class="flex flex-wrap items-center justify-end gap-2">
      <!-- 活跃伤病胶囊：珊瑚红 + 呼吸灯 -->
      <StatusChip v-for="injury in activeInjuries" :key="injury.id" tone="coral" pulse live>
        <AlertTriangle :size="12" :stroke-width="2.2" aria-hidden="true" />
        {{ bodyPartLabel[injury.bodyPart] ?? injury.bodyPart }}
        {{ severityLabel[injury.severity] ?? injury.severity }}
      </StatusChip>
      <StatusChip v-if="activeInjuries.length === 0 && state.twin" tone="muted">无活跃伤病</StatusChip>

      <!-- 数据新鲜度：不允许界面假装数据是实时的 -->
      <span class="hidden items-center gap-1.5 text-xs text-ink-muted md:inline-flex">
        <RefreshCw :size="12" :stroke-width="2" aria-hidden="true" />
        更新于 {{ freshness }}
      </span>

      <button
        type="button"
        class="btn-primary !min-h-[40px] !px-3.5 text-xs"
        :disabled="state.seeding"
        @click="appStore.seedDemoData()"
      >
        <Loader2 v-if="state.seeding" :size="14" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
        <DatabaseZap v-else :size="14" :stroke-width="2.2" aria-hidden="true" />
        {{ state.seeding ? '播种中…' : '一键播种演示数据' }}
      </button>
    </div>
  </header>
</template>