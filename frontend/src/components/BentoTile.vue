<script setup lang="ts">
/**
 * Bento 磁贴容器。
 *
 * 圆角与内边距按 MASTER.md §4.1 的「卡片分级」规则：heroChart 用 16px（rounded-tile），
 * 指标芯片用 12px（rounded-chip）——全站不允许一个圆角用到底。
 */
withDefaults(
  defineProps<{
    /** 标题：普通字号、Sentence case，不做 ALL-CAPS 字距套路 */
    title?: string
    /** 单位或右侧说明（如「近 30 天」） */
    hint?: string
    /** 视觉密度：hero 用于跨列主卡 */
    scale?: 'hero' | 'standard' | 'chip'
    interactive?: boolean
  }>(),
  { scale: 'standard', interactive: false },
)
</script>

<template>
  <section
    :class="[
      'tile flex flex-col',
      scale === 'chip' ? 'rounded-chip p-4' : 'p-5',
      interactive ? 'tile-interactive' : '',
    ]"
  >
    <header v-if="title || $slots.action" class="mb-3 flex items-start justify-between gap-3">
      <div class="min-w-0">
        <h2 v-if="title" class="text-sm font-medium text-ink-primary">{{ title }}</h2>
        <p v-if="hint" class="mt-0.5 text-xs text-ink-muted">{{ hint }}</p>
      </div>
      <div class="shrink-0">
        <slot name="action" />
      </div>
    </header>
    <slot />
  </section>
</template>