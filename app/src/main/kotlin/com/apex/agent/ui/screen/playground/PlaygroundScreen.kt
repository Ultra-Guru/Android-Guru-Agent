package com.apex.agent.ui.screen.playground

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.ui.glass.GlassCard

/**
 * ═══════════════════════════════════════════════════════════════
 *  游乐场 —— 一级导航页（玻璃实验室正上方）
 * ═══════════════════════════════════════════════════════════════
 *
 * 与官网 gh-pages 游乐场同源的 Android 街机：《字符风暴 GLYPH STORM》
 * —— pretext（chenglou/pretext，MIT）的字符测量理念驱动的物理拼词局：
 * 每个字符的物理半径都是 TextMeasurer 实测排版宽度，不是等宽方块。
 *
 * 页面构成：
 *  - 街机柜（游戏本体，见 [GlyphStormGame]）；
 *  - 测量模式切换：pretext 实测 vs 等宽近似（切换重开一局，手感差异
 *    即「为什么排版要用实测」的可玩论点）；
 *  - 战绩卡：高分 / 场次 / 累计清词 / 最长连击（SharedPreferences 持久化）；
 *  - pretext 实测展窗：现场量给你看 —— W 与 i 与「你」宽度天然不同；
 *  - 玩法说明卡。
 *
 * 与玻璃实验室互为邻居：实验室验证材质，游乐场验证测量 —— 两个
 * 「诚实工程」展示位。
 */
@Composable
fun PlaygroundScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var monoMode by remember { mutableStateOf(false) }
    var highScore by remember { mutableIntStateOf(prefs.getInt(KEY_HIGH_SCORE, 0)) }
    var totalGames by remember { mutableIntStateOf(prefs.getInt(KEY_TOTAL_GAMES, 0)) }
    var totalWords by remember { mutableIntStateOf(prefs.getInt(KEY_TOTAL_WORDS, 0)) }
    var bestCombo by remember { mutableIntStateOf(prefs.getInt(KEY_BEST_COMBO, 0)) }

    fun reportGameOver(score: Int, combo: Int, words: Int) {
        highScore = maxOf(highScore, score)
        bestCombo = maxOf(bestCombo, combo)
        totalGames += 1
        totalWords += words
        prefs.edit()
            .putInt(KEY_HIGH_SCORE, highScore)
            .putInt(KEY_BEST_COMBO, bestCombo)
            .putInt(KEY_TOTAL_GAMES, totalGames)
            .putInt(KEY_TOTAL_WORDS, totalWords)
            .apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PlaygroundHeader()
        GameSection(
            monoMode = monoMode,
            onModeChange = { monoMode = it },
            onGameOver = ::reportGameOver
        )
        StatsSection(
            highScore = highScore,
            totalGames = totalGames,
            totalWords = totalWords,
            bestCombo = bestCombo
        )
        PretextShowcaseSection()
        HowToSection()
    }
}

// ═══════════════════════════════════════════════════════════════
//  页头
// ═══════════════════════════════════════════════════════════════

@Composable
private fun PlaygroundHeader() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Column {
                    Text(
                        "字符风暴 · GLYPH STORM",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "pretext 实测排版宽度驱动的物理拼词街机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "每个字符的物理半径都是真实排版宽度（i 窄、W 宽、「你」满格）—— " +
                    "这正是 chenglou/pretext 在 Web 排版里坚持的事。引力井、捕获环、" +
                    "拍虫与连击都在这套真实尺寸的物理里发生。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  街机区（游戏 + 模式切换）
// ═══════════════════════════════════════════════════════════════

@Composable
private fun GameSection(
    monoMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onGameOver: (score: Int, bestCombo: Int, wordsCleared: Int) -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !monoMode,
                    onClick = { onModeChange(false) },
                    label = { Text("pretext 实测") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.VideogameAsset,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                FilterChip(
                    selected = monoMode,
                    onClick = { onModeChange(true) },
                    label = { Text("等宽近似") }
                )
            }
            Text(
                text = if (monoMode) {
                    "等宽近似：所有字符按 0.6em 假宽度算物理 —— 等宽思维，手感立变笨拙"
                } else {
                    "pretext 实测：字符宽度 = 真实排版宽度 —— 目标字符带虚线假宽框可对照"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlyphStormGame(
                monoMode = monoMode,
                onGameOver = onGameOver
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  战绩卡
// ═══════════════════════════════════════════════════════════════

@Composable
private fun StatsSection(
    highScore: Int,
    totalGames: Int,
    totalWords: Int,
    bestCombo: Int
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "战绩档案",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("最高分", highScore.toString(), Modifier.weight(1f))
                StatTile("场次", totalGames.toString(), Modifier.weight(1f))
                StatTile("累计清词", totalWords.toString(), Modifier.weight(1f))
                StatTile("最长连击", "×$bestCombo", Modifier.weight(1f))
            }
            Text(
                "本机持久化（应用私有目录），换词库 / 重装即清零 —— 街机的诚实计分板。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  pretext 实测展窗 —— 现场量给你看
// ═══════════════════════════════════════════════════════════════

/** 展窗样本：窄字母 / 宽字母 / CJK / 标点 —— 天然宽度差一眼可读。 */
private val ShowcaseSamples = listOf("W", "i", "m", "你", "好", "l", "!", "A")

@Composable
private fun PretextShowcaseSection() {
    val textMeasurer = rememberTextMeasurer()
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
    )
    val widths = remember(style, textMeasurer) {
        ShowcaseSamples.map { it to textMeasurer.measure(text = it, style = style).size.width }
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "pretext 实测展窗",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "同一字号（20sp 等宽字体）下，这些字符的实测宽度各不相同 —— " +
                    "字符风暴的全部物理都建立在这张表上：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                widths.forEach { (sample, widthPx) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                sample,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${widthPx}px",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Text(
                "灵感与测量契约来自 chenglou/pretext（MIT）—— 一个把「文本测量」" +
                    "本身做成 API 的排版库；官网游乐场与本街机是它的一体两面。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  玩法说明
// ═══════════════════════════════════════════════════════════════

@Composable
private fun HowToSection() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "玩法速查",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            HowToRow("①", "按住屏幕拖动 —— 手指就是引力井，字符被吸向指尖")
            HowToRow("②", "把发光的目标字符吸进中央捕获环，空槽幽灵字提示缺什么")
            HowToRow("③", "拼满整词清场奖励 + 难度爬升（虫子更快更多）")
            HowToRow("④", "虫子爬向环会咬血（三颗心），点按直接拍死 +15")
            HowToRow("⑤", "连续捕获攒连击倍率；被咬断连击")
        }
    }
}

@Composable
private fun HowToRow(bullet: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            bullet,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  持久化键
// ═══════════════════════════════════════════════════════════════

private const val PREFS_NAME = "playground"
private const val KEY_HIGH_SCORE = "glyph_storm_high_score"
private const val KEY_TOTAL_GAMES = "glyph_storm_total_games"
private const val KEY_TOTAL_WORDS = "glyph_storm_total_words"
private const val KEY_BEST_COMBO = "glyph_storm_best_combo"
