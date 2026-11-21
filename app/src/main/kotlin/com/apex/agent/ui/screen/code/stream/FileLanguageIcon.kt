package com.apex.agent.ui.screen.code.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * # File Language Icon — 文件胶囊的语言图标井
 *
 * 用户规格（2026-11）：读取/写入/编辑文件胶囊的第一个图标显示**对应编程
 * 语言（及其他文本语言）的官方图标**——中间是文件名，尾部是行号范围/
 * 增删行数。本仓不引入整套 devicon SVG 资产（体积与授权维护成本），
 * 采用「官方品牌色 + 语言单字徽标」的等效形态：Kotlin 紫 K、Python 蓝
 * Py、TS 蓝 TS、Dart 蓝 D…… 品牌色取各语言官方 Logo 的主色，徽标是
 * 紧凑井（20dp 圆角方块）内的 monogram，与族图标井同规格替换。
 *
 * 未知扩展名回退族图标（[fallback]）：读取 = 文件图标、写入 = 编辑图标、
 * 编辑 = 代码图标——视觉语言不断档。
 */
internal data class FileLangSpec(val label: String, val color: Color)

/** 扩展名 → 官方品牌色 monogram（小写键；null = 未知回退族图标）。 */
internal fun fileLanguageSpec(path: String): FileLangSpec? {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        // JVM 系
        "kt", "kts" -> FileLangSpec("K", Color(0xFF7F52FF))          // Kotlin 官方紫
        "java" -> FileLangSpec("J", Color(0xFFE76F00))               // Oracle 橙
        "groovy", "gradle" -> FileLangSpec("G", Color(0xFF4298B8))
        // 脚本 / 通用
        "py", "pyw" -> FileLangSpec("Py", Color(0xFF3776AB))         // Python 官方蓝
        "js", "mjs", "cjs" -> FileLangSpec("JS", Color(0xFFF7DF1E))  // JS 黄
        "ts" -> FileLangSpec("TS", Color(0xFF3178C6))                // TS 官方蓝
        "jsx" -> FileLangSpec("JS", Color(0xFF61DAFB))               // React 蓝
        "tsx" -> FileLangSpec("TS", Color(0xFF61DAFB))
        "rb", "rake", "gemspec" -> FileLangSpec("Rb", Color(0xFFCC342D))
        "php" -> FileLangSpec("php", Color(0xFF777BB4))
        "lua" -> FileLangSpec("Lua", Color(0xFF2C2D72))
        "pl", "pm" -> FileLangSpec("Pl", Color(0xFF39457E))
        "r" -> FileLangSpec("R", Color(0xFF276DC3))
        "sh", "bash", "zsh" -> FileLangSpec("sh", Color(0xFF4EAA25)) // shell 绿
        "ps1" -> FileLangSpec("PS", Color(0xFF5391FE))
        "swift" -> FileLangSpec("S", Color(0xFFF05138))              // Swift 橙
        "go" -> FileLangSpec("Go", Color(0xFF00ADD8))                // Go 青蓝
        "rs" -> FileLangSpec("Rs", Color(0xFFDEA584))                // Rust 锈色
        "dart" -> FileLangSpec("D", Color(0xFF0175C2))               // Dart 蓝
        "zig" -> FileLangSpec("Z", Color(0xFFF7A41D))
        "vim" -> FileLangSpec("vi", Color(0xFF019833))
        "asm", "s" -> FileLangSpec("As", Color(0xFF6E4A13))
        // C 系
        "c" -> FileLangSpec("C", Color(0xFFA8B9CC))
        "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> FileLangSpec("C++", Color(0xFF00599C))
        "h" -> FileLangSpec("H", Color(0xFFA8B9CC))
        "cs" -> FileLangSpec("C#", Color(0xFF512BD4))                // .NET 紫
        "m", "mm" -> FileLangSpec("M", Color(0xFF576268))            // ObjC
        // Web 标记
        "html", "htm" -> FileLangSpec("<>", Color(0xFFE34F26))       // HTML 橙
        "xml" -> FileLangSpec("<>", Color(0xFF6E7681))
        "css" -> FileLangSpec("#", Color(0xFF1572B6))                // CSS 蓝
        "scss", "sass" -> FileLangSpec("#", Color(0xFFCC6699))       // Sass 粉
        "vue" -> FileLangSpec("V", Color(0xFF41B883))                // Vue 绿
        "svelte" -> FileLangSpec("Sv", Color(0xFFFF3E00))            // Svelte 橙
        // 数据 / 配置
        "json" -> FileLangSpec("{}", Color(0xFFD97706))              // JSON 琥珀
        "yml", "yaml" -> FileLangSpec("Y", Color(0xFFCB171E))        // YAML 红
        "toml" -> FileLangSpec("T", Color(0xFF9C4221))
        "sql" -> FileLangSpec("SQL", Color(0xFFE48E00))
        "proto" -> FileLangSpec("Pb", Color(0xFF7C3AED))
        "properties", "env", "ini", "cfg", "conf" -> FileLangSpec("P", Color(0xFF6E7681))
        "csv", "tsv" -> FileLangSpec("Cv", Color(0xFF1D6F42))
        // 文档
        "md", "markdown" -> FileLangSpec("M↓", Color(0xFF083FA1))    // Markdown 官方蓝
        "txt", "text", "log" -> FileLangSpec("≡", Color(0xFF6E7681))
        "tex", "latex" -> FileLangSpec("Tx", Color(0xFF008080))
        "org", "rst" -> FileLangSpec("≡", Color(0xFF519731))
        else -> null
    }
}

/**
 * 文件胶囊图标井：语言 monogram（品牌色底 16% + 同色单字）优先，
 * 未知扩展名回退族图标。与 [capsuleStyle] 的图标井同规格（20dp / 7dp 圆角）。
 */
@Composable
internal fun FileLanguageIcon(
    path: String,
    fallbackIcon: ImageVector,
    fallbackColor: Color,
    modifier: Modifier = Modifier
) {
    val spec = fileLanguageSpec(path)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(20.dp)
            .background(
                color = (spec?.color ?: fallbackColor).copy(alpha = 0.16f),
                shape = RoundedCornerShape(7.dp)
            )
    ) {
        if (spec != null) {
            Text(
                text = spec.label,
                fontSize = if (spec.label.length >= 3) 7.sp else 9.sp,
                lineHeight = if (spec.label.length >= 3) 7.sp else 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = spec.color,
                maxLines = 1
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = fallbackColor,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
