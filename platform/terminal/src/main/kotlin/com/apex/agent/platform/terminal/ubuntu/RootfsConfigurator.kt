package com.apex.agent.platform.terminal.ubuntu

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * T72: RootFS Configurator —— 把一个"刚解压出来的 Ubuntu Base"配置成
 * "PRoot 里能真正跑起来的 Ubuntu"。
 *
 * P69 的 configureBasicEnv 只 mkdir 了 tmp/home/root/workspace —— 真实
 * Ubuntu Base tarball 解压后的阻断点全部没有处理：
 *
 *  1. /etc/resolv.conf 是空文件 → guest 内 DNS 解析全灭 → apt update 必败。
 *  2. /etc/hosts 是空文件 → localhost 反解失败（apt/sudo 等会卡警告）。
 *  3. /etc/hostname 空 → bash 提示符出现 "(none)"。
 *  4. 旧 Ubuntu Base 无 CA；完整 rootfs 已在构建期预装 ca-certificates，
 *     只对确实缺失/为空的存量环境做修复。
 *  5. apt 的工作目录（lists/partial、archives/partial、dpkg updates…）
 *     Ubuntu Base 自带，但删除重装/异常中断后可能缺——幂等确保。
 *
 * 策略（诚实优先）：
 *  - DNS：优先注入（Android 生产由 DI 传系统 DNS）；其次复制 host
 *    /etc/resolv.conf（CI/Linux 有效）；最后 fallback 公共 DNS 并记录
 *    warning —— 绝不假装"配置好了"而不说明来源。
 *  - CA：已有的打包证书优先；缺失时从可信宿主 bundle 或 Android 系统证书
 *    目录修复。来源不可读则告警，不从网络或用户目录下载/拼接证书。
 *  - 所有写入幂等：已存在且非空的非托管文件不覆盖（用户/上游改动优先）。
 */
