/**
 * 极简 Markdown 渲染（仅用于 AI 教练回复的排版）。
 *
 * 安全策略：**先整体转义 HTML**，再对白名单语法做替换，因此不会执行模型输出里的任何标签/脚本。
 * 支持：标题（#/##/###）、无序与有序列表、`行内代码`、**加粗**、段落换行。
 */

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function inline(escaped: string): string {
  return escaped
    .replace(/`([^`\n]+)`/g, '<code class="rounded bg-elevated px-1.5 py-0.5 text-[12px] text-acid">$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong class="font-semibold text-ink-primary">$1</strong>')
}

export function markdownToHtml(source: string): string {
  if (!source) return ''
  const lines = escapeHtml(source.replace(/\r\n/g, '\n')).split('\n')
  const html: string[] = []
  let listBuffer: string[] = []
  let ordered = false

  const flushList = () => {
    if (listBuffer.length === 0) return
    const tag = ordered ? 'ol' : 'ul'
    const cls = ordered ? 'list-decimal' : 'list-disc'
    html.push(`<${tag} class="${cls} ml-4 space-y-1">${listBuffer.join('')}</${tag}>`)
    listBuffer = []
  }

  for (const rawLine of lines) {
    const line = rawLine.trimEnd()
    const heading = /^(#{1,4})\s+(.*)$/.exec(line)
    const orderedItem = /^\s*(\d+)[.、)]\s+(.*)$/.exec(line)
    const bulletItem = /^\s*[-*·]\s+(.*)$/.exec(line)

    if (heading) {
      flushList()
      const level = heading[1].length
      const size = level <= 2 ? 'text-sm' : 'text-[13px]'
      html.push(`<p class="${size} font-semibold text-ink-primary mt-2 mb-1">${inline(heading[2])}</p>`)
      continue
    }
    if (orderedItem) {
      if (!ordered) flushList()
      ordered = true
      listBuffer.push(`<li>${inline(orderedItem[2])}</li>`)
      continue
    }
    if (bulletItem) {
      if (ordered) flushList()
      ordered = false
      listBuffer.push(`<li>${inline(bulletItem[1])}</li>`)
      continue
    }
    flushList()
    if (line.trim() === '') {
      html.push('<div class="h-2"></div>')
      continue
    }
    html.push(`<p>${inline(line)}</p>`)
  }
  flushList()
  return html.join('')
}

/**
 * 在已转义的文本里高亮命中词（RAG 调试台用）。
 *
 * 用占位符两段式替换：先把命中处换成占位符，全部替换完再统一生成 `<mark>`，
 * 避免「后替换的词命中前一次插入的标签属性」这类嵌套破坏。
 */
export function highlightTerms(source: string, terms: string[]): string {
  const escaped = escapeHtml(source)
  const unique = Array.from(new Set(terms.map((term) => term.trim()).filter((term) => term.length > 0))).sort(
    (a, b) => b.length - a.length,
  )
  if (unique.length === 0) return escaped.replace(/\n/g, '<br />')

  const slots: string[] = []
  let working = escaped
  for (const term of unique) {
    const pattern = escapeHtml(term).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    working = working.replace(new RegExp(pattern, 'g'), (matched) => {
      slots.push(matched)
      return `\u0000${slots.length - 1}\u0000`
    })
  }
  const withMarks = working.replace(/\u0000(\d+)\u0000/g, (_all, index: string) => {
    const original = slots[Number(index)] ?? ''
    return `<mark class="rounded bg-acid-dim px-0.5 text-acid">${original}</mark>`
  })
  return withMarks.replace(/\n/g, '<br />')
}