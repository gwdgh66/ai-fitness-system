<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  Activity,
  Dumbbell,
  LayoutDashboard,
  Radar,
  Salad,
  type LucideIcon,
} from 'lucide-vue-next'

interface NavItem {
  path: string
  title: string
  icon: LucideIcon
}

const items: NavItem[] = [
  { path: '/dashboard', title: '核心看板', icon: LayoutDashboard },
  { path: '/twin', title: '数字孪生', icon: Activity },
  { path: '/workout', title: '周课表与打卡', icon: Dumbbell },
  { path: '/nutrition', title: '饮食与套餐', icon: Salad },
  { path: '/engineering', title: 'AI 工程与 RAG 调试台', icon: Radar },
]

const route = useRoute()
const currentPath = computed(() => route.path)
</script>

<template>
  <nav
    class="flex h-full w-[68px] shrink-0 flex-col items-center gap-2 border-r border-hairline bg-obsidian/80 py-4"
    aria-label="主导航"
  >
    <RouterLink
      to="/dashboard"
      class="mb-2 flex h-10 w-10 items-center justify-center rounded-chip border border-acid/40 bg-acid-dim"
      aria-label="Kinetic 首页"
    >
      <span class="readout text-lg font-bold text-acid">K</span>
    </RouterLink>

    <RouterLink
      v-for="item in items"
      :key="item.path"
      :to="item.path"
      class="group relative flex h-11 w-11 items-center justify-center rounded-chip transition duration-200 ease-kinetic"
      :class="
        currentPath === item.path
          ? 'bg-elevated text-ink-primary'
          : 'text-ink-muted hover:bg-elevated/70 hover:text-ink-secondary'
      "
      :aria-current="currentPath === item.path ? 'page' : undefined"
      :aria-label="item.title"
    >
      <!-- 选中态：酸橙绿左侧 2px 标 -->
      <span
        v-if="currentPath === item.path"
        class="absolute -left-[11px] h-6 w-[2px] rounded-full bg-acid"
        aria-hidden="true"
      />
      <component :is="item.icon" :size="19" :stroke-width="1.75" aria-hidden="true" />
      <!-- hover 浮出文字标签 -->
      <span
        class="pointer-events-none absolute left-[52px] z-40 whitespace-nowrap rounded-chip border border-hairline bg-elevated px-2.5 py-1 text-xs text-ink-primary opacity-0 shadow-lg transition duration-150 group-hover:opacity-100 group-focus-visible:opacity-100"
      >
        {{ item.title }}
      </span>
    </RouterLink>
  </nav>
</template>