<script setup lang="ts">
import { computed, ref } from 'vue'
import { AlertTriangle, CheckCircle2, Loader2, Lock, ShieldCheck } from 'lucide-vue-next'
import StatusChip from '@/components/StatusChip.vue'
import { profileApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'
import { INJURY_BODY_PARTS, bodyPartLabel, severityLabel } from '@/theme/tokens'
import type { InjurySeverity, UserDigitalTwinVO, UserInjuryTag } from '@/api/types'

const props = defineProps<{ twin: UserDigitalTwinVO | null; loading?: boolean }>()
const emit = defineEmits<{ (event: 'changed'): void }>()

const busyPart = ref<string | null>(null)
const editingPart = ref<string | null>(null)
const draftSeverity = ref<InjurySeverity>('MILD')
const draftDescription = ref('')

interface PartState {
  part: string
  label: string
  injury: UserInjuryTag | null
}

/** 只有 ACTIVE 伤病会从后端返回，因此「未返回」即视为该部位当前无活跃伤病。 */
const parts = computed<PartState[]>(() =>
  INJURY_BODY_PARTS.map((part) => ({
    part,
    label: bodyPartLabel[part] ?? part,
    injury: props.twin?.activeInjuries.find((item) => item.bodyPart === part) ?? null,
  })),
)

const activeCount = computed(() => parts.value.filter((item) => item.injury !== null).length)

function startEdit(part: string): void {
  editingPart.value = part
  draftSeverity.value = 'MILD'
  draftDescription.value = ''
}

function cancelEdit(): void {
  editingPart.value = null
}

/** ACTIVE → RECOVERED（PATCH 状态接口）。 */
async function markRecovered(item: PartState): Promise<void> {
  if (!item.injury || busyPart.value) return
  busyPart.value = item.part
  try {
    await profileApi.setInjuryStatus(item.injury.id, 'RECOVERED')
    appStore.pushToast('success', `已将 ${item.label} 标记为已恢复`, '排课护栏将重新放行该部位的原本禁忌动作')
    emit('changed')
  } catch (error) {
    appStore.pushToast('error', '状态更新失败', toErrorMessage(error))
  } finally {
    busyPart.value = null
  }
}

/** 无 ACTIVE 伤病 → 新增/重新登记（POST 新增接口）。 */
async function submitInjury(item: PartState): Promise<void> {
  if (!props.twin || busyPart.value) return
  busyPart.value = item.part
  try {
    await profileApi.addInjury(props.twin.userId, {
      bodyPart: item.part,
      description: draftDescription.value || undefined,
      severity: draftSeverity.value,
    })
    appStore.pushToast(
      'success',
      `已登记 ${item.label} 伤病（${severityLabel[draftSeverity.value]}）`,
      '排课与打卡护栏会立即锁定该部位的禁忌动作',
    )
    cancelEdit()
    emit('changed')
  } catch (error) {
    appStore.pushToast('error', '登记失败', toErrorMessage(error))
  } finally {
    busyPart.value = null
  }
}
</script>

<template>
  <div class="flex h-full flex-col">
    <div v-if="loading && !twin" class="space-y-3">
      <div v-for="index in 3" :key="index" class="skeleton h-24 w-full" />
    </div>

    <template v-else>
      <div class="mb-4 flex flex-wrap items-center gap-2">
        <StatusChip :tone="activeCount > 0 ? 'coral' : 'acid'" live>
          <AlertTriangle v-if="activeCount > 0" :size="12" :stroke-width="2.2" aria-hidden="true" />
          <ShieldCheck v-else :size="12" :stroke-width="2.2" aria-hidden="true" />
          {{ activeCount > 0 ? `${activeCount} 个部位处于活跃伤病` : '全部部位健康' }}
        </StatusChip>
        <p class="text-xs text-ink-muted">点击卡片操作，护栏会在服务端二次校验</p>
      </div>

      <ul class="grid flex-1 grid-cols-1 gap-3 sm:grid-cols-2">
        <li
          v-for="item in parts"
          :key="item.part"
          :class="[
            'rounded-chip border p-3.5 transition duration-200 ease-kinetic',
            item.injury
              ? 'border-signal-coral/45 bg-signal-coral/[0.07]'
              : 'border-hairline bg-obsidian/40 hover:border-hairline-strong',
          ]"
        >
          <div class="flex items-start justify-between gap-2">
            <div class="min-w-0">
              <p class="flex items-center gap-1.5 text-sm font-medium text-ink-primary">
                {{ item.label }}
                <span
                  v-if="item.injury"
                  class="h-1.5 w-1.5 animate-breathe rounded-full bg-signal-coral"
                  aria-hidden="true"
                />
              </p>
              <p class="mt-0.5 text-[11px] text-ink-muted">{{ item.part }}</p>
            </div>
            <StatusChip :tone="item.injury ? 'coral' : 'muted'" :pulse="Boolean(item.injury)" live>
              {{ item.injury ? 'ACTIVE' : '正常' }}
            </StatusChip>
          </div>

          <template v-if="item.injury">
            <p class="mt-2.5 text-xs text-ink-secondary">
              严重程度
              <span class="text-signal-coral">{{ severityLabel[item.injury.severity] ?? item.injury.severity }}</span>
            </p>
            <p v-if="item.injury.description" class="mt-1 line-clamp-2 text-xs text-ink-muted">
              {{ item.injury.description }}
            </p>
            <p class="mt-2 flex items-start gap-1.5 text-[11px] leading-relaxed text-signal-coral/90">
              <Lock :size="11" :stroke-width="2.2" class="mt-0.5 shrink-0" aria-hidden="true" />
              排课护栏已自动锁定该部位禁忌动作
            </p>
            <button
              type="button"
              class="btn-danger-outline mt-3 w-full !min-h-[38px] text-xs"
              :disabled="busyPart !== null"
              @click="markRecovered(item)"
            >
              <Loader2 v-if="busyPart === item.part" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
              <CheckCircle2 v-else :size="13" :stroke-width="2.2" aria-hidden="true" />
              标记为已恢复
            </button>
          </template>

          <template v-else>
            <p class="mt-2.5 text-xs leading-relaxed text-ink-muted">
              当前无活跃伤病，该部位动作全部可选。
            </p>

            <div v-if="editingPart === item.part" class="mt-3 space-y-2">
              <div>
                <label class="field-label" :for="`severity-${item.part}`">严重程度</label>
                <select :id="`severity-${item.part}`" v-model="draftSeverity" class="field-input !py-2 text-xs">
                  <option value="MILD">轻度 · 训练中轻微不适</option>
                  <option value="MODERATE">中度 · 影响动作幅度</option>
                  <option value="SEVERE">重度 · 疼痛需就医</option>
                </select>
              </div>
              <div>
                <label class="field-label" :for="`desc-${item.part}`">描述（可空）</label>
                <input
                  :id="`desc-${item.part}`"
                  v-model="draftDescription"
                  class="field-input !py-2 text-xs"
                  type="text"
                  placeholder="例如：推举到顶部时前束刺痛"
                />
              </div>
              <div class="flex gap-2">
                <button
                  type="button"
                  class="btn-danger-outline flex-1 !min-h-[38px] text-xs"
                  :disabled="busyPart !== null"
                  @click="submitInjury(item)"
                >
                  <Loader2 v-if="busyPart === item.part" :size="13" :stroke-width="2.4" class="animate-spin" aria-hidden="true" />
                  {{ busyPart === item.part ? '登记中…' : '确认登记' }}
                </button>
                <button type="button" class="btn-ghost !min-h-[38px] !px-3 text-xs" @click="cancelEdit">取消</button>
              </div>
            </div>

            <button
              v-else
              type="button"
              class="btn-ghost mt-3 w-full !min-h-[38px] text-xs"
              @click="startEdit(item.part)"
            >
              <AlertTriangle :size="13" :stroke-width="2.2" aria-hidden="true" />
              登记伤病
            </button>
          </template>
        </li>
      </ul>

      <p class="mt-4 text-[11px] leading-relaxed text-ink-muted">
        说明：接口只返回 ACTIVE 状态的伤病，因此「正常」表示该部位当前没有活跃伤病记录；
        恢复后再次登记会生成新的记录，历史记录不会被覆盖。
      </p>
    </template>
  </div>
</template>