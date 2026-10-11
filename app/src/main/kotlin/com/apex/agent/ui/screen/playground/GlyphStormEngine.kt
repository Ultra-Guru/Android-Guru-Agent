package com.apex.agent.ui.screen.playground

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ═══════════════════════════════════════════════════════════════
 *  GlyphStormEngine —— 《字符风暴》纯逻辑引擎
 * ═══════════════════════════════════════════════════════════════
 *
 * 玩法血统：gh-pages 官网游乐场《GLYPH STORM》的 Android 姊妹局 ——
 * 拖出「引力井」把漂浮字符吸进中央捕获环，按任意顺序补齐目标词的
 * 缺字；虫子会主动爬向环咬血，点按拍死得分。
 *
 * pretext 血统（chenglou/pretext 的核心主张）：字符不是等宽方块 ——
 * 每个字符的物理半径 = 真实排版实测宽度（UI 层经 Compose TextMeasurer
 * 预测量表传入 [glyphWidth]，与 pretext 在 Web 上 measureNaturalWidth
 * 的用法同构）。引擎只消费宽度表，测量方式（实测 / 等宽近似）可切换，
 * 两种宽度下物理手感肉眼可见地不同 —— 这本身就是游戏的彩蛋。
 *
 * 纯 Kotlin 状态机：无 Android / Compose 运行时依赖（geometry 值类
 * 除外），帧推进由 UI 层 withFrameNanos 驱动；确定性随机源可注入。
 * 离开组合即无帧 —— 引擎随之自然冻结（切抽屉/退出页面 = 免费暂停）。
 */
