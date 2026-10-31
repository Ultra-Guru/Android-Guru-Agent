import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { buildPromptSections } from '@/lib/prompts'
import { memoryScore } from '@/lib/scoring'

/**
 * 注入预览（诚实上报）—— 与消息路由同一构建逻辑，
 * 返回本次对话将注入到系统提示词的每个分段，所见即所发。
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const workspaceId = url.searchParams.get('workspaceId')
  const mode = url.searchParams.get('mode') === 'coding' ? 'coding' : 'agent'
  if (!workspaceId) {
    return NextResponse.json({ error: '缺少 workspaceId' }, { status: 400 })
  }

  const workspace = await db.workspace.findUnique({ where: { id: workspaceId } })
  if (!workspace) {
    return NextResponse.json({ error: '工作区不存在' }, { status: 404 })
  }

  const globalRules =
    (await db.setting.findUnique({ where: { key: 'globalRules' } }))?.value || ''

  const memoryPool = await db.memoryItem.findMany()
  const memories = memoryPool
    .map((m) => ({ ...m, score: memoryScore(m) }))
    .sort((a, b) => b.score - a.score)
    .slice(0, 5)

  const profileItems =
    mode === 'coding'
      ? await db.profileItem.findMany({ orderBy: { confidence: 'desc' }, take: 8 })
      : []

  const sections = buildPromptSections({
    mode,
    workspace,
    globalRules,
    profileItems: profileItems.map((p) => ({
      aspect: p.aspect,
      content: p.content,
      confidence: p.confidence,
    })),
    memoryItems: memories.map((m) => ({ category: m.category, content: m.content })),
  })

  const prompt = sections.map((s) => s.body).join('\n\n')

  return NextResponse.json({
    mode,
    sections: sections.map((s) => ({
      key: s.key,
      title: s.title,
      subtitle: s.subtitle,
      body: s.body,
      chars: s.body.length,
    })),
    prompt,
    stats: {
      totalChars: prompt.length,
      memoryCount: memories.length,
      memoryPool: memoryPool.length,
      profileCount: profileItems.length,
      globalRulesChars: globalRules.length,
      projectRulesChars: workspace.projectRules.length,
    },
  })
}
