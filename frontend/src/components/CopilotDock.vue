<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  ChevronRight,
  Eraser,
  Loader2,
  Send,
  Sparkles,
  Square,
  Zap,
} from 'lucide-vue-next'
import StatusChip from '@/components/StatusChip.vue'
import { aiChat } from '@/composables/useAiChat'
import { appStore } from '@/stores/appStore'
import { markdownToHtml } from '@/utils/markdown'
import { formatClock } from '@/utils/format'

const collapsed = ref(false)
const draft = ref('')
const showQuick = ref(false)
const scroller = ref<HTMLDivElement | null>(null)

const state = aiChat.state
const appState = appStore.state

/** 精选快捷指令：覆盖打卡、饮食、伤病替换、循证问答四条主线。 */
const quickPrompts = [
  '今天杠铃深蹲 105kg 做了 4组×6次 RPE 8，帮我打卡并算下 1RM',
  '午餐吃了 200g熟米饭 和 150g生鸡胸肉，帮我记到今天午饭里',
  '我左肩有点疼，帮我看看当前周课表，把伤肩的动作安全替换掉',
  '我目前卧推重量卡住了，按运动科学双渐进法和减载原则该怎么突破？',
]

const hasMessages = computed(() => state.messages.length > 0)
const injuryHint = computed(() =>
  (appState.twin?.activeInjuries ?? []).length > 0
    ? '已读取你的活跃伤病，回答会避开禁忌动作'
    : '尚未登记伤病，建议先在数字孪生页完善',
)
const statusText = computed(() => {
  if (state.streaming) return 'AI 正在回复…'
  return hasMessages.value ? '回复已完成，左侧数据已自动刷新' : '等待提问'
})

function render(content: string): string {
  return markdownToHtml(content)
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  if (scroller.value) {
    scroller.value.scrollTop = scroller.value.scrollHeight
  }
}

async function send(text: string): Promise<void> {
  const value = text.trim()
  if (!value || state.streaming) return
  draft.value = ''
  showQuick.value = false
  await aiChat.send(appState.userId, value)
  await scrollToBottom()
}

async function useQuick(prompt: string): Promise<void> {
  await send(prompt)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void send(draft.value)
  }
}

async function clearMemory(): Promise<void> {
  await aiChat.clear(appState.userId)
}

watch(() => state.messages.map((item) => item.content.length).join(','), scrollToBottom)
watch(() => showQuick.value, scrollToBottom)
</script>

