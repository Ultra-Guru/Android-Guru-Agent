// 消息后提炼（批量蒸馏）—— 对标 cs-mem v2：写入门禁 + 更新优先 + 巩固合并
// 在 after() 中运行：绝不抛出、绝不拖垮主流程；失败即静默丢弃（下一轮再试）。

import { db } from '@/lib/db'
import { CONSOLIDATE_THRESHOLD, memoryScore, similarity } from '@/lib/scoring'
import { buildDigestUserMessage, DIGEST_SYSTEM } from '@/lib/prompts'
import { completeText, parseLooseJson } from '@/lib/llm'

interface DigestAction {
  op: 'new' | 'update'
  old?: string
  category?: string
  aspect?: string
  content?: string
  importance?: number
  confidence?: number
  evidence?: string
}

interface DigestResult {
  skip?: boolean
  memories?: DigestAction[]
  profile?: DigestAction[]
}

const MAX_MEMORY = 80
const MAX_PROFILE = 60

async function topMemories(limit = 10) {
  const items = await db.memoryItem.findMany({ orderBy: { createdAt: 'desc' } })
  return items
    .map((m) => ({ ...m, score: memoryScore(m) }))
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
}

async function topProfile(limit = 12) {
  const items = await db.profileItem.findMany({ orderBy: { confidence: 'desc' } })
  return items.slice(0, limit)
}

/** 巩固：同类别与现有条目近重复 → 拒绝入库（保留更早/更高分的） */
function isDuplicate(content: string, existing: Array<{ content: string }>): boolean {
  return existing.some((e) => similarity(content, e.content) >= CONSOLIDATE_THRESHOLD)
}

export async function runDigest(input: {
  mode: 'agent' | 'coding'
  recentTurns: Array<{ role: string; content: string }>
}): Promise<void> {
  try {
    const existingMem = await topMemories(10)
    const existingProf = await topProfile(12)

    const raw = await completeText(
      [
        { role: 'system', content: DIGEST_SYSTEM },
        {
          role: 'user',
          content: buildDigestUserMessage({
            mode: input.mode,
            recentTurns: input.recentTurns,
            existingMemories: existingMem.map((m) => ({ category: m.category, content: m.content })),
            existingProfile: existingProf.map((p) => ({ aspect: p.aspect, content: p.content })),
          }),
        },
      ],
      { maxTokens: 600, temperature: 0.2 }
    )
    if (!raw) return
    const parsed = parseLooseJson<DigestResult>(raw)
    if (!parsed || parsed.skip) return

    const allMem = await db.memoryItem.findMany()
    const allProf = await db.profileItem.findMany()

    // ── memories 账本 ──
    for (const action of parsed.memories ?? []) {
      const content = (action.content || '').trim()
      if (!content || content.length > 120) continue
      if (action.op === 'update' && action.old) {
        const target = allMem.find((m) => m.content === action.old.trim())
        if (target) {
          await db.memoryItem.update({
            where: { id: target.id },
            data: { content, importance: Math.min(1, target.importance + 0.1), lastAccessedAt: new Date() },
          })
        }
        continue
      }
      if (action.op === 'new') {
        const category = ['fact', 'state', 'milestone', 'preference'].includes(action.category || '')
          ? (action.category as string)
          : 'fact'
        // state 近况有界替换：只保留最新 1 条
        if (category === 'state') {
          const states = allMem.filter((m) => m.category === 'state')
          for (const s of states) await db.memoryItem.delete({ where: { id: s.id } })
        }
        if (isDuplicate(content, allMem)) continue
        if (allMem.length >= MAX_MEMORY) {
          // 容量淘汰：低分先出
          const worst = allMem
            .map((m) => ({ m, score: memoryScore(m) }))
            .sort((a, b) => a.score - b.score)[0]
          if (worst) await db.memoryItem.delete({ where: { id: worst.m.id } })
        }
        await db.memoryItem.create({
          data: {
            category,
            content,
            importance: Math.min(1, Math.max(0.3, action.importance ?? 0.6)),
            source: 'auto',
          },
        })
      }
    }

    // ── profile 账本（仅编码相关维度，编码模式才写） ──
    if (input.mode === 'coding') {
      for (const action of parsed.profile ?? []) {
        const content = (action.content || '').trim()
        if (!content || content.length > 120) continue
        const aspect = ['tech-stack', 'coding-style', 'preferences', 'domains', 'habits', 'goals'].includes(
          action.aspect || ''
        )
          ? (action.aspect as string)
          : null
        if (!aspect) continue
        if (action.op === 'update' && action.old) {
          const target = allProf.find((p) => p.content === action.old.trim())
          if (target) {
            await db.profileItem.update({
              where: { id: target.id },
              data: { content, confidence: Math.min(1, target.confidence + 0.1), updatedAt: new Date() },
            })
          }
          continue
        }
        if (action.op === 'new') {
          if (isDuplicate(content, allProf)) continue
          if (allProf.length >= MAX_PROFILE) {
            const worst = [...allProf].sort((a, b) => a.confidence - b.confidence)[0]
            if (worst) await db.profileItem.delete({ where: { id: worst.id } })
          }
          await db.profileItem.create({
            data: {
              aspect,
              content,
              confidence: Math.min(1, Math.max(0.3, action.confidence ?? 0.6)),
              evidence: (action.evidence || '').slice(0, 60) || null,
              source: 'derived',
            },
          })
        }
      }
    }
  } catch (err) {
    console.error('[digest] failed (silently dropped):', (err as Error).message)
  }
}
