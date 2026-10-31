// 评分公式 —— 对标 cs-mem v2：importance × recency × frequency
// recency   = 0.55 + 0.45 × 2^(−ageDays / 14)   （14 天半衰，地板 0.55）
// frequency = 1 + 0.12 × ln(1 + accessCount)     （越被召回越牢）

export function recencyFactor(lastAccessedAt: Date | string, now = new Date()): number {
  const ageDays = Math.max(0, (now.getTime() - new Date(lastAccessedAt).getTime()) / 86400000)
  return 0.55 + 0.45 * Math.pow(2, -ageDays / 14)
}

export function frequencyFactor(accessCount: number): number {
  return 1 + 0.12 * Math.log(1 + Math.max(0, accessCount))
}

export function memoryScore(input: {
  importance: number
  accessCount: number
  lastAccessedAt: Date | string
}): number {
  return input.importance * recencyFactor(input.lastAccessedAt) * frequencyFactor(input.accessCount)
}

/** 简易相似度：字符 bigram Dice 系数（用于巩固合并的近重复判断） */
export function similarity(a: string, b: string): number {
  const norm = (s: string) => s.toLowerCase().replace(/[\s，。、；：！？,.;;!?~·]/g, '')
  const x = norm(a)
  const y = norm(b)
  if (!x || !y) return 0
  if (x === y) return 1
  if (x.length < 2 || y.length < 2) return x === y ? 1 : 0
  const bigrams = (s: string) => {
    const set = new Map<string, number>()
    for (let i = 0; i < s.length - 1; i++) {
      const g = s.slice(i, i + 2)
      set.set(g, (set.get(g) || 0) + 1)
    }
    return set
  }
  const A = bigrams(x)
  const B = bigrams(y)
  let hit = 0
  for (const [g, n] of A) {
    const m = B.get(g) || 0
    hit += Math.min(n, m)
  }
  const total = x.length - 1 + y.length - 1
  return total > 0 ? (2 * hit) / total : 0
}

/** 巩固阈值：同类别内容 Dice ≥ 0.72 视为近重复 */
export const CONSOLIDATE_THRESHOLD = 0.72
