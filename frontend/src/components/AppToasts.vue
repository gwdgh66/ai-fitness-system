<script setup lang="ts">
import { computed } from 'vue'
import { CheckCircle2, Info, XCircle } from 'lucide-vue-next'
import { appStore } from '@/stores/appStore'

const toasts = computed(() => appStore.state.toasts)

const TONE = {
  success: { icon: CheckCircle2, class: 'text-acid', bar: 'bg-acid' },
  error: { icon: XCircle, class: 'text-signal-coral', bar: 'bg-signal-coral' },
  info: { icon: Info, class: 'text-signal-cyan', bar: 'bg-signal-cyan' },
} as const
</script>

<template>
  <!-- 实时状态区：语义化播报，不抢焦点 -->
  <div
    class="pointer-events-none fixed bottom-5 left-1/2 z-50 flex w-[min(92vw,460px)] -translate-x-1/2 flex-col gap-2"
    role="status"
    aria-live="polite"
    aria-atomic="true"
  >
    <TransitionGroup
      enter-active-class="transition duration-250 ease-kinetic"
      enter-from-class="opacity-0 translate-y-3"
      leave-active-class="transition duration-200 ease-kinetic"
      leave-to-class="opacity-0 translate-y-2"
    >
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="tile pointer-events-auto flex items-start gap-3 overflow-hidden p-3.5"
      >
        <span :class="['mt-0.5 shrink-0', TONE[toast.tone].class]" aria-hidden="true">
          <component :is="TONE[toast.tone].icon" :size="18" :stroke-width="1.9" />
        </span>
        <div class="min-w-0 flex-1">
          <p class="text-sm font-medium text-ink-primary">{{ toast.title }}</p>
          <p v-if="toast.detail" class="mt-1 text-xs leading-relaxed text-ink-secondary">
            {{ toast.detail }}
          </p>
        </div>
        <button
          class="shrink-0 cursor-pointer rounded-md px-1.5 text-xs text-ink-muted transition hover:text-ink-primary"
          aria-label="关闭提示"
          @click="appStore.dismissToast(toast.id)"
        >
          ✕
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>