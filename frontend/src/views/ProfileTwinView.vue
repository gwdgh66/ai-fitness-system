<script setup lang="ts">
import { computed } from 'vue'
import { Target, Wrench } from 'lucide-vue-next'
import BentoTile from '@/components/BentoTile.vue'
import InjuryMatrix from '@/components/InjuryMatrix.vue'
import MetricsConsole from '@/components/MetricsConsole.vue'
import StatusChip from '@/components/StatusChip.vue'
import { appStore } from '@/stores/appStore'
import { equipmentLabel, goalLabel } from '@/theme/tokens'
import { formatWeight } from '@/utils/format'

const state = appStore.state
const twin = computed(() => state.twin)

/** 体测档案保存后：重新拉取数字孪生，并广播刷新信号让看板同步。 */
async function handleChanged(): Promise<void> {
  await appStore.refreshAll()
}
</script>

<template>
  <div class="space-y-3">
    <!-- 概览条：当前档案摘要 -->
    <section class="tile flex flex-wrap items-center justify-between gap-3 px-5 py-3.5">
      <div class="flex min-w-0 items-center gap-3">
        <div class="min-w-0">
          <p class="truncate text-sm font-medium text-ink-primary">
            {{ twin?.nickname || '训练者' }}
            <span class="ml-1.5 text-xs text-ink-muted">#{{ state.userId }}</span>
          </p>
          <p class="mt-0.5 text-xs text-ink-muted">
            {{ twin ? `${twin.gender === 'MALE' ? '男' : '女'} · ${twin.age} 岁 · ${formatWeight(twin.heightCm)}cm · ${formatWeight(twin.weightKg)}kg` : '加载中…' }}
          </p>
        </div>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <StatusChip v-if="twin" tone="acid">
          <Target :size="12" :stroke-width="2.2" aria-hidden="true" />
          目标 {{ goalLabel[twin.fitnessGoal] ?? twin.fitnessGoal }}
        </StatusChip>
        <StatusChip v-if="twin" tone="cyan">
          <Wrench :size="12" :stroke-width="2.2" aria-hidden="true" />
          {{ equipmentLabel[twin.equipmentEnv] ?? twin.equipmentEnv }}
        </StatusChip>
        <StatusChip v-if="twin" tone="muted">BMR {{ twin.bmr }} · TDEE {{ twin.tdee }} kcal</StatusChip>
      </div>
    </section>

    <div class="grid-bento">
      <!-- 左：体测控制台 -->
      <BentoTile
        class="lg:col-span-7"
        title="体测控制台"
        hint="修改后保存，由后端重算 BMR / TDEE（前端不做任何公式推算）"
      >
        <MetricsConsole :twin="twin" :loading="state.twinLoading" @saved="handleChanged" />
      </BentoTile>

      <!-- 右：伤病红绿灯矩阵 -->
      <BentoTile
        class="lg:col-span-5"
        title="伤病红绿灯矩阵"
        hint="ACTIVE 部位会被排课护栏自动锁定禁忌动作"
      >
        <InjuryMatrix :twin="twin" :loading="state.twinLoading" @changed="handleChanged" />
      </BentoTile>
    </div>

    <p
      v-if="state.twinError"
      class="flex items-center gap-2 rounded-chip border border-signal-coral/40 bg-signal-coral/10 px-3.5 py-2.5 text-xs text-signal-coral"
      role="alert"
    >
      数字孪生加载失败：{{ state.twinError }}
      <button type="button" class="ml-auto cursor-pointer underline" @click="appStore.loadTwin()">重试</button>
    </p>
  </div>
</template>