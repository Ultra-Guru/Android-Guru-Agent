import { db } from '@/lib/db'
import { after } from 'next/server'
import { buildSystemPrompt } from '@/lib/prompts'
import { memoryScore } from '@/lib/scoring'
import { streamText } from '@/lib/llm'
import { runDigest } from '@/lib/digest'

type Params = { params: Promise<{ id: string }> }

const HISTORY_LIMIT = 40
const MEMORY_INJECT = 5
const PROFILE_INJECT = 8

export const dynamic = 'force-dynamic'

function sse(obj: unknown): Uint8Array {
  return new TextEncoder().encode(`data: ${JSON.stringify(obj)}\n\n`)
}

export async function POST(req: Request, { params }: Params) {
  const { id } = await params
  const body = await req.json().catch(() => null)
  const regenerate = body?.regenerate === true
  const content = (body?.content || '').toString().trim()
  if (!regenerate && (!content || content.length > 24000)) {
    return new Response(JSON.stringify({ error: '消息内容无效（1 ~ 24000 字）' }), {
      status: 400,
      headers: { 'content-type': 'application/json' },
    })
  }

  const conv = await db.conversation.findUnique({
    where: { id },
    include: { workspace: true, messages: { orderBy: { createdAt: 'asc' } } },
  })
  if (!conv) {
    return new Response(JSON.stringify({ error: '对话不存在' }), {
      status: 404,
      headers: { 'content-type': 'application/json' },
    })
  }

  const mode: 'agent' | 'coding' = conv.mode === 'coding' ? 'coding' : 'agent'

  let history: Array<{ role: 'user' | 'assistant'; content: string }>
  let newTitle: string | null = null
  let userMsgId = ''

  if (regenerate) {
    // ── 重新生成：删除尾部连续的 assistant 消息，用既有历史重推 ──
    const msgs = [...conv.messages]
    const trailing: string[] = []
    while (msgs.length > 0 && msgs[msgs.length - 1].role === 'assistant') {
      trailing.push(msgs.pop()!.id)
    }
    if (msgs.length === 0) {
      return new Response(JSON.stringify({ error: '没有可重新生成的回复（缺少用户消息）' }), {
        status: 400,
        headers: { 'content-type': 'application/json' },
      })
    }
    if (trailing.length > 0) {
      await db.message.deleteMany({ where: { id: { in: trailing } } })
    }
    history = msgs.slice(-HISTORY_LIMIT).map((m) => ({
      role: m.role === 'assistant' ? ('assistant' as const) : ('user' as const),
      content: m.content,
    }))
  } else {
    // ── 常规发送：保存用户消息 + 自动标题 + 触碰工作区 ──
    const isFirst = conv.messages.length === 0
    const userMsg = await db.message.create({
      data: { conversationId: id, role: 'user', content },
    })
    userMsgId = userMsg.id
    if (isFirst && conv.title === '新对话') {
      newTitle = content.replace(/\s+/g, ' ').slice(0, 18) + (content.length > 18 ? '…' : '')
    }
    await db.conversation.update({
      where: { id },
      data: { updatedAt: new Date(), ...(newTitle ? { title: newTitle } : {}) },
    })
    await db.workspace.update({
      where: { id: conv.workspaceId },
      data: { lastUsedAt: new Date() },
    })
    history = conv.messages.slice(-HISTORY_LIMIT).map((m) => ({
      role: m.role === 'assistant' ? ('assistant' as const) : ('user' as const),
      content: m.content,
    }))
    history.push({ role: 'user', content })
  }

  // ── 组装上下文 ──
  const globalRules =
    (await db.setting.findUnique({ where: { key: 'globalRules' } }))?.value || ''

  const memories = (await db.memoryItem.findMany())
    .map((m) => ({ ...m, score: memoryScore(m) }))
    .sort((a, b) => b.score - a.score)
    .slice(0, MEMORY_INJECT)

  const profileItems = mode === 'coding'
    ? (await db.profileItem.findMany({ orderBy: { confidence: 'desc' }, take: PROFILE_INJECT }))
    : []

  // 注入即访问：递增 accessCount / 刷新 lastAccessedAt（诚实上报，反馈到评分召回）
  if (memories.length > 0) {
    await db.memoryItem.updateMany({
      where: { id: { in: memories.map((m) => m.id) } },
      data: { accessCount: { increment: 1 }, lastAccessedAt: new Date() },
    })
  }
  if (profileItems.length > 0) {
    await db.profileItem.updateMany({
      where: { id: { in: profileItems.map((p) => p.id) } },
      data: { accessCount: { increment: 1 }, lastAccessedAt: new Date() },
    })
  }

  const systemPrompt = buildSystemPrompt({
    mode,
    workspace: conv.workspace,
    globalRules,
    profileItems: profileItems.map((p) => ({ aspect: p.aspect, content: p.content, confidence: p.confidence })),
    memoryItems: memories.map((m) => ({ category: m.category, content: m.content })),
  })

  // ── SSE 流 ──
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      let closed = false
      const safeEnqueue = (chunk: Uint8Array) => {
        if (!closed) {
          try {
            controller.enqueue(chunk)
          } catch {
            closed = true
          }
        }
      }
      safeEnqueue(
        sse(
          regenerate
            ? { type: 'user', id: '', content: '', title: null, regenerated: true }
            : { type: 'user', id: userMsgId, content, title: newTitle }
        )
      )

      const full = await streamText(
        [{ role: 'system', content: systemPrompt }, ...history],
        (text) => safeEnqueue(sse({ type: 'delta', text })),
        { temperature: 0.7, maxTokens: 4096 }
      )

      if (full === null) {
        safeEnqueue(sse({ type: 'error', message: '模型暂时没有响应，请稍后重试。' }))
        closed = true
        try {
          controller.close()
        } catch {}
        return
      }

      const assistantMsg = await db.message.create({
        data: { conversationId: id, role: 'assistant', content: full },
      })
      await db.conversation.update({ where: { id }, data: { updatedAt: new Date() } })
      safeEnqueue(sse({ type: 'done', messageId: assistantMsg.id, title: newTitle }))
      closed = true
      try {
        controller.close()
      } catch {}

      // 消息后提炼（批量蒸馏）—— 绝不拖垮主流程；重新生成时以末条用户消息为锚
      const lastUserContent = history.length > 0 ? history[history.length - 1].content : ''
      after(async () => {
        await runDigest({
          mode,
          recentTurns: [
            { role: 'user', content: lastUserContent },
            { role: 'assistant', content: full },
          ],
        })
      })
    },
  })

  return new Response(stream, {
    headers: {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    },
  })
}
