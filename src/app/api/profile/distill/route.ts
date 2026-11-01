import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { buildProfileDistillUserMessage, PROFILE_DISTILL_SYSTEM } from '@/lib/prompts'
import { completeText, parseLooseJson } from '@/lib/llm'
import { CONSOLIDATE_THRESHOLD, similarity } from '@/lib/scoring'

const ASPECTS = ['tech-stack', 'coding-style', 'preferences', 'domains', 'habits', 'goals']

interface Action {
  op: 'new' | 'update'
  old?: string
  aspect?: string
  content?: string
  confidence?: number
  evidence?: string
}

/** 手动触发：扫描全部编码模式对话，批量提炼用户画像 */
export async function POST() {
  const codingConvs = await db.conversation.findMany({
    where: { mode: 'coding' },
    include: { messages: { orderBy: { createdAt: 'asc' } } },
  })
  const lines: string[] = []
  for (const conv of codingConvs) {
    if (!conv.messages.length) continue
    lines.push(`## 对话《${conv.title}》`)
    for (const m of conv.messages) {
      const role = m.role === 'user' ? '用户' : '助手'
      lines.push(`${role}：${m.content.slice(0, 500)}`)
    }
    lines.push('')
  }
  const transcript = lines.join('\n')
  if (!transcript.trim()) {
    return NextResponse.json({
      ok: true,
      changed: 0,
      note: '还没有编码模式对话——先去工作区里用编码模式聊几轮，再来提炼画像。',
    })
  }

  const existing = await db.profileItem.findMany({ orderBy: { confidence: 'desc' } })
  const raw = await completeText(
    [
      { role: 'system', content: PROFILE_DISTILL_SYSTEM },
      {
        role: 'user',
        content: buildProfileDistillUserMessage({
          transcript,
          existingProfile: existing.map((p) => ({ aspect: p.aspect, content: p.content })),
        }),
      },
    ],
    { maxTokens: 800, temperature: 0.2 }
  )
  if (!raw) {
    return NextResponse.json({ ok: false, error: '提炼模型暂时不可用，请稍后再试。' }, { status: 502 })
  }
  const parsed = parseLooseJson<{ skip?: boolean; actions?: Action[] }>(raw)
  if (!parsed || parsed.skip || !Array.isArray(parsed.actions) || parsed.actions.length === 0) {
    return NextResponse.json({ ok: true, changed: 0, note: '本轮没有可提炼的新信号（写入门禁生效）。' })
  }

  let changed = 0
  for (const action of parsed.actions.slice(0, 8)) {
    const content = (action.content || '').trim()
    if (!content || content.length > 200) continue
    const aspect = ASPECTS.includes(action.aspect || '') ? (action.aspect as string) : null
    if (!aspect) continue

    if (action.op === 'update' && action.old) {
      const target = existing.find((p) => p.content === action.old!.trim())
      if (target) {
        await db.profileItem.update({
          where: { id: target.id },
          data: { content, confidence: Math.min(1, target.confidence + 0.1), updatedAt: new Date() },
        })
        changed++
        continue
      }
    }
    if (action.op === 'new') {
      const dup = existing.some((p) => similarity(content, p.content) >= CONSOLIDATE_THRESHOLD)
      if (dup) continue
      await db.profileItem.create({
        data: {
          aspect,
          content,
          confidence: Math.min(1, Math.max(0.3, action.confidence ?? 0.7)),
          evidence: (action.evidence || '').slice(0, 60) || null,
          source: 'derived',
        },
      })
      changed++
    }
  }
  return NextResponse.json({
    ok: true,
    changed,
    note: changed > 0 ? `已更新 ${changed} 条画像。` : '没有产生变化（内容与现有画像重复或被门禁拦截）。',
  })
}
