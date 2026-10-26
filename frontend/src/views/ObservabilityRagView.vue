<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  Activity,
  AlertTriangle,
  BookOpen,
  Loader2,
  RefreshCw,
  Search,
  ShieldAlert,
  ShieldCheck,
  Timer,
  Zap,
} from 'lucide-vue-next'
import BentoTile from '@/components/BentoTile.vue'
import StatusChip from '@/components/StatusChip.vue'
import { aiApi, observabilityApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { highlightTerms } from '@/utils/markdown'
import { formatClock, formatInt } from '@/utils/format'
import type { AuditStatus, RagSearchResultVO, ToolAuditSummaryVO } from '@/api/types'

const state = appStore.state

const audit = ref<ToolAuditSummaryVO | null>(null)
const auditLoading = ref(false)
const auditError = ref('')
const limit = ref(50)
const statusFilter = ref<'ALL' | AuditStatus>('ALL')

const ragQuery = ref('左肩疼怎么练胸')
const ragResult = ref<RagSearchResultVO | null>(null)
const ragLoading = ref(false)
const ragError = ref('')
const ragTopK = ref(3)

const quickQueries = ['左肩疼怎么练胸', '什么时候需要减载周', 'RPE怎么控制', '双渐进法怎么加重']

const STATUS_TONE: Record<AuditStatus, 'acid' | 'coral' | 'orange'> = {
  SUCCESS: 'acid',
  GUARDRAIL_BLOCKED: 'coral',
  EXCEPTION: 'orange',
}

const STATUS_LABEL: Record<AuditStatus, string> = {
  SUCCESS: 'SUCCESS',
  GUARDRAIL_BLOCKED: 'GUARDRAIL_BLOCKED',
  EXCEPTION: 'EXCEPTION',
}

const successCount = computed(() => {
  const logs = audit.value?.logs ?? []
  return logs.filter((item) => item.status === 'SUCCESS').length
})

const filteredLogs = computed(() => {
  const logs = audit.value?.logs ?? []
  if (statusFilter.value === 'ALL') return logs
  return logs.filter((item) => item.status === statusFilter.value)
})

async function loadAudit(): Promise<void> {
  auditLoading.value = true
  auditError.value = ''
  try {
    audit.value = await observabilityApi.toolLogs(state.userId, limit.value)
  } catch (error) {
    auditError.value = toErrorMessage(error)
  } finally {
    auditLoading.value = false
  }
}

async function runRagSearch(query = ragQuery.value): Promise<void> {
  const text = query.trim()
  if (!text) return
  ragQuery.value = text
  ragLoading.value = true
  ragError.value = ''
  try {
    ragResult.value = await aiApi.ragSearch(text, ragTopK.value)
  } catch (error) {
    ragError.value = toErrorMessage(error)
  } finally {
    ragLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadAudit(), runRagSearch()])
})

watch(() => state.refreshToken, loadAudit)
watch(limit, loadAudit)

/** 结果摘要压缩：审计表只展示前 120 字符。 */
function summarize(text: string | null, max = 120): string {
  if (!text) return '—'
  const normalized = text.replace(/\s+/g, ' ').trim()
  return normalized.length > max ? `${normalized.slice(0, max)}…` : normalized
}
</script>

