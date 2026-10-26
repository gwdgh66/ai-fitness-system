<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    hint?: string
    /** 弹窗宽度（Tailwind 类） */
    width?: string
  }>(),
  { width: 'max-w-xl' },
)

const emit = defineEmits<{ (event: 'close'): void }>()

const panel = ref<HTMLDivElement | null>(null)

function close(): void {
  emit('close')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') close()
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      window.addEventListener('keydown', onKeydown)
      await nextTick()
      panel.value?.focus()
    } else {
      window.removeEventListener('keydown', onKeydown)
    }
  },
)

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition duration-200 ease-kinetic"
      enter-from-class="opacity-0"
      leave-active-class="transition duration-150 ease-kinetic"
      leave-to-class="opacity-0"
    >
      <div v-if="open" class="fixed inset-0 z-50 flex items-end justify-center p-0 sm:items-center sm:p-6">
        <!-- 遮罩：点击关闭 -->
        <div
          class="absolute inset-0 bg-obsidian/70 backdrop-blur-sm"
          aria-hidden="true"
          @click="close"
        />

        <div
          ref="panel"
          class="tile relative z-10 max-h-[92vh] w-full overflow-y-auto p-5 outline-none sm:max-h-[86vh]"
          :class="width"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
          tabindex="-1"
        >
          <header class="mb-4 flex items-start justify-between gap-3">
            <div class="min-w-0">
              <h2 class="text-sm font-medium text-ink-primary">{{ title }}</h2>
              <p v-if="hint" class="mt-0.5 text-xs text-ink-muted">{{ hint }}</p>
            </div>
            <button type="button" class="icon-btn !h-9 !w-9" aria-label="关闭弹窗" @click="close">
              <X :size="16" :stroke-width="2" aria-hidden="true" />
            </button>
          </header>

          <slot />

          <footer v-if="$slots.footer" class="mt-5 border-t border-hairline pt-4">
            <slot name="footer" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>