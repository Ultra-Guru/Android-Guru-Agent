import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { buildPlaygroundPrompt, extractHtml, validatePlaygroundHtml } from '@/lib/prompts'
import { completeText } from '@/lib/llm'

const CATEGORIES = ['game', 'tool', 'viz', 'animation']
const THEMES = ['neon', 'paper', 'candy', 'brutal']

function slug(): string {
  return Math.random().toString(36).slice(2, 8)
}

export const maxDuration = 300

/** LLM 生成自包含 HTML 应用；校验失败自动带错误反馈重试一次 */
async function generateHtml(input: {
  prompt: string
  category: string
  theme: string
  id: string
}): Promise<{ html: string } | { error: string; details: string[] }> {
  const ask = buildPlaygroundPrompt(input)
  let raw = await completeText(
    [{ role: 'system', content: '你是游乐场作品生成引擎，只输出完整 HTML 文件本身。' }, { role: 'user', content: ask }],
    { temperature: 0.8, maxTokens: 16000 }
  )
  let html = extractHtml(raw || '')
  let details = html ? validatePlaygroundHtml(html) : ['输出中没有可识别的 HTML 文档']

  if (html && details.length === 0) return { html }

  // 带错误反馈重试一次
  const feedback = (details.length ? details : ['未能提取 HTML']).join('；')
  raw = await completeText(
    [
      { role: 'system', content: '你是游乐场作品生成引擎，只输出完整 HTML 文件本身。' },
      { role: 'user', content: ask },
      {
        role: 'assistant',
        content: (raw || '').slice(0, 2000) + '\n…（输出被截断或违规）',
      },
      {
        role: 'user',
        content: `你上一版输出违反了硬性契约：${feedback}\n请修正这些问题，重新输出完整的 HTML 文件。只输出 HTML 本身。务必输出到 </html> 结束标签，不要中途截断。`,
      },
    ],
    { temperature: 0.6, maxTokens: 16000 }
  )
  html = extractHtml(raw || '')
  if (!html) return { error: '生成失败：模型没有产出可用的 HTML 文档', details: ['未能提取 HTML'] }
  details = validatePlaygroundHtml(html)
  if (details.length > 0) return { error: '生成完成但未通过质量校验', details }
  return { html }
}

export async function POST(req: Request) {
  const body = await req.json().catch(() => null)
  const prompt = (body?.prompt || '').toString().trim()
  const category = CATEGORIES.includes(body?.category) ? body.category : 'game'
  const theme = THEMES.includes(body?.theme) ? body.theme : 'neon'
  const regenerateOf = (body?.regenerateOf || '').toString() || null // 再生成：替换该作品
  const wantTitle = (body?.title || '').toString().trim().slice(0, 40) || null

  if (!prompt || prompt.length < 4) {
    return NextResponse.json({ error: '描述太短了——说说你想要什么（≥4 字）' }, { status: 400 })
  }
  if (prompt.length > 2000) {
    return NextResponse.json({ error: '描述过长（≤2000 字）' }, { status: 400 })
  }

  const existing = regenerateOf
    ? await db.playgroundApp.findUnique({ where: { id: regenerateOf } })
    : null
  if (regenerateOf && !existing) {
    return NextResponse.json({ error: '要替换的作品不存在' }, { status: 404 })
  }

  const id = slug()
  const result = await generateHtml({ prompt, category, theme, id })
  if ('error' in result) {
    return NextResponse.json(
      { ok: false, error: result.error, details: result.details.slice(0, 6) },
      { status: 422 }
    )
  }

  // 从 HTML 提取 <title> 作为默认标题
  const titleMatch = result.html.match(/<title>([^<]{1,60})<\/title>/i)
  const fallbackTitle = titleMatch ? titleMatch[1].split(/[·|]/)[0].trim() : prompt.slice(0, 16)
  const title = wantTitle || fallbackTitle || '未命名作品'
  const description = prompt.slice(0, 200)

  if (existing) {
    const app = await db.playgroundApp.update({
      where: { id: existing.id },
      data: { title, description, category, prompt, html: result.html, version: { increment: 1 } },
    })
    return NextResponse.json({ ok: true, app, replaced: true })
  }
  const app = await db.playgroundApp.create({
    data: { title, description, category, prompt, html: result.html, sortOrder: 50 },
  })
  return NextResponse.json({ ok: true, app, replaced: false })
}