<template>
  <div class="space-y-3">
    <!-- 上半区：AOP 审计统计 -->
    <div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <BentoTile scale="chip" title="累计 Tool 调用">
        <p class="readout mt-1 text-4xl font-semibold text-ink-primary">{{ formatInt(audit?.totalCalls) }}</p>
        <p class="mt-1 text-[11px] text-ink-muted">AOP 切面自动记录</p>
      </BentoTile>
      <BentoTile scale="chip" title="成功执行">
        <p class="readout mt-1 text-4xl font-semibold text-acid">{{ formatInt(successCount) }}</p>
        <p class="mt-1 text-[11px] text-ink-muted">最近 {{ limit }} 条窗口内</p>
      </BentoTile>
      <BentoTile scale="chip" title="护栏拦截">
        <p class="readout mt-1 text-4xl font-semibold text-signal-coral">{{ formatInt(audit?.guardrailBlocked) }}</p>
        <p class="mt-1 text-[11px] text-ink-muted">返回 ERROR: 的调用</p>
      </BentoTile>
      <BentoTile scale="chip" title="平均执行耗时">
        <p class="mt-1 flex items-baseline gap-1">
          <span class="readout text-4xl font-semibold text-signal-cyan">{{ audit?.avgCostMs ?? '—' }}</span>
          <span class="text-xs text-ink-secondary">ms</span>
        </p>
        <p class="mt-1 text-[11px] text-ink-muted">最大 {{ audit?.maxCostMs ?? '—' }} ms</p>
      </BentoTile>
    </div>

    <BentoTile title="Tool 调用审计流水" hint="Spring AOP 环绕通知记录：类名 / 方法名 / 入参 JSON / 返回摘要 / 状态 / 耗时">
      <template #action>
        <div class="flex flex-wrap items-center gap-2">
          <select v-model.number="limit" class="field-input !w-24 !py-1.5 text-xs" aria-label="查询条数">
            <option :value="20">20 条</option>
            <option :value="50">50 条</option>
            <option :value="100">100 条</option>
          </select>
          <button type="button" class="chip cursor-pointer border-hairline text-ink-secondary transition hover:text-ink-primary" @click="loadAudit">
            <RefreshCw :size="12" :stroke-width="2.2" :class="auditLoading ? 'animate-spin' : ''" aria-hidden="true" />
            刷新
          </button>
        </div>
      </template>

      <div class="mb-3 flex flex-wrap items-center gap-2">
        <button
          v-for="option in ['ALL', 'SUCCESS', 'GUARDRAIL_BLOCKED', 'EXCEPTION'] as const"
          :key="option"
          type="button"
          :class="[
            'chip cursor-pointer transition duration-200 ease-kinetic',
            statusFilter === option
              ? option === 'GUARDRAIL_BLOCKED'
                ? 'border-signal-coral/50 bg-signal-coral/10 text-signal-coral'
                : option === 'EXCEPTION'
                  ? 'border-signal-orange/50 bg-signal-orange/10 text-signal-orange'
                  : 'border-acid/50 bg-acid-dim text-acid'
              : 'border-hairline text-ink-secondary hover:text-ink-primary',
          ]"
          :aria-pressed="statusFilter === option"
          @click="statusFilter = option"
        >
          {{ option === 'ALL' ? '全部' : STATUS_LABEL[option] }}
        </button>
      </div>

      <div
        v-if="auditError"
        class="mb-3 flex items-center gap-2 rounded-chip border border-signal-coral/40 bg-signal-coral/10 px-3.5 py-2.5 text-xs text-signal-coral"
        role="alert"
      >
        <AlertTriangle :size="14" :stroke-width="2.2" aria-hidden="true" />
        {{ auditError }}
        <button type="button" class="ml-auto cursor-pointer underline" @click="loadAudit">重试</button>
      </div>

      <div v-if="auditLoading && !audit" class="space-y-2">
        <div v-for="index in 4" :key="index" class="skeleton h-14 w-full" />
      </div>

      <div v-else-if="filteredLogs.length === 0" class="flex h-[140px] flex-col items-center justify-center gap-2 text-center">
        <ShieldCheck :size="20" :stroke-width="1.8" class="text-ink-muted" aria-hidden="true" />
        <p class="text-sm text-ink-secondary">该筛选条件下没有审计记录</p>
        <p class="text-xs text-ink-muted">去右上角 AI 教练舱说一句话（例如让它打卡），这里就会出现调用流水</p>
      </div>

      <ul v-else class="space-y-2">
        <li
          v-for="log in filteredLogs"
          :key="log.id"
          :class="[
            'rounded-chip border p-3.5 transition duration-200 ease-kinetic',
            log.status === 'GUARDRAIL_BLOCKED'
              ? 'border-signal-coral/45 bg-signal-coral/[0.07]'
              : log.status === 'EXCEPTION'
                ? 'border-signal-orange/40 bg-signal-orange/[0.06]'
                : 'border-hairline bg-obsidian/40',
          ]"
        >
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="flex min-w-0 flex-wrap items-center gap-2">
              <StatusChip :tone="STATUS_TONE[log.status]">
                <ShieldAlert v-if="log.status === 'GUARDRAIL_BLOCKED'" :size="12" :stroke-width="2.2" aria-hidden="true" />
                <Zap v-else :size="12" :stroke-width="2.2" aria-hidden="true" />
                {{ STATUS_LABEL[log.status] }}
              </StatusChip>
              <span class="truncate text-xs font-medium text-ink-primary">
                {{ log.toolClass }}.{{ log.toolName }}
              </span>
              <span v-if="log.userId" class="text-[11px] text-ink-muted">user {{ log.userId }}</span>
            </div>
            <div class="flex shrink-0 items-center gap-2 text-[11px] text-ink-muted">
              <span class="flex items-center gap-1">
                <Timer :size="11" :stroke-width="2.2" aria-hidden="true" />
                <span class="tnum text-ink-secondary">{{ log.costMs ?? '—' }} ms</span>
              </span>
              <span class="tnum">{{ formatClock(log.createdAt) }}</span>
            </div>
          </div>

          <dl class="mt-2.5 space-y-1.5 text-[11px]">
            <div class="flex gap-2">
              <dt class="w-14 shrink-0 text-ink-muted">入参</dt>
              <dd class="min-w-0 flex-1 break-all font-mono text-ink-secondary">{{ summarize(log.argsJson, 200) }}</dd>
            </div>
            <div class="flex gap-2">
              <dt class="w-14 shrink-0 text-ink-muted">返回</dt>
              <dd
                :class="[
                  'min-w-0 flex-1 break-all font-mono',
                  log.status === 'SUCCESS' ? 'text-ink-secondary' : 'text-signal-coral',
                ]"
              >
                {{ summarize(log.resultText, 260) }}
              </dd>
            </div>
          </dl>
        </li>
      </ul>
    </BentoTile>

    <!-- 下半区：RAG 测试舱 -->
    <BentoTile
      title="运动科学 RAG 混合检索引擎测试舱"
      hint="纯 Java 内存检索：BM25 + TF-IDF 余弦 + 领域关键词加权（0 Token 消耗、无外部向量库）"
    >
      <template #action>
        <StatusChip tone="cyan">
          <BookOpen :size="12" :stroke-width="2.2" aria-hidden="true" />
          {{ ragResult?.totalSegments ?? 8 }} 个知识切片
        </StatusChip>
      </template>

      <div class="flex flex-wrap items-end gap-2">
        <div class="min-w-[240px] flex-1">
          <label class="field-label" for="rag-query">检索问题</label>
          <div class="relative">
            <Search :size="14" :stroke-width="2" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-muted" aria-hidden="true" />
            <input
              id="rag-query"
              v-model="ragQuery"
              class="field-input pl-9"
              type="text"
              placeholder="例如：左肩疼怎么练胸"
              @keydown.enter="runRagSearch()"
            />
          </div>
        </div>
        <div>
          <label class="field-label" for="rag-topk">Top-K</label>
          <select id="rag-topk" v-model.number="ragTopK" class="field-input !w-24">
            <option :value="1">1</option>
            <option :value="3">3</option>
            <option :value="5">5</option>
          </select>
        </div>
        <button type="button" class="btn-primary !min-h-[44px] !px-5 text-xs" :disabled="ragLoading" @click="runRagSearch()">
          <Loader2 v-if="ragLoading" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
          <Search v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
          检索
        </button>
      </div>

      <div class="mt-3 flex flex-wrap gap-2">
        <button
          v-for="query in quickQueries"
          :key="query"
          type="button"
          :class="[
            'chip cursor-pointer transition duration-200 ease-kinetic',
            ragQuery === query ? 'border-acid/50 bg-acid-dim text-acid' : 'border-hairline text-ink-secondary hover:text-ink-primary',
          ]"
          @click="runRagSearch(query)"
        >
          {{ query }}
        </button>
      </div>

      <div
        v-if="ragError"
        class="mt-3 flex items-center gap-2 rounded-chip border border-signal-coral/40 bg-signal-coral/10 px-3.5 py-2.5 text-xs text-signal-coral"
        role="alert"
      >
        <AlertTriangle :size="14" :stroke-width="2.2" aria-hidden="true" />
        {{ ragError }}
      </div>

      <div v-if="ragLoading" class="mt-4 space-y-2">
        <div v-for="index in 3" :key="index" class="skeleton h-28 w-full" />
      </div>

      <div v-else-if="ragResult && ragResult.hits.length > 0" class="mt-4 space-y-3">
        <article
          v-for="hit in ragResult.hits"
          :key="hit.rank"
          class="rounded-chip border border-hairline bg-obsidian/40 p-4 transition duration-200 ease-kinetic hover:border-hairline-strong"
        >
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="flex min-w-0 items-center gap-2">
              <span class="readout text-lg font-semibold text-acid">#{{ hit.rank }}</span>
              <h3 class="truncate text-sm font-medium text-ink-primary">{{ hit.sectionTitle }}</h3>
            </div>
            <StatusChip tone="acid">
              score
              <span class="tnum font-semibold">{{ hit.score.toFixed(3) }}</span>
            </StatusChip>
          </div>

          <div class="mt-2 flex flex-wrap gap-2 text-[11px] text-ink-muted">
            <span>BM25 <span class="tnum text-ink-secondary">{{ hit.bm25.toFixed(3) }}</span></span>
            <span>余弦 <span class="tnum text-ink-secondary">{{ hit.cosine.toFixed(3) }}</span></span>
            <span>关键词加分 <span class="tnum text-ink-secondary">{{ hit.keywordBonus.toFixed(3) }}</span></span>
          </div>

          <div v-if="hit.matchedTerms.length > 0" class="mt-2 flex flex-wrap items-center gap-1.5">
            <span class="text-[11px] text-ink-muted">命中词</span>
            <span
              v-for="term in hit.matchedTerms"
              :key="term"
              class="rounded bg-acid-dim px-1.5 py-0.5 font-mono text-[11px] text-acid"
            >
              {{ term }}
            </span>
          </div>

          <p
            class="mt-3 whitespace-pre-wrap text-[11px] leading-relaxed text-ink-secondary"
            v-html="highlightTerms(hit.content, hit.matchedTerms)"
          />
        </article>
      </div>

      <div v-else-if="ragResult" class="mt-4 flex h-[100px] items-center justify-center text-sm text-ink-muted">
        没有召回任何切片，换个说法试试
      </div>
    </BentoTile>

    <p class="flex items-center justify-center gap-2 py-2 text-xs text-ink-muted">
      <Activity :size="13" :stroke-width="2" aria-hidden="true" />
      审计与检索都是纯后端能力：AOP 记录发生在工具真正执行时，RAG 检索不调用任何大模型接口
    </p>
  </div>
</template>