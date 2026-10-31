import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { ensureSeed } from '@/lib/seed'

export async function GET() {
  await ensureSeed()
  const apps = await db.playgroundApp.findMany({
    orderBy: [{ builtin: 'desc' }, { sortOrder: 'asc' }, { updatedAt: 'desc' }],
    select: {
      id: true,
      title: true,
      description: true,
      category: true,
      builtin: true,
      version: true,
      prompt: true,
      createdAt: true,
      updatedAt: true,
    },
  })
  return NextResponse.json({ apps })
}