internal class GlyphStormEngine(
    private val words: List<String>,
    /** 字符 → 实测排版宽度（px）。pretext 测量结果的唯一消费口。 */
    private val glyphWidth: (Char) -> Float,
    /** 逻辑 dp → px 换算（构造时由 UI 层按屏密度注入一次）。 */
    private val pxPerDp: Float,
    /** 干扰字母池：与目标词无关的漂浮字符来源。 */
    private val distractors: List<Char> = DEFAULT_DISTRACTORS,
    private val random: Random = Random.Default
) {
    // ═══ 阶段 ═══

    internal enum class Phase { READY, RUNNING, PAUSED, OVER }

    /** 单帧事件（UI 层消费：音效触感 / 粒子 / 弹窗）。读后即清。 */
    internal enum class Event { CAPTURE, WORD_CLEAR, BUG_SQUASH, BUG_BITE, GAME_OVER }

    // ═══ 实体 ═══

    internal class Glyph(
        val char: Char,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        /** 呼吸相位 —— 漂浮观感（READY 阶段也在动）。 */
        val phase: Float,
        /** 已捕获进卡槽（捕获后由 snap 动画接管坐标）。 */
        var capturedSlot: Int = -1,
        /** 捕获瞬间的起始坐标（snap 缓动用）。 */
        var snapFromX: Float = 0f,
        var snapFromY: Float = 0f,
        var snapT: Float = 1f
    )

    internal class Bug(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        /** 拍扁进度 0→1（正值 = 死亡动画播放中）。 */
        var squashT: Float = 0f
    ) {
        val dead: Boolean get() = squashT > 0f
    }

    internal class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        val maxLife: Float,
        val hue: Int // 0 主色 / 1 次色 / 2 三色（渲染层映射主题色）
    )

    // ═══ 对局状态（HUD 直接镜像的读数）═══

    internal var phase = Phase.READY
        private set
    internal var score = 0
        private set
    internal var combo = 0
        private set
    internal var bestCombo = 0
        private set
    internal var lives = START_LIVES
        private set
    internal var wordIndex = 0
        private set
    internal var wordsCleared = 0
        private set
    internal val events = mutableListOf<Event>()

    /** 当前词每个卡槽：已捕获的字符（null = 空槽）。 */
    internal var slots: List<Char?> = emptyList()
        private set
    /** 卡槽 x 起点（含内边距后每个槽的宽度表 —— pretext 宽度直出）。 */
    internal var slotXs: FloatArray = FloatArray(0)
        private set
    internal var slotWidths = FloatArray(0)
        private set

    internal val glyphs = mutableListOf<Glyph>()
    internal val bugs = mutableListOf<Bug>()
    internal val particles = mutableListOf<Particle>()

    /** 引力井（手指按住点；null = 无井）。 */
    internal var attractor: Offset? = null

    /** 捕获环中心与半径（布局期由 UI 按竞技场尺寸落位）。 */
    internal var arena = Size.Zero
        private set
    internal var ringCenter = Offset.Zero
        private set
    internal var ringRadius = 0f
        private set

    /** 咬环震屏剩余时间（秒）。 */
    internal var shakeT = 0f

    /** 悠闲时钟（秒）—— 绘制层旋转虚线环 / 引力井脉动用。 */
    internal val clock: Float get() = idleT

    // ═══ 私有节奏量 ═══

    private var bugTimer = 0f
    private var spawnTimer = 0f
    private var graceTimer = 0f
    /** READY 阶段的悠闲时钟（呼吸漂浮）。 */
    private var idleT = 0f

    // ═══ 生命周期 ═══

    /** 开始 / 重开一局。 */
    fun start() {
        score = 0
        combo = 0
        bestCombo = 0
        lives = START_LIVES
        wordIndex = 0
        wordsCleared = 0
        shakeT = 0f
        bugTimer = 0f
        graceTimer = GRACE_AFTER_WORD
        glyphs.clear()
        bugs.clear()
        particles.clear()
        events.clear()
        layoutWord()
        populateGlyphs()
        phase = Phase.RUNNING
    }

    fun pause() {
        if (phase == Phase.RUNNING) phase = Phase.PAUSED
    }

    fun resume() {
        if (phase == Phase.PAUSED) phase = Phase.RUNNING
    }

    /** 首次布局后的演示模式：READY 阶段先撒一批字符（吸引模式画面不空场）。 */
    fun warmUp() {
        if (phase == Phase.READY && glyphs.isEmpty()) populateGlyphs()
    }

    /** 竞技场尺寸就绪 / 变化时调用（旋转后重建布局）。 */
    fun layout(arena: Size) {
        this.arena = arena
        ringRadius = min(arena.width, arena.height) * 0.30f
        ringCenter = Offset(arena.width * 0.5f, arena.height * 0.30f)
        layoutWord()
    }

    /** 目标词换词 / 重排：以当前宽度表铺卡槽（居中于环内）。 */
    private fun layoutWord() {
        val word = currentWord
        slots = word.map { null }
        val gap = SLOT_GAP_DP * pxPerDp
        val pad = SLOT_PAD_DP * pxPerDp * 2f
        slotWidths = FloatArray(word.length) { glyphWidth(word[it]) + pad }
        slotXs = FloatArray(word.length)
        var total = 0f
        for (i in slotWidths.indices) total += slotWidths[i]
        total += gap * max(0, word.length - 1)
        var x = ringCenter.x - total * 0.5f
        for (i in slotWidths.indices) {
            slotXs[i] = x
            x += slotWidths[i] + gap
        }
    }

    private val currentWord: String get() = words[wordIndex % words.size]

    // ═══ 生成 ═══

    /** 保持场上字符人口：目标词缺字加权 + 干扰字母。 */
    private fun populateGlyphs() {
        val target = TARGET_POPULATION + wordsCleared.coerceAtMost(6)
        while (glyphs.count { it.capturedSlot < 0 } < target) {
            spawnGlyph()
        }
    }

    private fun spawnGlyph() {
        val word = currentWord
        val missing = word.filterIndexed { i, c -> slots.getOrNull(i)?.let { it != c } ?: true }
        // 注：String.filterIndexed 返回 String（kotlin.text 扩展），用 length
        val pool: Char = if (missing.isNotEmpty() && random.nextFloat() < 0.55f) {
            missing[random.nextInt(missing.length)]
        } else {
            distractors[random.nextInt(distractors.size)]
        }
        // 从四边随机注入，初速指向场内
        val edge = random.nextInt(4)
        val m = SPAWN_MARGIN_DP * pxPerDp
        val (x, y) = when (edge) {
            0 -> Offset(random.nextFloat() * arena.width, -m)
            1 -> Offset(arena.width + m, random.nextFloat() * arena.height * 0.6f)
            2 -> Offset(random.nextFloat() * arena.width, arena.height + m)
            else -> Offset(-m, random.nextFloat() * arena.height * 0.6f)
        }
        val ang = random.nextFloat() * 6.2832f
        val sp = (60f + random.nextFloat() * 90f) * pxPerDp
        glyphs += Glyph(
            char = pool,
            x = x,
            y = y,
            vx = kotlin.math.cos(ang) * sp,
            vy = kotlin.math.sin(ang) * sp,
            phase = random.nextFloat() * 6.2832f
        )
    }

    private fun spawnBug() {
        val edge = random.nextInt(4)
        val m = 24f * pxPerDp
        val (x, y) = when (edge) {
            0 -> Offset(random.nextFloat() * arena.width, -m)
            1 -> Offset(arena.width + m, random.nextFloat() * arena.height)
            2 -> Offset(random.nextFloat() * arena.width, arena.height + m)
            else -> Offset(-m, random.nextFloat() * arena.height)
        }
        bugs += Bug(x, y, 0f, 0f)
    }

    // ═══ 输入 ═══

    /** 点按拍虫：命中返回 true（+分）。 */
    fun squashAt(p: Offset): Boolean {
        if (phase != Phase.RUNNING) return false
        val hitR = SQUASH_RADIUS_DP * pxPerDp
        val bug = bugs.filter { !it.dead }.minByOrNull {
            val d = sqrt((it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y))
            d
        } ?: return false
        val dx = bug.x - p.x
        val dy = bug.y - p.y
        if (dx * dx + dy * dy > hitR * hitR) return false
        bug.squashT = 0.001f
        score += SCORE_BUG
        burst(bug.x, bug.y, 8, 1)
        events += Event.BUG_SQUASH
        return true
    }

    // ═══ 帧推进 ═══

    /**
     * 推进一帧。dt 秒（UI 层已钳制）。READY/PAUSED 只走呼吸漂浮，
     * RUNNING 走完整物理；OVER 只走粒子收尾。
     */
    fun tick(dt: Float) {
        idleT += dt
        if (shakeT > 0f) shakeT = max(0f, shakeT - dt)
        tickParticles(dt)
        when (phase) {
            Phase.READY -> tickIdleFloat(dt)
            Phase.PAUSED -> tickIdleFloat(dt)
            Phase.RUNNING -> tickRunning(dt)
            Phase.OVER -> Unit
        }
    }

    /** 未开局/暂停：字符仍轻微漂浮（画面不死板），但不捕获不出虫。 */
    private fun tickIdleFloat(dt: Float) {
        for (g in glyphs) {
            if (g.capturedSlot >= 0) continue
            g.x += kotlin.math.sin(idleT * 0.7f + g.phase) * 6f * pxPerDp * dt
            g.y += kotlin.math.cos(idleT * 0.5f + g.phase) * 8f * pxPerDp * dt
            keepInArena(g, soft = true)
        }
    }

    private fun tickRunning(dt: Float) {
        if (graceTimer > 0f) graceTimer -= dt
        val attract = attractor
        val attractForce = ATTRACT_ACCEL_DP * pxPerDp

        // ── 字符物理 ──
        for (g in glyphs) {
            if (g.capturedSlot >= 0) {
                // 捕获后 snap 缓动（160ms ease-out）
                if (g.snapT < 1f) {
                    g.snapT = min(1f, g.snapT + dt / 0.16f)
                    val e = 1f - (1f - g.snapT) * (1f - g.snapT)
                    val (sx, sy) = slotPos(g.capturedSlot)
                    g.x = g.snapFromX + (sx - g.snapFromX) * e
                    g.y = g.snapFromY + (sy - g.snapFromY) * e
                }
                continue
            }
            if (attract != null) {
                val dx = attract.x - g.x
                val dy = attract.y - g.y
                val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                // 近场衰减：避免贴手后疯狂抖动
                val falloff = min(1f, d / (90f * pxPerDp))
                g.vx += dx / d * attractForce * falloff * dt
                g.vy += dy / d * attractForce * falloff * dt
            }
            // 轻漂浮 + 阻尼
            g.vx += kotlin.math.sin(idleT * 0.9f + g.phase) * 10f * pxPerDp * dt
            g.vy += kotlin.math.cos(idleT * 0.7f + g.phase) * 12f * pxPerDp * dt
            val damping = exp(-DAMPING * dt)
            g.vx *= damping
            g.vy *= damping
            g.x += g.vx * dt
            g.y += g.vy * dt
            keepInArena(g, soft = false)
        }

        // ── 字符互斥（O(n²)，人口 ≤ 20 出头，可承受）──
        val repulse = REPULSE_ACCEL_DP * pxPerDp
        for (i in glyphs.indices) {
            val a = glyphs[i]
            if (a.capturedSlot >= 0) continue
            for (j in i + 1 until glyphs.size) {
                val b = glyphs[j]
                if (b.capturedSlot >= 0) continue
                val dx = b.x - a.x
                val dy = b.y - a.y
                val ra = glyphWidth(a.char) * 0.5f + GLYPH_PAD_DP * pxPerDp
                val rb = glyphWidth(b.char) * 0.5f + GLYPH_PAD_DP * pxPerDp
                val minD = (ra + rb) * 1.15f
                val d2 = dx * dx + dy * dy
                if (d2 in 1f..minD * minD) {
                    val d = sqrt(d2)
                    val push = repulse * (1f - d / minD) * dt
                    a.vx -= dx / d * push
                    a.vy -= dy / d * push
                    b.vx += dx / d * push
                    b.vy += dy / d * push
                }
            }
        }

        // ── 捕获 / 弹开判定 ──
        val word = currentWord
        val iter = glyphs.iterator()
        while (iter.hasNext()) {
            val g = iter.next()
            if (g.capturedSlot >= 0) continue
            val dx = g.x - ringCenter.x
            val dy = g.y - ringCenter.y
            if (dx * dx + dy * dy > ringRadius * ringRadius) continue
            // 目标字符（还有空槽）→ 吸入第一个匹配空槽
            var slot = -1
            for (i in slots.indices) {
                if (slots[i] == null && word[i] == g.char) { slot = i; break }
            }
            if (slot >= 0) {
                slots = slots.toMutableList().also { it[slot] = g.char }
                g.capturedSlot = slot
                g.snapFromX = g.x
                g.snapFromY = g.y
                g.snapT = 0f
                combo += 1
                bestCombo = max(bestCombo, combo)
                score += SCORE_GLYPH * (1 + combo.coerceAtMost(20))
                burst(g.x, g.y, 10, 0)
                events += Event.CAPTURE
                if (slots.all { it != null }) {
                    // 清词即整体清场重铺 —— 正在进行的 glyphs 迭代立即作废，
                    // 必须跳出（继续 next() 会 ConcurrentModificationException）
                    onWordClear()
                    break
                }
                continue
            }
            // 非目标字符 → 弹出环外（冲量沿径向）
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val impulse = REPEL_IMPULSE_DP * pxPerDp
            g.vx += dx / d * impulse
            g.vy += dy / d * impulse
        }

        // ── 虫子 ──
        if (graceTimer <= 0f) {
            bugTimer += dt
            val interval = max(BUG_INTERVAL_MIN, BUG_INTERVAL_BASE - wordsCleared * 0.28f)
            if (bugTimer >= interval && bugs.count { !it.dead } < BUG_CAP) {
                bugTimer = 0f
                spawnBug()
            }
        }
        val bugSpeed = (BUG_SPEED_DP + wordsCleared * 5f) * pxPerDp
        val biteR = ringRadius * 0.55f
        val bugIter = bugs.iterator()
        while (bugIter.hasNext()) {
            val bug = bugIter.next()
            if (bug.dead) {
                bug.squashT += dt / 0.4f
                if (bug.squashT >= 1f) bugIter.remove()
                continue
            }
            val dx = ringCenter.x - bug.x
            val dy = ringCenter.y - bug.y
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            // 虫子绕环螺旋逼近（比直线爬更有威胁感）
            val approach = bugSpeed * dt
            val tangent = 0.55f
            bug.x += (dx / d * approach) + (-dy / d * approach * tangent)
            bug.y += (dy / d * approach) + (dx / d * approach * tangent)
            if (d < biteR) {
                bugIter.remove()
                lives -= 1
                combo = 0
                shakeT = 0.5f
                burst(bug.x, bug.y, 14, 2)
                events += Event.BUG_BITE
                if (lives <= 0) {
                    phase = Phase.OVER
                    events += Event.GAME_OVER
                }
            }
        }

        // ── 人口维持 ──
        spawnTimer += dt
        if (spawnTimer > 0.5f) {
            spawnTimer = 0f
            populateGlyphs()
        }
        // 已捕获的字符在换词前一直占列表 —— 换词时整体清场，这里无需回收
    }

    private fun onWordClear() {
        val bonus = SCORE_WORD_BASE + currentWord.length * SCORE_WORD_PER_CHAR
        score += bonus * (1 + combo.coerceAtMost(10) / 2)
        wordsCleared += 1
        wordIndex = (wordIndex + 1) % words.size
        graceTimer = GRACE_AFTER_WORD
        bugTimer = 0f
        events += Event.WORD_CLEAR
        burst(ringCenter.x, ringCenter.y, 26, 0)
        // 清场（含已捕获字符）→ 新词新人口
        glyphs.clear()
        layoutWord()
        populateGlyphs()
    }

    // ═══ 工具 ═══

    /** 卡槽中心（捕获 snap 与绘制共用同一坐标源）。 */
    internal fun slotPos(slot: Int): Offset {
        val w = slotWidths.getOrElse(slot) { 40f * pxPerDp }
        val x = slotXs.getOrElse(slot) { ringCenter.x } + w * 0.5f
        return Offset(x, ringCenter.y)
    }

    private fun keepInArena(g: Glyph, soft: Boolean) {
        val m = if (soft) 8f * pxPerDp else 4f * pxPerDp
        val topM = m + GLYPH_TOP_MARGIN_DP * pxPerDp
        if (g.x < m) { g.x = m; g.vx = kotlin.math.abs(g.vx) * 0.6f }
        if (g.x > arena.width - m) { g.x = arena.width - m; g.vx = -kotlin.math.abs(g.vx) * 0.6f }
        if (g.y < topM) { g.y = topM; g.vy = kotlin.math.abs(g.vy) * 0.6f }
        if (g.y > arena.height - m) { g.y = arena.height - m; g.vy = -kotlin.math.abs(g.vy) * 0.6f }
    }

    private fun burst(x: Float, y: Float, count: Int, hue: Int) {
        repeat(count) {
            val ang = random.nextFloat() * 6.2832f
            val sp = (90f + random.nextFloat() * 160f) * pxPerDp
            val life = 0.35f + random.nextFloat() * 0.3f
            particles += Particle(x, y, kotlin.math.cos(ang) * sp, kotlin.math.sin(ang) * sp, life, life, hue)
        }
    }

    private fun tickParticles(dt: Float) {
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.life -= dt
            if (p.life <= 0f) { iter.remove(); continue }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= exp(-3.5f * dt)
            p.vy = p.vy * exp(-3.5f * dt) + 240f * pxPerDp * dt
        }
    }

    internal companion object {
        internal val WORDS = listOf(
            "终端", "玻璃", "智能", "推理", "引力", "量子", "风暴",
            "PRETEXT", "STORM", "AGENT", "TOKEN", "GLASS", "PIXEL", "LOGIC"
        )
        internal val DEFAULT_DISTRACTORS = listOf(
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'K', 'M', 'N',
            'R', 'S', 'T', 'U', 'W', 'X', 'Y', 'Z', '字', '符', '雨', '光', '井'
        )

        internal const val START_LIVES = 3
        private const val ATTRACT_ACCEL_DP = 900f
        private const val DAMPING = 2.1f
        private const val REPULSE_ACCEL_DP = 700f
        private const val REPEL_IMPULSE_DP = 340f
        private const val GLYPH_PAD_DP = 3f
        private const val SLOT_GAP_DP = 6f
        private const val SLOT_PAD_DP = 7f
        private const val SPAWN_MARGIN_DP = 28f
        private const val GLYPH_TOP_MARGIN_DP = 26f
        private const val TARGET_POPULATION = 13
        private const val BUG_INTERVAL_BASE = 4.6f
        private const val BUG_INTERVAL_MIN = 1.9f
        private const val BUG_SPEED_DP = 34f
        private const val BUG_CAP = 5
        private const val SQUASH_RADIUS_DP = 30f
        private const val GRACE_AFTER_WORD = 1.2f
        private const val SCORE_GLYPH = 10
        private const val SCORE_BUG = 15
        private const val SCORE_WORD_BASE = 50
        private const val SCORE_WORD_PER_CHAR = 15
    }
}
