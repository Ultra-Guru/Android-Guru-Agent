// 种子 —— 幂等
// 内置游乐场作品：总是与 src/playground/*.html 源文件同步（开发期可直接改文件）
// 默认工作区：仅在没有任何工作区时创建
import { db } from '@/lib/db'
import { readFileSync } from 'fs'
import { join } from 'path'

const SEED_KEY = 'seeded.v1'

function readBuiltin(name: string): string {
  return readFileSync(join(process.cwd(), 'src', 'playground', name), 'utf-8')
}

const BUILTIN_SPECS: Array<{
  key: string
  title: string
  description: string
  category: string
  file: string
  sortOrder: number
}> = [
  {
    key: 'neon-snake',
    title: '霓虹蛇',
    description:
      '终端暗色霓虹风的贪吃蛇：连击加成、粒子迸溅、震屏结算，支持滑动转向与 WebAudio 合成音效。',
    category: 'game',
    file: 'neon-snake.html',
    sortOrder: 1,
  },
  {
    key: 'particle-galaxy',
    title: '粒子星河',
    description:
      '数千粒子沿流场漂移的生成艺术：可调数量/余晖/流速，四种调色板，指针扰动或牵引，帧率自适应降载。',
    category: 'animation',
    file: 'particle-galaxy.html',
    sortOrder: 2,
  },
  {
    key: 'code-typist',
    title: '码农打字',
    description:
      '面向程序员的打字测试：真实代码段落（TS/JS/Rust/Go/SQL…），WPM 与准确率实时统计，60 秒模式最佳纪录。',
    category: 'tool',
    file: 'code-typist.html',
    sortOrder: 3,
  },
]

export async function ensureSeed(): Promise<void> {
  // ── 内置作品同步（每次调用都核对，幂等且廉价） ──
  const existing = await db.playgroundApp.findMany({ where: { builtin: true } })
  for (const spec of BUILTIN_SPECS) {
    const html = readBuiltin(spec.file)
    const hit = existing.find((e) => e.title === spec.title)
    if (!hit) {
      await db.playgroundApp.create({
        data: {
          title: spec.title,
          description: spec.description,
          category: spec.category,
          html,
          builtin: true,
          sortOrder: spec.sortOrder,
          prompt: '（内置示例 · 手工打造的质量标杆）',
        },
      })
    } else if (hit.html !== html) {
      await db.playgroundApp.update({
        where: { id: hit.id },
        data: { html, description: spec.description, category: spec.category },
      })
    }
  }

  // ── 默认工作区（仅一次） ──
  const wsCount = await db.workspace.count()
  if (wsCount === 0) {
    await db.workspace.create({
      data: {
        name: '默认工作区',
        emoji: '🗂',
        description: '系统创建的初始工作区，可以重命名或新建自己的工作区。',
      },
    })
  }

  await db.setting.upsert({
    where: { key: SEED_KEY },
    create: { key: SEED_KEY, value: new Date().toISOString() },
    update: { value: new Date().toISOString() },
  })
}
