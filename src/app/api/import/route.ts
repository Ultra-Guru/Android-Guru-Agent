import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

// ═══════════ 数据导入（与 /api/export 配套） ═══════════
// 合并策略（诚实、可预期、无静默覆盖）：
//  · globalRules：仅在备份非空时覆盖写入
//  · 工作区：同名工作区不合并——带「（导入）」后缀新建（连同其对话与消息，全部重映射新 id）
//  · 记忆/画像：按 content 去重，重复条目跳过并计数
//  · 游乐场：作为新生成作品导入（builtin=false）

const MEM_CATS = new Set(['fact', 'state', 'milestone', 'preference'])
const PROFILE_ASPECTS = new Set(['tech-stack', 'coding-style', 'preferences', 'domains', 'habits', 'goals'])
const PG_CATS = new Set(['game', 'tool', 'viz', 'animation'])

function clampNum(v: unknown, min: number, max: number, fallback: number): number {
  const n = typeof v === 'number' && Number.isFinite(v) ? v : fallback
  return Math.min(max, Math.max(min, n))
}

function str(v: unknown, maxLen: number): string {
  return typeof v === 'string' ? v.slice(0, maxLen) : ''
}

export async function POST(req: Request) {
  const body = await req.json().catch(() => null)
  if (!body || typeof body !== 'object' || body.version !== 1 || !Array.isArray(body.workspaces)) {
    return NextResponse.json(
      { error: '无效的备份文件：需要 version=1 的 /api/export 导出 JSON' },
      { status: 400 }
    )
  }

  const stats = {
    workspaces: 0,
    conversations: 0,
    messages: 0,
    memories: 0,
    profile: 0,
    playground: 0,
    skippedMemories: 0,
    skippedProfile: 0,
  }

  // ── 软件级规则 ──
  const globalRules = typeof body.globalRules === 'string' ? body.globalRules : ''
  if (globalRules.trim()) {
    await db.setting.upsert({
      where: { key: 'globalRules' },
      update: { value: globalRules },
      create: { key: 'globalRules', value: globalRules },
    })
  }

  // ── 工作区（含对话与消息，全部新建 id） ──
  const existingNames = new Set(
    (await db.workspace.findMany({ select: { name: true } })).map((w) => w.name)
  )
  for (const ws of body.workspaces) {
    const name = str(ws?.name, 40).trim()
    if (!name) continue
    let finalName = existingNames.has(name) ? `${name.slice(0, 36)}（导入）` : name
    while (existingNames.has(finalName)) finalName = `${finalName.slice(0, 36)}·`
    const created = await db.workspace.create({
      data: {
        name: finalName,
        description: str(ws.description, 200) || null,
        emoji: str(ws.emoji, 8) || '🗂',
        projectRules: str(ws.projectRules, 64000),
      },
    })
    existingNames.add(finalName)
    stats.workspaces++

    for (const conv of Array.isArray(ws.conversations) ? ws.conversations : []) {
      const title = str(conv?.title, 80).trim() || '导入对话'
      const c = await db.conversation.create({
        data: {
          workspaceId: created.id,
          title,
          mode: conv?.mode === 'coding' ? 'coding' : 'agent',
        },
      })
      stats.conversations++

      const msgs = (Array.isArray(conv?.messages) ? conv.messages : [])
        .map((m: { role?: unknown; content?: unknown }) => ({
          role: m?.role === 'assistant' ? 'assistant' : 'user',
          content: str(m?.content, 100000),
        }))
        .filter((m: { content: string }) => m.content)
      if (msgs.length > 0) {
        await db.message.createMany({
          data: msgs.map((m: { role: string; content: string }) => ({
            conversationId: c.id,
            role: m.role,
            content: m.content,
          })),
        })
        stats.messages += msgs.length
      }
    }
  }

  // ── 记忆（按 content 去重） ──
  const existingMem = new Set(
    (await db.memoryItem.findMany({ select: { content: true } })).map((m) => m.content)
  )
  for (const m of Array.isArray(body.memories) ? body.memories : []) {
    const content = str(m?.content, 2000).trim()
    if (!content || existingMem.has(content)) {
      stats.skippedMemories++
      continue
    }
    await db.memoryItem.create({
      data: {
        category: MEM_CATS.has(m?.category) ? m.category : 'fact',
        content,
        importance: clampNum(m?.importance, 0, 1, 0.6),
        source: m?.source === 'auto' ? 'auto' : 'manual',
      },
    })
    existingMem.add(content)
    stats.memories++
  }

  // ── 用户画像（按 content 去重） ──
  const existingProf = new Set(
    (await db.profileItem.findMany({ select: { content: true } })).map((p) => p.content)
  )
  for (const p of Array.isArray(body.profile) ? body.profile : []) {
    const content = str(p?.content, 2000).trim()
    if (!content || existingProf.has(content)) {
      stats.skippedProfile++
      continue
    }
    await db.profileItem.create({
      data: {
        aspect: PROFILE_ASPECTS.has(p?.aspect) ? p.aspect : 'tech-stack',
        content,
        confidence: clampNum(p?.confidence, 0, 1, 0.6),
        evidence: str(p?.evidence, 200) || null,
        source: p?.source === 'derived' ? 'derived' : 'manual',
      },
    })
    existingProf.add(content)
    stats.profile++
  }

  // ── 游乐场作品（作为新生成导入） ──
  for (const a of Array.isArray(body.playground) ? body.playground : []) {
    const title = str(a?.title, 80).trim()
    const html = str(a?.html, 200000)
    if (!title || !/<\/html>/i.test(html)) continue
    await db.playgroundApp.create({
      data: {
        title,
        description: str(a?.description, 300),
        category: PG_CATS.has(a?.category) ? a.category : 'game',
        prompt: str(a?.prompt, 2000) || null,
        html,
        version: 1,
        builtin: false,
      },
    })
    stats.playground++
  }

  return NextResponse.json({ ok: true, stats })
}
