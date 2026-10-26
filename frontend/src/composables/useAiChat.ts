import { reactive } from 'vue'
import { aiApi } from '@/api'
import { toErrorMessage } from '@/api/client'
import { appStore } from '@/stores/appStore'

export interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  content: string
  state: 'streaming' | 'done' | 'error'
  at: number
  /** 是否由同步接口降级产生 */
  degraded?: boolean
}

interface ChatState {
  messages: ChatMessage[]
  streaming: boolean
  /** 当前正在流式生成的助手消息 id */
  activeId: number | null
}

const state = reactive<ChatState>({
  messages: [],
  streaming: false,
  activeId: null,
})

let seq = 0
let controller: AbortController | null = null
let idleTimer: number | null = null

/** SSE 空闲超时：后端在生成时可能长时间没有 token，超时才中断。 */
const IDLE_TIMEOUT_MS = 90_000

function push(role: ChatMessage['role'], content: string, messageState: ChatMessage['state']): ChatMessage {
  const message: ChatMessage = { id: ++seq, role, content, state: messageState, at: Date.now() }
  state.messages.push(message)
  return message
}

interface SseBlock {
  event: string
  data: string
}

function parseBlock(block: string): SseBlock {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  return { event, data: dataLines.join('\n') }
}

/** SSE 的 data 是 JSON（如 {"content":"token"}）；解析失败时退化为原始文本。 */
function extractField(data: string, field: string): string {
  if (!data) return ''
  try {
    const parsed = JSON.parse(data) as Record<string, unknown>
    const value = parsed[field]
    return typeof value === 'string' ? value : ''
  } catch {
    return data
  }
}

function armIdleTimeout(): void {
  clearIdleTimeout()
  idleTimer = window.setTimeout(() => controller?.abort(), IDLE_TIMEOUT_MS)
}

function clearIdleTimeout(): void {
  if (idleTimer !== null) {
    window.clearTimeout(idleTimer)
    idleTimer = null
  }
}

/**
 * 通过 fetch 读取 SSE 流（EventSource 无法终止且只支持 GET 语义，这里需要可中断）。
 * 返回是否成功拿到过 token。
 */
async function streamChat(
  userId: number,
  text: string,
  signal: AbortSignal,
  onToken: (token: string) => void,
  onReset: () => void,
): Promise<void> {
  const url = `/api/ai/chat/stream?userId=${userId}&message=${encodeURIComponent(text)}`
  const response = await fetch(url, { headers: { Accept: 'text/event-stream' }, signal })
  if (!response.ok) {
    throw new Error(`流式接口返回 HTTP ${response.status}`)
  }
  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应读取')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let serverError = ''
  armIdleTimeout()

  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    armIdleTimeout()
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')

    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = parseBlock(buffer.slice(0, boundary))
      buffer = buffer.slice(boundary + 2)
      if (block.event === 'message') {
        const token = extractField(block.data, 'content')
        if (token) onToken(token)
      } else if (block.event === 'reset') {
        // 服务端在「工具执行完成」时会下发 reset：此前的 token 都是模型的内部独白
        // （“我先查一下食物字典…”），对用户没有意义，直接丢弃，只保留工具执行后的最终答复
        onReset()
      } else if (block.event === 'error') {
        serverError = extractField(block.data, 'message') || 'AI 服务返回错误'
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
  clearIdleTimeout()
  // done 事件或流关闭都视为结束；服务端错误则抛出（由上层决定是否降级）
  if (serverError) {
    throw new Error(serverError)
  }
}

async function send(userId: number, rawText: string): Promise<void> {
  const text = rawText.trim()
  if (!text || state.streaming) return

  push('user', text, 'done')
  const assistant = push('assistant', '', 'streaming')
  state.streaming = true
  state.activeId = assistant.id
  controller = new AbortController()

  let received = false
  let resetCount = 0
  try {
    await streamChat(
      userId,
      text,
      controller.signal,
      (token) => {
        received = true
        assistant.content += token
      },
      () => {
        resetCount += 1
        assistant.content = ''
      },
    )
    if (!received) {
      // 流正常结束但一个 token 都没有：按失败处理，走降级路径
      throw new Error('流式响应为空')
    }
  } catch (error) {
    if (!received) {
      // 优雅降级：流式在特殊网络/代理下不可用时改用同步接口，保证演示 100% 可用
      try {
        const answer = await aiApi.chat({ userId, message: text })
        assistant.content = answer
        assistant.degraded = true
        appStore.pushToast('info', '流式通道不可用，已自动降级为同步对话', toErrorMessage(error))
      } catch (fallbackError) {
        assistant.content = toErrorMessage(fallbackError)
        assistant.state = 'error'
      }
    } else {
      assistant.content += `\n\n[流式中断] ${toErrorMessage(error)}`
      assistant.state = 'error'
    }
  } finally {
    clearIdleTimeout()
    if (assistant.state === 'streaming') {
      assistant.state = assistant.content ? 'done' : 'error'
    }
    if (!assistant.content) {
      assistant.content = resetCount > 0
        ? '（模型这次只产生了内部推理，没有给出最终答复，请再问一次）'
        : '（未收到任何内容）'
      assistant.state = 'error'
    }
    state.streaming = false
    state.activeId = null
    controller = null
    // 核心机制：AI 可能已写库（打卡/改课表/记饮食/登记伤病），广播全局刷新让左侧视图自动跳变
    await appStore.refreshAll()
  }
}

/** 中断当前流式生成。 */
function stop(): void {
  controller?.abort()
  controller = null
  state.streaming = false
  clearIdleTimeout()
}

/** 清空对话（同时通知服务端清除 Redis + 内存会话上下文）。 */
async function clear(userId: number): Promise<void> {
  stop()
  state.messages = []
  try {
    await aiApi.clearMemory(userId)
    appStore.pushToast('success', '会话记忆已清空', 'Redis 与内存中的对话上下文都已重置')
  } catch (error) {
    appStore.pushToast('error', '清空记忆失败', toErrorMessage(error))
  }
}

export const aiChat = {
  state,
  send,
  stop,
  clear,
}