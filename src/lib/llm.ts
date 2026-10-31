// LLM 调用薄封装 —— z-ai-web-dev-sdk 仅后端使用
import ZAI from 'z-ai-web-dev-sdk'

export async function getZai() {
  return ZAI.create()
}

/** 非流式补全：返回文本；失败返回 null（记忆/画像/游乐场等增强链路绝不拖垮主流程） */
export async function completeText(
  messages: Array<{ role: 'system' | 'user' | 'assistant'; content: string }>,
  opts: { maxTokens?: number; temperature?: number } = {}
): Promise<string | null> {
  try {
    const zai = await getZai()
    const completion = await zai.chat.completions.create({
      messages,
      thinking: { type: 'disabled' },
      ...(opts.temperature !== undefined ? { temperature: opts.temperature } : {}),
      ...(opts.maxTokens !== undefined ? { max_tokens: opts.maxTokens } : {}),
    })
    const content = completion?.choices?.[0]?.message?.content
    return typeof content === 'string' && content.trim() ? content.trim() : null
  } catch (err) {
    console.error('[llm] completeText failed:', (err as Error).message)
    return null
  }
}

/** 流式补全：onDelta 逐步回调；返回完整文本，失败返回 null */
export async function streamText(
  messages: Array<{ role: 'system' | 'user' | 'assistant'; content: string }>,
  onDelta: (text: string) => void,
  opts: { maxTokens?: number; temperature?: number } = {}
): Promise<string | null> {
  try {
    const zai = await getZai()
    const response = (await zai.chat.completions.create({
      messages,
      stream: true,
      thinking: { type: 'disabled' },
      ...(opts.temperature !== undefined ? { temperature: opts.temperature } : {}),
      ...(opts.maxTokens !== undefined ? { max_tokens: opts.maxTokens } : {}),
    })) as unknown as { body?: ReadableStream<Uint8Array> }

    if (!response?.body) {
      // 某些实现非 Response 形态 —— 降级为非流式
      const fallback = await completeText(messages, opts)
      if (fallback) onDelta(fallback)
      return fallback
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let full = ''

    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed.startsWith('data:')) continue
        const payload = trimmed.slice(5).trim()
        if (payload === '[DONE]') continue
        try {
          const json = JSON.parse(payload)
          const delta: string | undefined =
            json?.choices?.[0]?.delta?.content ?? json?.choices?.[0]?.message?.content
          if (typeof delta === 'string' && delta) {
            full += delta
            onDelta(delta)
          }
        } catch {
          // 忽略无法解析的行（心跳/注释）
        }
      }
    }
    return full.trim() ? full : null
  } catch (err) {
    console.error('[llm] streamText failed:', (err as Error).message)
    return null
  }
}

/** 宽容 JSON 解析：剥离围栏、截取首尾大括号 */
export function parseLooseJson<T = unknown>(raw: string | null): T | null {
  if (!raw) return null
  let text = raw.trim()
  const fence = text.match(/```(?:json)?\s*\n?([\s\S]*?)```/i)
  if (fence) text = fence[1].trim()
  const start = text.indexOf('{')
  const end = text.lastIndexOf('}')
  if (start === -1 || end === -1 || end <= start) return null
  try {
    return JSON.parse(text.slice(start, end + 1)) as T
  } catch {
    return null
  }
}
