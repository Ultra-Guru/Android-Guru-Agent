package com.apex.agent.terminalemulator

/**
 * ═══ OSC 7 / OSC 9;9 工作目录上报（v0.3）═══
 *
 * bash `PROMPT_COMMAND` / zsh `precmd` 会把当前目录推给终端 —— 宿主据此
 * 显示会话 cwd、Agent 据此获得命令执行上下文（无需额外 shell 侧探测）：
 *
 * ```
 * guest: printf '\e]7;file://host/root/project\e\\'   ← OSC 7（URI 形式）
 * guest: printf '\e]9;9;/root/project\e\\'            ← OSC 9;9（ConEmu 形式）
 * host : snapshot.guestCwd == "/root/project"          ← %XX 已解码
 * ```
 *
 * 纯解析器（引擎侧状态只是一根 `guestCwd: String?` 字符串）。
 * 畸形输入（非 file 协议、空路径、非法百分号转义）→ null —— 状态不动，绝不抛。
 */
internal object GuestCwd {

    /**
     * OSC 7：`file:///path` 或 `file://host/path` → 解码后的路径。
     * 空 path / 非 file 协议 → null。
     *
     * T88 修复：`file://myhost/root/project` 按 URI 语义 authority=myhost、
     * path=/root/project —— 旧实现 `substringAfter('/')` 把前导 '/' 一起丢掉
     *（返回 "root/project"），且 `file://hostonly`（无 path）漏判为合法。
     */
    fun parseOsc7(uri: String): String? {
        val s = uri.trim()
        if (!s.startsWith("file://", ignoreCase = true)) return null
        val rest = s.substring(7)
        if (rest.isEmpty()) return null
        val path = when {
            rest.startsWith('/') -> rest                          // 空 host：file:///path
            rest.contains('/') -> "/" + rest.substringAfter('/')  // 剥 host，path 含前导 '/'
            else -> return null                                   // 仅 host 无 path
        }
        if (path.isEmpty()) return null
        val decoded = decodePercent(path) ?: return null
        return decoded.ifEmpty { null }
    }

    /**
     * OSC 9;9（ConEmu）：OSC code=9，data 形如 `9;/path`（解析器把首个 `;`
     * 前的 `9` 归入 code，剩余 data = `9;<path>`）。路径可带引号（剥离）。
     */
    fun parseOsc9(data: String): String? {
        if (!data.startsWith("9;")) return null
        var path = data.substring(2)
        if (path.length >= 2 && (path.first() == '"' && path.last() == '"')) {
            path = path.substring(1, path.length - 1)
        }
        if (path.isEmpty()) return null
        val decoded = decodePercent(path) ?: return null
        return decoded.ifEmpty { null }
    }

    /**
     * %XX 百分号解码（UTF-8 字节 → 字符串）。
     * 孤立 '%' / 非 hex 转义 → null（保守：整条上报作废，不落半截目录）。
     */
    fun decodePercent(s: String): String? {
        if (!s.contains('%')) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null   // 孤立 '%'（无完整 %XX）
                val hi = hexVal(s[i + 1]) ?: return null
                val lo = hexVal(s[i + 2]) ?: return null
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
            } else {
                bytes.add(c.code.toByte())
                i++
            }
        }
        return runCatching { bytes.toByteArray().toString(Charsets.UTF_8) }.getOrNull()
    }

    private fun hexVal(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}