<template>
  <aside
    :class="[
      'hidden shrink-0 flex-col border-l border-hairline bg-surface/60 backdrop-blur-md transition-[width] duration-250 ease-kinetic xl:flex',
      collapsed ? 'w-[56px]' : 'w-[380px]',
    ]"
    aria-label="AI 教练副驾驶"
  >
    <!-- 折叠态 -->
    <div v-if="collapsed" class="flex h-full flex-col items-center gap-4 py-4">
      <button type="button" class="icon-btn" aria-label="展开 AI 教练面板" @click="collapsed = false">
        <Sparkles :size="18" :stroke-width="1.9" aria-hidden="true" />
      </button>
      <span class="readout rotate-180 text-xs tracking-widest text-ink-muted" style="writing-mode: vertical-rl">
        AI COACH
      </span>
      <span
        v-if="state.streaming"
        class="h-2 w-2 animate-breathe rounded-full bg-acid"
        aria-hidden="true"
      />
    </div>

    <template v-else>
      <header class="flex items-center justify-between gap-2 border-b border-hairline px-4 py-3">
        <div class="flex items-center gap-2">
          <Sparkles :size="15" :stroke-width="2" class="text-acid" aria-hidden="true" />
          <h2 class="text-sm font-medium text-ink-primary">AI 教练</h2>
          <StatusChip v-if="state.streaming" tone="acid" pulse live>生成中</StatusChip>
        </div>
        <div class="flex items-center gap-1">
          <button
            type="button"
            class="icon-btn !h-9 !w-9"
            aria-label="清空会话记忆"
            title="清空会话记忆（Redis + 内存）"
            :disabled="state.streaming"
            @click="clearMemory"
          >
            <Eraser :size="16" :stroke-width="1.9" aria-hidden="true" />
          </button>
          <button type="button" class="icon-btn !h-9 !w-9" aria-label="收起 AI 教练面板" @click="collapsed = true">
            <ChevronRight :size="16" :stroke-width="2" aria-hidden="true" />
          </button>
        </div>
      </header>

      <!-- 消息区 -->
      <div ref="scroller" class="flex-1 space-y-3 overflow-y-auto px-4 py-4">
        <template v-if="!hasMessages">
          <p class="text-xs leading-relaxed text-ink-secondary">
            直接说人话就行：AI 会调用后端 <span class="text-ink-primary">@Tool</span> 真正写库
            （打卡、记饮食、改课表、登记伤病），落库后左侧视图会自动刷新。
          </p>
          <p class="text-xs text-ink-muted">{{ injuryHint }}</p>
          <p class="mt-1 text-xs text-ink-muted">点下面的快捷指令可以立刻体验完整链路：</p>
          <ul class="space-y-2">
            <li v-for="prompt in quickPrompts" :key="prompt">
              <button
                type="button"
                class="w-full cursor-pointer rounded-chip border border-hairline bg-obsidian/40 px-3 py-2.5 text-left text-xs leading-relaxed text-ink-secondary transition duration-200 ease-kinetic hover:border-acid/40 hover:text-ink-primary"
                @click="useQuick(prompt)"
              >
                {{ prompt }}
              </button>
            </li>
          </ul>
        </template>

        <template v-else>
          <div
            v-for="message in state.messages"
            :key="message.id"
            :class="message.role === 'user' ? 'flex justify-end' : 'flex justify-start'"
          >
            <div
              :class="[
                'max-w-[92%] rounded-chip border px-3.5 py-2.5 text-xs leading-relaxed',
                message.role === 'user'
                  ? 'border-acid/35 bg-acid-dim text-ink-primary'
                  : message.state === 'error'
                    ? 'border-signal-coral/45 bg-signal-coral/10 text-signal-coral'
                    : 'border-hairline bg-obsidian/50 text-ink-secondary',
              ]"
            >
              <div v-if="message.role === 'user'" class="whitespace-pre-wrap">{{ message.content }}</div>
              <template v-else>
                <div class="chat-md space-y-1" v-html="render(message.content)" />
                <span
                  v-if="message.state === 'streaming' && message.id === state.activeId"
                  class="ml-0.5 inline-block h-3 w-1.5 animate-breathe bg-acid align-middle"
                  aria-hidden="true"
                />
              </template>
              <p class="mt-1.5 flex items-center gap-2 text-[10px] text-ink-muted">
                <span>{{ formatClock(new Date(message.at).toISOString()) }}</span>
                <span v-if="message.degraded" class="text-signal-orange">同步降级</span>
                <span v-if="message.state === 'error'">失败</span>
              </p>
            </div>
          </div>
        </template>
      </div>

      <!-- 无障碍状态播报：只播报有意义的短语，不播报裸 token -->
      <p class="sr-only" role="status" aria-atomic="true">{{ statusText }}</p>

      <footer class="border-t border-hairline p-3">
        <button
          v-if="hasMessages"
          type="button"
          class="mb-2 flex w-full cursor-pointer items-center justify-between rounded-chip border border-hairline px-3 py-2 text-xs text-ink-secondary transition hover:text-ink-primary"
          :aria-expanded="showQuick"
          @click="showQuick = !showQuick"
        >
          <span class="flex items-center gap-1.5">
            <Zap :size="12" :stroke-width="2.2" aria-hidden="true" />
            快捷指令
          </span>
          <span class="text-ink-muted">{{ showQuick ? '收起' : '展开' }}</span>
        </button>

        <ul v-if="showQuick" class="mb-2 space-y-1.5">
          <li v-for="prompt in quickPrompts" :key="prompt">
            <button
              type="button"
              class="w-full cursor-pointer rounded-chip border border-hairline bg-obsidian/40 px-3 py-2 text-left text-[11px] leading-relaxed text-ink-secondary transition hover:border-acid/40 hover:text-ink-primary"
              @click="useQuick(prompt)"
            >
              {{ prompt }}
            </button>
          </li>
        </ul>

        <div class="flex items-end gap-2">
          <textarea
            v-model="draft"
            class="field-input max-h-32 min-h-[44px] flex-1 resize-none"
            rows="1"
            placeholder="例如：今天深蹲 100kg 4×8 RPE 8，帮我打卡"
            aria-label="给 AI 教练提问"
            :disabled="state.streaming"
            @keydown="onKeydown"
          />
          <button
            v-if="state.streaming"
            type="button"
            class="btn-ghost !min-h-[44px] !w-11 !px-0"
            aria-label="停止生成"
            @click="aiChat.stop()"
          >
            <Square :size="14" :stroke-width="2.4" aria-hidden="true" />
          </button>
          <button
            v-else
            type="button"
            class="btn-primary !min-h-[44px] !w-11 !px-0"
            aria-label="发送"
            :disabled="!draft.trim()"
            @click="send(draft)"
          >
            <Send :size="16" :stroke-width="2" aria-hidden="true" />
          </button>
        </div>
        <p class="mt-2 flex items-center justify-center gap-1.5 text-[11px] text-ink-muted">
          <Loader2 v-if="state.streaming" :size="11" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
          {{ statusText }}
        </p>
      </footer>
    </template>
  </aside>
</template>

<style scoped>
/* 聊天 Markdown 的排版微调（列表与代码块内间距） */
.chat-md :deep(ul),
.chat-md :deep(ol) {
  padding-left: 1rem;
}
.chat-md :deep(li) {
  margin-top: 0.15rem;
}
.chat-md :deep(p) {
  margin: 0;
}
</style>