<script setup lang="ts">
import { computed } from 'vue'

type Tone = 'acid' | 'orange' | 'cyan' | 'coral' | 'muted' | 'violet'

const props = withDefaults(
  defineProps<{
    tone?: Tone
    /** 呼吸灯：用于 ACTIVE 伤病等需要持续提示的状态 */
    pulse?: boolean
    /** 无障碍：语义化播报文本（role=status 时使用） */
    live?: boolean
  }>(),
  { tone: 'muted', pulse: false, live: false },
)

const TONE_STYLE: Record<Tone, string> = {
  acid: 'border-acid/45 bg-acid-dim text-acid',
  orange: 'border-signal-orange/45 bg-signal-orange/10 text-signal-orange',
  cyan: 'border-signal-cyan/45 bg-signal-cyan/10 text-signal-cyan',
  coral: 'border-signal-coral/45 bg-signal-coral/10 text-signal-coral',
  violet: 'border-signal-violet/45 bg-signal-violet/10 text-signal-violet',
  muted: 'border-hairline text-ink-secondary',
}

const classes = computed(() => ['chip', TONE_STYLE[props.tone]])
</script>

<template>
  <span
    :class="classes"
    :role="live ? 'status' : undefined"
    :aria-atomic="live ? 'true' : undefined"
  >
    <span
      v-if="pulse"
      class="h-1.5 w-1.5 shrink-0 animate-breathe rounded-full bg-current"
      aria-hidden="true"
    />
    <slot />
  </span>
</template>