class RootfsConfigurator(
    /** 注入的 DNS 服务器（Android 生产：系统 DNS；测试：任意）。空 = 未提供。 */
    private val dnsServers: () -> List<String> = { emptyList() },
    /** host 侧 CA bundle（默认 CI/Linux 路径；Android 上不存在 → null）。 */
    private val hostCaBundle: () -> File? = { File("/etc/ssl/certs/ca-certificates.crt").takeIf { it.isFile && it.length() > 0 } },
    /** 仅系统级 Android CA；不读取用户安装的 CA 或任意下载目录。 */
    private val androidSystemCaDirs: () -> List<File> = {
        listOf(File("/apex/com.android.conscrypt/cacerts"), File("/system/etc/security/cacerts"))
    },
    /**
     * 每个系统 CA 目录对应的「已停用证书」目录（Android SystemCertificateSource
     * 用 cacerts-removed 标记被用户关闭的系统锚点）。目录顺序与
     * [androidSystemCaDirs] 一一对应。该目录不存在 = 没有任何停用标记
     * （Android 只在用户停用时创建它）；显式 null = 未知 revocation 来源，
     * 该系统目录的锚点一律不导入（fail closed）。不导入 cacerts-added /
     * 用户安装证书；不写 guest bundle 之外的任何路径。
     */
    private val androidSystemCaRemovedDirs: () -> List<File?> = {
        List(androidSystemCaDirs().size) { null }
    },
    /** guest 主机名。 */
    private val hostname: String = DEFAULT_HOSTNAME,
    /**
     * T82（Termux 基线 §3.5）：写入 /etc/locale.gen 的 locale 行
     *（如 "zh_CN.UTF-8 UTF-8"、"en_US.UTF-8 UTF-8"）。bootstrap 安装 `locales`
     * 包时 postinst 自动 locale-gen 全部列出项 —— 无需额外执行步骤。
     * 空 = 不写（历史行为）。默认 LANG 仍 C.UTF-8（不变更语义，仅**可用性**扩展）。
     */
    private val localeGen: List<String> = emptyList(),
    /**
     * T82（基线 §3.6）：Android 时区 id（如 "Asia/Shanghai"）。写入
     * /etc/timezone + /etc/localtime 符号链接；`tzdata`（essential）postinst
     * 读取 /etc/timezone 完成生效。null = 不写（UTC 回退，历史行为）。
     */
    private val timezone: String? = null,
    /**
     * 宿主进程补充组 gid（对话实录 #14）：PRoot -0 伪装 uid/gid=0，但补充组
     * 继承自 Android 宿主（3003 inet / 9997 everybody / 101xx…），guest
     * /etc/group 无这些条目 → coreutils id/groups 每次启动刷 6 行
     * “groups: cannot find name for group ID xxx”。生产 DI 注入
     * Os.getgroups()；测试默认空（不动作）。
     */
    private val hostSupplementaryGids: () -> List<Int> = { emptyList() }
) {

    /** 配置结果 —— 全部动作与 warning 进入返回值（可断言、可入日志），不靠 println。 */
    data class ConfigureReport(
        val actions: List<String>,
        val warnings: List<String>
    ) {
        val ok: Boolean get() = actions.isNotEmpty() || warnings.isEmpty()
    }

    fun configure(root: File): ConfigureReport {
        val actions = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ── 1. /etc/resolv.conf —— DNS 是 apt/网络的第一阻断点 ──
        // ★ 符号链接加固（用户反馈「APT 引导未完成」的可能根因）：Ubuntu Base
        // 24.04 的 /etc/resolv.conf 是指向 /run/systemd/resolve/stub-resolv.conf
        // 的符号链接 —— PRoot 内无 systemd，目标不存在 → dangling symlink：
        //   - hasContent() 对 dangling 链接返回 false（isFile=false）
        //   - writeText 跟随链接 → 目标目录缺失 → FileNotFoundException →
        //     configure 崩溃或静默失败 → guest DNS 全灭 → apt update 必败。
        // 处理：非普通文件（链接/损坏）时先删除再重写为普通文件。
        val resolv = File(root, "etc/resolv.conf")
        val dns = resolveDnsServers(warnings)
        val resolvIsPlainFile = resolv.isFile && !java.nio.file.Files.isSymbolicLink(resolv.toPath())
        if (!resolvIsPlainFile || !hasContent(resolv)) {
            resolv.parentFile?.mkdirs()
            if (resolv.exists() || java.nio.file.Files.exists(resolv.toPath())) {
                // dangling symlink：exists() 对其返回 false，须用 Files.exists 跟随链接判定
                runCatching { resolv.delete() }
                    .onFailure { warnings.add("resolv.conf: cannot remove stale entry — ${it.message}") }
            }
            runCatching {
                resolv.writeText(dns.joinToString("\n") { "nameserver $it" } + "\n")
                actions.add("resolv.conf: wrote nameservers ${dns.joinToString(",")}")
            }.onFailure {
                warnings.add("resolv.conf: write failed — ${it.message}")
            }
        } else {
            actions.add("resolv.conf: kept existing (${resolv.length()} bytes)")
        }

        // ── 2. /etc/hosts ──
        val hosts = File(root, "etc/hosts")
        if (!hasContent(hosts)) {
            hosts.parentFile?.mkdirs()
            hosts.writeText(
                """
                127.0.0.1       localhost
                ::1             localhost ip6-localhost ip6-loopback
                127.0.1.1       $hostname
                """.trimIndent() + "\n"
            )
            actions.add("hosts: wrote localhost + $hostname")
        } else {
            actions.add("hosts: kept existing")
        }

        // ── 3. /etc/hostname ──
        val hostnameFile = File(root, "etc/hostname")
        if (!hasContent(hostnameFile)) {
            hostnameFile.parentFile?.mkdirs()
            hostnameFile.writeText("$hostname\n")
            actions.add("hostname: wrote '$hostname'")
        } else {
            actions.add("hostname: kept existing '${hostnameFile.readText().trim()}'")
        }

        // ── 3b. /etc/group：宿主补充组条目补齐（实录 #14：PRoot 组噪音根因）──
        // PRoot -0 伪装 root，但补充组继承宿主 → guest 内无对应 gid 条目，
        // id/groups 启动即报 “cannot find name for group ID”。幂等补进
        // g<gid>:x:<gid>: 行（命名避开真实组名；已存在的 gid 跳过）。
        val gids = hostSupplementaryGids().distinct().filter { it > 0 }
        if (gids.isNotEmpty()) {
            val groupFile = File(root, "etc/group")
            val existing = if (groupFile.isFile) groupFile.readText() else ""
            val additions = buildGroupAdditions(existing, gids)
            if (additions.isNotEmpty()) {
                groupFile.parentFile?.mkdirs()
                groupFile.appendText(additions.joinToString("") { "$it\n" })
                actions.add("group: added ${additions.size} host supplementary gid entries (${gids.joinToString(",")})")
            } else {
                actions.add("group: all ${gids.size} host supplementary gids already mapped")
            }
        }

        // ── 4. locale（C.UTF-8 内建于 glibc，无需生成）──
        val localeDefault = File(root, "etc/default/locale")
        if (!localeDefault.isFile) {
            localeDefault.parentFile?.mkdirs()
            localeDefault.writeText("LANG=\"C.UTF-8\"\n")
            actions.add("locale: wrote LANG=C.UTF-8")
        }
        // T82：/etc/locale.gen —— locales 包 postinst 自动生成（zh_CN.UTF-8 等）。
        //    幂等：仅当列出的行不都在既有文件中时追加。
        if (localeGen.isNotEmpty()) {
            val localeGenFile = File(root, "etc/locale.gen")
            val existingLines = if (localeGenFile.isFile) localeGenFile.readLines().toSet() else emptySet()
            val missing = localeGen.filterNot { it in existingLines }
            if (missing.isNotEmpty()) {
                localeGenFile.parentFile?.mkdirs()
                localeGenFile.appendText(missing.joinToString("\n", prefix = if (localeGenFile.isFile) "\n" else "", postfix = "\n"))
                actions.add("locale.gen: appended ${missing.joinToString(",")}")
            } else {
                actions.add("locale.gen: already contains ${localeGen.joinToString(",")}")
            }
        }

        // ── 5. apt/dpkg 工作目录幂等确保 ──
        val aptDirs = listOf(
            "var/lib/apt/lists/partial",
            "var/lib/dpkg/info",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/triggers",
            "var/cache/apt/archives/partial",
            "var/log/apt"
        )
        var madeDirs = 0
        for (d in aptDirs) {
            val f = File(root, d)
            if (!f.isDirectory) {
                if (f.mkdirs()) madeDirs++ else warnings.add("apt-dir: cannot create /$d")
            }
        }
        actions.add("apt-dirs: ${aptDirs.size - warnings.count { it.startsWith("apt-dir") }} present, $madeDirs created")

        // ── 6. CA certificates：完整包自带；仅缺失/空时从可信宿主修复 ──
        val guestCa = File(root, "etc/ssl/certs/ca-certificates.crt")
        when {
            Files.isSymbolicLink(guestCa.toPath()) -> warnings.add("ca-certificates: unsafe symlink — not followed")
            hasContent(guestCa) -> actions.add("ca-certificates: kept existing bundle (${guestCa.length()} bytes)")
            else -> {
                val supplied = supplyCaIfMissing(root)
                if (supplied != null) {
                    actions.add("ca-certificates: copied $supplied (${guestCa.length()} bytes)")
                } else {
                    warnings.add(
                        "ca-certificates: ABSENT — http apt sources work unchanged; " +
                            "https needs a trusted system CA store or a repaired ca-certificates package"
                    )
                }
            }
        }

        // ── 7. timezone：Ubuntu Base 自带 /etc/localtime → Etc/UTC ──
        //    T82：注入 Android 时区（/etc/timezone + /etc/localtime 符号链接）。
        //    tzdata（T82 起进 essential）postinst 读取 /etc/timezone 生效。
        if (timezone != null && timezone.isNotBlank()) {
            val tzFile = File(root, "etc/timezone")
            if (!tzFile.isFile || tzFile.readText().trim() != timezone.trim()) {
                tzFile.parentFile?.mkdirs()
                tzFile.writeText("$timezone\n")
                actions.add("timezone: wrote /etc/timezone=$timezone")
            }
            val localtimeFile = File(root, "etc/localtime")
            val zoneTarget = File(root, "usr/share/zoneinfo/$timezone")
            // 符号链接目标在 tzdata 安装后才存在 —— 提前创建（dangling 到安装完成，
            // postinst/下次 configure 会收敛；如实记录）。
            runCatching {
                if (localtimeFile.exists()) localtimeFile.delete()
                java.nio.file.Files.createSymbolicLink(
                    localtimeFile.toPath(),
                    zoneTarget.toPath()
                )
                if (zoneTarget.isFile) actions.add("timezone: /etc/localtime -> $timezone")
                else actions.add("timezone: /etc/localtime -> $timezone (dangling until tzdata installed)")
            }.onFailure {
                warnings.add("timezone: failed to link /etc/localtime — ${it.message}")
            }
        } else {
            val localtime = File(root, "etc/localtime")
            if (!localtime.exists()) {
                warnings.add("timezone: /etc/localtime missing — guest falls back to UTC")
            } else {
                actions.add("timezone: /etc/localtime present")
            }
        }

        // ── 8. P69 原有的基础目录（bind 点）──
        for (d in listOf("tmp", "home", "root", "workspace")) {
            val f = File(root, d)
            if (!f.isDirectory) f.mkdirs()
        }
        // /tmp 必须可写（sticky 1777）
        File(root, "tmp").setWritable(true, false)
        File(root, "tmp").setExecutable(true, false)
        actions.add("base-dirs: tmp/home/root/workspace ensured (tmp world-writable)")

        return ConfigureReport(actions, warnings)
    }

    /**
     * Repair a legacy rootfs without reinstalling the pinned archive. A nonempty
     * packaged/user bundle is never touched. Missing/unreadable trusted host
     * sources leave the guest unchanged (no insecure network fallback).
     */
    fun repairCaIfMissing(root: File): Boolean = supplyCaIfMissing(root) != null

    private fun supplyCaIfMissing(root: File): String? {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return null
        val guest = File(root, "etc/ssl/certs/ca-certificates.crt")
        if (listOf("etc", "etc/ssl", "etc/ssl/certs").any {
                Files.isSymbolicLink(File(root, it).toPath())
            } || Files.isSymbolicLink(guest.toPath()) || hasContent(guest)) return null

        return runCatching {
            val host = hostCaBundle()?.takeIf { it.isFile && it.length() in 1..MAX_CA_BYTES.toLong() }
            val hostBytes = host?.readBytes()?.takeIf { validCaBundle(it) }
            val source = if (hostBytes != null) {
                "host bundle" to hostBytes
            } else {
                val systemBytes = trustedAndroidSystemCas() ?: return@runCatching null
                "Android system CA store" to systemBytes
            }
            val parent = guest.parentFile ?: return@runCatching null
            if (!parent.isDirectory && !parent.mkdirs()) return@runCatching null
            // Same-directory temporary file + rename: never expose a partial trust store.
            val temp = File.createTempFile(".ca-certificates-", ".tmp", parent)
            try {
                temp.writeBytes(source.second)
                // Guest processes run as non-root (Android app uid + su subshell);
                // a 0600 anchor file would make /etc/ssl/certs unreadable there.
                // createTempFile starts at 0600; CA bundles must be readable by
                // the non-root guest uid (and by su subshells), like 0644.
                temp.setReadable(true, false)
                temp.setReadable(true, true)
                if (Files.isSymbolicLink(guest.toPath()) || hasContent(guest)) return@runCatching null
                try {
                    Files.move(temp.toPath(), guest.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    Files.move(temp.toPath(), guest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                source.first
            } finally {
                temp.delete()
            }
        }.getOrNull()
    }

    private fun validCaBundle(bytes: ByteArray): Boolean = runCatching {
        if (bytes.isEmpty() || bytes.size > MAX_CA_BYTES) return@runCatching false
        val certs = CertificateFactory.getInstance("X.509")
            .generateCertificates(bytes.inputStream())
        certs.isNotEmpty() && certs.all { it is X509Certificate }
    }.getOrDefault(false)

    /** Mirrors Android SystemCertificateSource.isCertMarkedAsRemoved(). */
    private fun isCertMarkedAsRemoved(removedDir: File, caFile: String): Boolean =
        // File.exists() misses dangling markers; Files.exists() does not.
        java.nio.file.Files.exists(File(removedDir, caFile).toPath())

    private fun trustedAndroidSystemCas(): ByteArray? {
        val factory = CertificateFactory.getInstance("X.509")
        // APEX (Android 14+) takes precedence over the older /system store.
        // Never include user-installed credentials or arbitrary PEM files.
        val dirs = androidSystemCaDirs()
        val removedDirs = androidSystemCaRemovedDirs()
        for (index in dirs.indices) {
            val dir = dirs[index]
            // null removal dir = unknown revocation source for this system dir.
            val removedDir = removedDirs.getOrNull(index) ?: continue
            if (!dir.isDirectory) continue
            val certs = collectTrustedSystemCertificates(factory, dir, removedDir)
            if (certs.isNotEmpty()) return trustedBundle(certs)
        }
        return null
    }

    private fun trustedBundle(certs: List<X509Certificate>): ByteArray {
        val pem = StringBuilder()
        for (cert in certs) {
            val encoded = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(cert.encoded)
            pem.append("-----BEGIN CERTIFICATE-----\n").append(encoded).append("\n-----END CERTIFICATE-----\n")
        }
        require(pem.isNotEmpty() && pem.length <= MAX_CA_BYTES) { "invalid trusted bundle" }
        return pem.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun collectTrustedSystemCertificates(
        factory: CertificateFactory,
        dir: File,
        removedDir: File
    ): List<X509Certificate> {
        val seen = HashSet<String>()
        return dir.listFiles().orEmpty().asSequence()
            .filter { it.name.matches(SYSTEM_CA_FILENAME) && it.isFile &&
                !Files.isSymbolicLink(it.toPath()) && it.length() in 1..MAX_CERT_BYTES.toLong() }
            .filterNot { file -> isCertMarkedAsRemoved(removedDir, file.name) }
            .sortedBy { it.name }
            .take(MAX_SYSTEM_CERTS)
            .mapNotNull { file ->
                runCatching {
                    file.inputStream().use { factory.generateCertificate(it) as? X509Certificate }
                }.getOrNull()?.takeIf { it.basicConstraints >= 0 }
            }
            .filter { cert -> seen.add(cert.subjectX500Principal.name + cert.serialNumber) }
            .toList()
    }

    /**
     * DNS 决策链：注入 > host resolv.conf > 公共 DNS fallback（带 warning）。
     */
    private fun resolveDnsServers(warnings: MutableList<String>): List<String> {
        val injected = dnsServers().filter { it.isNotBlank() }
        if (injected.isNotEmpty()) return injected.distinct()

        val hostResolv = File("/etc/resolv.conf")
        if (hostResolv.isFile) {
            val servers = hostResolv.readLines()
                .map { it.trim() }
                .filter { it.startsWith("nameserver") }
                .map { it.removePrefix("nameserver").trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
            if (servers.isNotEmpty()) return servers.distinct()
        }
        warnings.add(
            "resolv.conf: no injected DNS and no host /etc/resolv.conf — " +
                "falling back to public resolvers (223.5.5.5, 119.29.29.29, 8.8.8.8, 1.1.1.1); " +
                "production Android should inject system DNS at DI time"
        )
        // 顺序即优先级：glibc/ICU resolver 从首个开始试。223.5.5.5（AliDNS）与
        // 119.29.29.29（DNSPod）均为 anycast，全球可达且在中国大陆质量稳定；
        // 8.8.8.8/1.1.1.1 在大陆常被墙/污染 —— 放在末尾仅作全球兜底。
        return listOf("223.5.5.5", "119.29.29.29", "8.8.8.8", "1.1.1.1")
    }

    private fun hasContent(f: File): Boolean = f.isFile && f.length() > 0

    /**
     * P1（DNS 快照刷新）：resolv.conf 是**安装时刻**的 DNS 快照 —— configure()
     * 只在 doInstall 内执行一次，「非空即保留」策略让切网（Wi-Fi→蜂窝/VPN）
     * 后 guest 内 apt/pip/curl 的 DNS 全灭且无任何修复通道（repair 不重写、
     * ensureReady 短路）。本方法对比宿主当前 DNS 与文件内容，变化时重写。
     *
     * - 幂等且廉价（一次 DNS provider 调用 + 一次小文件读）；
     * - DNS 兑底链拿不到有效服务器（无网络等）时不动作（返回 false）——
     *   绝不把「不知道」写成「8.8.8.8」假装可用；
     * - 符号链接/损坏条目处理同 configure() 的加固逻辑。
     *
     * @return true = 已重写（网络已切换）；false = 无变化 / 无 DNS / rootfs 不可用。
     */
    fun refreshDnsIfChanged(root: File): Boolean {
        if (!root.isDirectory) return false
        val warnings = mutableListOf<String>()
        val dns = resolveDnsServers(warnings)
        if (dns.isEmpty()) return false
        val desired = dns.distinct().joinToString("\n") { "nameserver $it" } + "\n"
        val resolv = File(root, "etc/resolv.conf")
        val current = runCatching {
            resolv.takeIf { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                ?.readText()
        }.getOrNull()
        if (current != null && current.trim() == desired.trim()) return false
        val wrote = runCatching {
            if (resolv.exists() || java.nio.file.Files.exists(resolv.toPath())) {
                resolv.delete() // dangling symlink / 旧普通文件统一先删
            }
            resolv.parentFile?.mkdirs()
            resolv.writeText(desired)
            true
        }.getOrDefault(false)
        return wrote
    }

    companion object {
        const val DEFAULT_HOSTNAME = "android-guru"
        private const val MAX_CA_BYTES = 8 * 1024 * 1024
        private const val MAX_CERT_BYTES = 64 * 1024
        private const val MAX_SYSTEM_CERTS = 512
        private val SYSTEM_CA_FILENAME = Regex("^[0-9a-f]{8}\\.[0-9]+$")

        /**
         * 解析 /proc/self/status 的 “Groups:” 行（实录 #14 的生产采集端）。
         *
         * 为什么不用 android.system.Os.getgroups()：该方法在 App 编译 SDK 的
         * android.system.Os 里不存在（CI 实测 unresolved reference）。
         * /proc/self/status 是内核态真值，全 Android 版本可读、纯 JVM 无 API
         * 依赖 —— PRoot 继承的正是这份补充组列表。
         */
        fun parseSupplementaryGids(statusContent: String): List<Int> =
            statusContent.lineSequence()
                .firstOrNull { it.startsWith("Groups:") }
                ?.removePrefix("Groups:")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.split(Regex("\\s+"))
                ?.mapNotNull { it.toIntOrNull() }
                ?.distinct()
                ?: emptyList()

        /**
         * 为缺失 gid 生成 /etc/group 行（实录 #14；纯函数可单测）：
         * - 幂等：已存在的 gid（第三字段）跳过；
         * - g<gid> 命名避开真实组名冲突；负数/零过滤。
         */
        fun buildGroupAdditions(existingGroupContent: String, gids: List<Int>): List<String> {
            val existingGids = existingGroupContent.lines()
                .mapNotNull { it.split(":").getOrNull(2)?.trim()?.toIntOrNull() }
                .toSet()
            return gids.distinct().filter { it > 0 && it !in existingGids }
                .map { "g$it:x:$it:" }
        }
    }
}
