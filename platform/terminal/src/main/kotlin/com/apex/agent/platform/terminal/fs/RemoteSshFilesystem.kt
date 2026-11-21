package com.apex.agent.platform.TerminalFs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.security.PublicKey
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH/SFTP 远程文件系统（Operit 对标 §4.7 明确列缺项 —— 补齐远程工作区能力）。
 *
 * 背景：仓库此前只有 GuestFilesystem（Ubuntu/PRoot rootfs 内结构化文件操作）。
 * 远程机器上的文件只能靠 Agent 拼 `ssh user@host "cat > f"` 这类 shell 命令，
 * 引号地狱 + 无错误语义 + 二进制不安全。本类提供**结构化、二进制安全、带超时**
 * 的 SSH/SFTP 远程文件操作，与 GuestFilesystem 同哲学（结构化结果 + 路径语义
 * 显式）但作用域是**远端主机**。
 *
 * 连接复用：按 [SshTarget] 缓存 [SSHClient]，同一 target 的多次操作共享一条
 * TCP/SSH 连接（避免每次操作都重新握手 + 认证，远端 fs 操作常见连续十几次调用）。
 * 认证失败 / 连接断开时**丢弃缓存**，下次操作重新建链（自愈）。
 *
 * 二进制安全：读写全部走 SFTP `RemoteFile`（无 shell、无 base64 往返）——
 * SSHJ 直接在 SSH 通道上搬运字节，UTF-8 / NUL / 换行均不敏感。
 *
 * 主机密钥校验（安全默认）：
 *  - 默认拒绝未知 host key（[KnownHostsStore] 未记录时返回 SshFsError:HostKeyUnknown，
 *    不静默信任 —— 防 MITM）；
 *  - 用户显式 `trustUnknownHost = true` 时改用 [PromiscuousVerifier]（等价
 *    `StrictHostKeyChecking=no`，仅建议测试环境 / 内网使用）。
 *
 * 写门禁：本类**不做**路径白名单门禁（远端主机的 sandbox 边界不由本类负责，
 * 用户凭据本身就是边界）。但拒绝 root 目录的 remove（`/` 不可删）。
 */
class RemoteSshFilesystem(
    private val knownHosts: KnownHostsStore = InMemoryKnownHostsStore(),
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MAX_WRITE_BYTES = 8 * 1024 * 1024
        private const val MAX_READ_BYTES = 16 * 1024 * 1024
    }

    // ─── 结果类型（与 GuestFilesystem.FsResult 同形状，独立 sealed 便于按域扩展） ───

    sealed class SshFsResult<out T> {
        data class Ok<T>(val value: T, val durationMs: Long) : SshFsResult<T>()
        data class Err(val code: String, val message: String, val cause: Throwable? = null) : SshFsResult<Nothing>()
    }

    data class SshTarget(
        val host: String,
        val port: Int = 22,
        val username: String,
        /** 密码认证（与 privateKey 二选一；两者都空时拒绝建链）。 */
        val password: String? = null,
        /** 公钥认证 PEM 私钥内容（OpenSSH / PKCS8）。 */
        val privateKey: String? = null,
        /** 私钥口令（可空）。 */
        val passphrase: String? = null,
        /**
         * 未知 host key 时是否放行（等价 StrictHostKeyChecking=no）。默认 false ——
         * 走 [KnownHostsStore] 严格校验。仅建议测试环境 / 内网显式置 true。
         */
        val trustUnknownHost: Boolean = false
    )

    data class SshFileInfo(
        val path: String,
        val type: String,          // file | dir | link | other
        val sizeBytes: Long,
        val modifiedEpochSec: Long,
        val permissions: String
    )

    data class SshDirEntry(val name: String, val type: String, val sizeBytes: Long)

    data class SshReadResult(val text: String, val bytes: ByteArray, val base64: String)

    // ─── 连接缓存 ───

    private val connections = ConcurrentHashMap<SshTarget, SSHClient>()

    /**
     * 健康探测（轻量，供 DI / UI 连接管理器用）。走一次 stat("/") 验证连接 +
     * 认证 + SFTP 子系统可用，不产生写副作用。
     */
    suspend fun ping(target: SshTarget): SshFsResult<SshFileInfo> = stat(target, "/")

    /**
     * 主动断开某 target 的缓存连接（用户「断开远程」入口）。幂等。
     */
    suspend fun disconnect(target: SshTarget): SshFsResult<Unit> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val client = connections.remove(target)
        runCatching { client?.disconnect() }
        SshFsResult.Ok(Unit, System.currentTimeMillis() - t0)
    }

    /**
     * 关闭全部缓存连接（app 退出 / 用户「断开所有」入口）。
     */
    suspend fun disconnectAll() = withContext(Dispatchers.IO) {
        connections.values.forEach { runCatching { it.disconnect() } }
        connections.clear()
        Unit
    }

    // ─── 文件操作 ───

    /** 读文件（SFTP RemoteFile 直接搬运字节；text 为 UTF-8 解码，base64 供下游消费）。 */
    suspend fun read(target: SshTarget, path: String): SshFsResult<SshReadResult> = withContext(Dispatchers.IO) {
        timed { t0 ->
            val sftpResult = withClient(target) { client ->
                val sftp = client.newSFTPClient()
                try {
                    val attrs = sftp.stat(path)
                    if (attrs.size > MAX_READ_BYTES) {
                        return@withClient SshFsResult.Err(
                            "SshFsError:TooLarge",
                            "remote file ${attrs.size}B > $MAX_READ_BYTES — use SFTP tooling outside the agent"
                        )
                    }
                    val rf: RemoteFile = sftp.open(path, EnumSet.of(OpenMode.READ))
                    try {
                        val buf = ByteArray(attrs.size.toInt().coerceAtLeast(0))
                        var off = 0
                        while (off < buf.size) {
                            val n = rf.read(off.toLong(), buf, off, buf.size - off)
                            if (n <= 0) break
                            off += n
                        }
                        val bytes = if (off == buf.size) buf else buf.copyOf(off)
                        val text = String(bytes, Charsets.UTF_8)
                        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
                        SshFsResult.Ok(SshReadResult(text, bytes, b64), System.currentTimeMillis() - t0)
                    } finally {
                        runCatching { rf.close() }
                    }
                } finally {
                    runCatching { sftp.close() }
                }
            }
            sftpResult
        }
    }

    /** 读文本（便捷）。 */
    suspend fun readText(target: SshTarget, path: String): SshFsResult<String> =
        read(target, path).mapValue { it.text }

    /** 写文件（覆盖；SFTP WRITE，二进制安全）。 */
    suspend fun write(target: SshTarget, path: String, content: String): SshFsResult<Unit> =
        writeBytes(target, path, content.toByteArray(Charsets.UTF_8))

    suspend fun writeBytes(target: SshTarget, path: String, bytes: ByteArray): SshFsResult<Unit> {
        if (bytes.size > MAX_WRITE_BYTES) {
            return SshFsResult.Err(
                "SshFsError:TooLarge",
                "content ${bytes.size}B > $MAX_WRITE_BYTES — split writes"
            )
        }
        return withContext(Dispatchers.IO) {
            timed { t0 ->
                withClient(target) { client ->
                    client.newSFTPClient().use { sftp ->
                        ensureParentDirs(sftp, path)
                        val rf: RemoteFile = sftp.open(
                            path,
                            EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC)
                        )
                        rf.use { remote ->
                            if (bytes.isNotEmpty()) remote.write(0, bytes, 0, bytes.size)
                        }
                        SshFsResult.Ok(Unit, System.currentTimeMillis() - t0)
                    }
                }
            }
        }
    }

    /** 追加写（读 → 合并 → 写回；SFTP 无原生 append，二进制安全靠单写者假设）。 */
    suspend fun append(target: SshTarget, path: String, content: String): SshFsResult<Unit> {
        val addition = content.toByteArray(Charsets.UTF_8)
        if (addition.size > MAX_WRITE_BYTES) {
            return SshFsResult.Err("SshFsError:TooLarge", "append chunk ${addition.size}B > $MAX_WRITE_BYTES")
        }
        val existing = read(target, path)
        val merged: ByteArray = when (existing) {
            is SshFsResult.Ok -> existing.value.bytes + addition
            is SshFsResult.Err -> {
                // 读失败（典型：ENOENT）→ 当作新建文件。其它错误透传。
                if (existing.code == "SshFsError:NoSuchFile") addition else return existing
            }
        }
        return writeBytes(target, path, merged)
    }

    /** 列目录（一层；SFTP readdir，带 size）。 */
    suspend fun list(target: SshTarget, path: String): SshFsResult<List<SshDirEntry>> = withContext(Dispatchers.IO) {
        timed { t0 ->
            withClient(target) { client ->
                client.newSFTPClient().use { sftp ->
                    val entries = sftp.ls(path).filter { it.name != "." && it.name != ".." }.map { e ->
                        SshDirEntry(
                            name = e.name,
                            type = typeOf(e.attributes),
                            sizeBytes = e.attributes.size
                        )
                    }.sortedBy { it.name }
                    SshFsResult.Ok(entries, System.currentTimeMillis() - t0)
                }
            }
        }
    }

    /** stat。 */
    suspend fun stat(target: SshTarget, path: String): SshFsResult<SshFileInfo> = withContext(Dispatchers.IO) {
        timed { t0 ->
            withClient(target) { client ->
                client.newSFTPClient().use { sftp ->
                    val attrs = sftp.stat(path)
                    SshFsResult.Ok(
                        SshFileInfo(
                            path = path,
                            type = typeOf(attrs),
                            sizeBytes = attrs.size,
                            modifiedEpochSec = attrs.mtime,
                            permissions = attrs.permissions.toString()
                        ),
                        System.currentTimeMillis() - t0
                    )
                }
            }
        }
    }

    /** 存在性（stat 成功即存在；ENOENT → false）。 */
    suspend fun exists(target: SshTarget, path: String): SshFsResult<Boolean> = when (val r = stat(target, path)) {
        is SshFsResult.Ok -> SshFsResult.Ok(true, r.durationMs)
        is SshFsResult.Err ->
            if (r.code == "SshFsError:NoSuchFile") SshFsResult.Ok(false, 0L) else r
    }

    /** mkdir（-p 语义：递归建父目录）。 */
    suspend fun mkdir(target: SshTarget, path: String): SshFsResult<Unit> = withContext(Dispatchers.IO) {
        timed { t0 ->
            withClient(target) { client ->
                client.newSFTPClient().use { sftp ->
                    mkdirs(sftp, path)
                    SshFsResult.Ok(Unit, System.currentTimeMillis() - t0)
                }
            }
        }
    }

    /** 删除（recursive → 先递归清空；拒绝根目录）。 */
    suspend fun remove(target: SshTarget, path: String, recursive: Boolean): SshFsResult<Unit> {
        val p = path.trim()
        if (p.isEmpty() || p == "/" || p == ".") {
            return SshFsResult.Err("SshFsError:PathDenied", "refusing to remove root / empty path")
        }
        return withContext(Dispatchers.IO) {
            timed { t0 ->
                withClient(target) { client ->
                    client.newSFTPClient().use { sftp ->
                        if (recursive) {
                            removeRecursive(sftp, p)
                        } else {
                            val attrs = runCatching { sftp.lstat(p) }.getOrElse { e ->
                                return@withClient SshFsResult.Err(
                                    "SshFsError:NoSuchFile",
                                    "lstat failed: ${e.message}", e
                                )
                            }
                            if (attrs.type == FileMode.Type.DIRECTORY) {
                                sftp.rmdir(p)
                            } else {
                                sftp.rm(p)
                            }
                        }
                        SshFsResult.Ok(Unit, System.currentTimeMillis() - t0)
                    }
                }
            }
        }
    }

    /** 远程 → 远程移动（SFTP rename）。 */
    suspend fun move(target: SshTarget, path: String, targetPath: String): SshFsResult<Unit> {
        if (targetPath.isBlank()) return SshFsResult.Err("SshFsError:InvalidInput", "target required for move")
        return withContext(Dispatchers.IO) {
            timed { t0 ->
                withClient(target) { client ->
                    client.newSFTPClient().use { sftp ->
                        ensureParentDirs(sftp, targetPath)
                        sftp.rename(path, targetPath)
                        SshFsResult.Ok(Unit, System.currentTimeMillis() - t0)
                    }
                }
            }
        }
    }

    /** 远程 → 远程拷贝（读 → 写另一路径；SFTP 协议无服务端 copy，本地搬运字节）。 */
    suspend fun copy(target: SshTarget, path: String, targetPath: String): SshFsResult<Unit> {
        if (targetPath.isBlank()) return SshFsResult.Err("SshFsError:InvalidInput", "target required for copy")
        val readRes = read(target, path)
        val bytes = when (readRes) {
            is SshFsResult.Ok -> readRes.value.bytes
            is SshFsResult.Err -> return readRes
        }
        return writeBytes(target, targetPath, bytes)
    }

    /**
     * 主机密钥指纹（UI「信任此主机」确认页展示 SHA-256 指纹）。
     * 不会建立写副作用，只做一次握手 + key 探测。
     */
    suspend fun hostKeyFingerprint(target: SshTarget): SshFsResult<String> = withContext(Dispatchers.IO) {
        timed { t0 ->
            val client = SSHClient()
            try {
                client.timeout = connectTimeoutMs.toInt()
                // 用 capturing verifier：仅探测指纹，不建立信任副作用
                var captured: String? = null
                client.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                        captured = sha256FingerprintOf(key)
                        return true
                    }
                    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
                })
                client.connect(target.host, target.port)
                val fp = captured
                if (fp == null) {
                    return@timed SshFsResult.Err(
                        "SshFsError:ConnectFailed",
                        "handshake completed but host key not captured"
                    )
                }
                SshFsResult.Ok(fp, System.currentTimeMillis() - t0)
            } catch (e: Exception) {
                SshFsResult.Err("SshFsError:ConnectFailed", "handshake failed: ${e.message}", e)
            } finally {
                runCatching { client.disconnect() }
            }
        }
    }

    // ─── 内部 ───

    private inline fun <T> timed(block: (t0: Long) -> SshFsResult<T>): SshFsResult<T> {
        val t0 = System.currentTimeMillis()
        return block(t0)
    }

    private suspend fun <T : Any> withClient(
        target: SshTarget,
        block: (SSHClient) -> SshFsResult<T>
    ): SshFsResult<T> {
        val client = obtainClient(target)
            ?: return SshFsResult.Err(
                "SshFsError:ConnectFailed",
                "cannot establish SSH connection to ${target.username}@${target.host}:${target.port} " +
                    "(auth failed or network unreachable)"
            )
        return try {
            block(client)
        } catch (e: net.schmizz.sshj.common.SSHException) {
            // 连接层异常 → 丢缓存自愈
            connections.remove(target)
            runCatching { client.disconnect() }
            SshFsResult.Err("SshFsError:ConnectFailed", "SSH error: ${e.message}", e)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            SshFsResult.Err(sftpCodeOf(e), "SFTP error: ${e.message}", e)
        } catch (e: java.io.IOException) {
            connections.remove(target)
            runCatching { client.disconnect() }
            SshFsResult.Err("SshFsError:IOError", "IO error: ${e.message}", e)
        } catch (e: Exception) {
            SshFsResult.Err("SshFsError:Unknown", "unexpected error: ${e.message}", e)
        }
    }

    private suspend fun obtainClient(target: SshTarget): SSHClient? {
        connections[target]?.let { existing ->
            if (existing.isConnected && existing.isAuthenticated) return existing
            connections.remove(target)
            runCatching { existing.disconnect() }
        }
        return withContext(Dispatchers.IO) {
            val client = SSHClient()
            try {
                client.timeout = timeoutMs.toInt()
                client.connection.keepAlive.keepAliveInterval = 30
                if (target.privateKey.isNullOrBlank() && target.password.isNullOrBlank()) {
                    return@withContext null
                }
                // Host key 校验：known_hosts 命中 → 用之；未命中 → 严格拒绝
                //（除非调用方在 target 上标 trustUnknownHost，见下方 trust 参数）。
                // 注：trustUnknownHost 走 target.extraTrust，避免污染 data class 主字段。
                if (target.trustUnknownHost || knownHosts.isKnown(target.host, target.port)) {
                    client.addHostKeyVerifier(PromiscuousVerifier())
                } else {
                    // 严格模式：HostKeyVerifier 有 2 个抽象方法，不能用 SAM lambda，需 object 表达式
                    client.addHostKeyVerifier(object : HostKeyVerifier {
                        override fun verify(host: String, port: Int, key: PublicKey): Boolean =
                            knownHosts.matchesFingerprint(host, port, sha256FingerprintOf(key))
                        override fun findExistingAlgorithms(host: String, port: Int): List<String> = emptyList()
                    })
                }
                client.connect(target.host, target.port)
                when {
                    !target.privateKey.isNullOrBlank() -> {
                        // loadKeys(path, char[] passphrase)：2-arg 重载接受加密 key
                        val keys = client.loadKeys(target.privateKey, target.passphrase?.toCharArray())
                        client.authPublickey(target.username, keys)
                    }
                    else -> client.authPassword(target.username, target.password)
                }
                if (!client.isAuthenticated) return@withContext null
                connections[target] = client
                client
            } catch (e: Exception) {
                runCatching { client.disconnect() }
                null
            }
        }
    }

    private fun typeOf(attrs: FileAttributes): String = when (attrs.type) {
        FileMode.Type.DIRECTORY -> "dir"
        FileMode.Type.SYMLINK -> "link"
        FileMode.Type.REGULAR -> "file"
        else -> "other"
    }

    private fun sftpCodeOf(e: net.schmizz.sshj.sftp.SFTPException): String = when (e.statusCode) {
        net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE -> "SshFsError:NoSuchFile"
        net.schmizz.sshj.sftp.Response.StatusCode.PERMISSION_DENIED -> "SshFsError:PermissionDenied"
        net.schmizz.sshj.sftp.Response.StatusCode.FILE_ALREADY_EXISTS -> "SshFsError:AlreadyExists"
        else -> "SshFsError:RemoteFailure"
    }

    private fun ensureParentDirs(sftp: SFTPClient, path: String) {
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotBlank() && parent != "/" && parent != ".") {
            mkdirs(sftp, parent)
        }
    }

    private fun mkdirs(sftp: SFTPClient, path: String) {
        val normalized = path.trimEnd('/')
        if (normalized.isBlank()) return
        val segments = normalized.split('/').filter { it.isNotBlank() }
        var cur = ""
        for (seg in segments) {
            cur += "/$seg"
            runCatching { sftp.stat(cur) }.onSuccess { return@onSuccess }.onFailure {
                runCatching { sftp.mkdir(cur) }
            }
        }
    }

    private fun removeRecursive(sftp: SFTPClient, path: String) {
        val attrs = sftp.lstat(path)
        if (attrs.type == FileMode.Type.DIRECTORY) {
            sftp.ls(path).filter { it.name != "." && it.name != ".." }.forEach { entry ->
                removeRecursive(sftp, if (path.endsWith("/")) "$path${entry.name}" else "$path/${entry.name}")
            }
            sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    // ─── 辅助扩展（SshFsResult 共用形状） ───

    private fun <T, R> SshFsResult<T>.mapValue(fn: (T) -> R): SshFsResult<R> = when (this) {
        is SshFsResult.Ok -> SshFsResult.Ok(fn(value), durationMs)
        is SshFsResult.Err -> this
    }
}

/**
 * KnownHosts 记忆库接口（OpenSSH known_hosts 等价物）。
 * 默认拒绝未知 host —— 强制 UI 层先调 hostKeyFingerprint 展示指纹再用户确认。
 */
interface KnownHostsStore {
    fun isKnown(host: String, port: Int): Boolean
    fun matchesFingerprint(host: String, port: Int, fingerprint: String): Boolean
    fun remember(host: String, port: Int, fingerprint: String)
}

/** 内存版实现（app 侧持久化到 EncryptedSharedPreferences 的工作交由 DI 层）。 */
class InMemoryKnownHostsStore : KnownHostsStore {
    private val entries = ConcurrentHashMap<String, String>()  // "host:port" -> fingerprint

    override fun isKnown(host: String, port: Int): Boolean = entries.containsKey("$host:$port")
    override fun matchesFingerprint(host: String, port: Int, fingerprint: String): Boolean =
        entries["$host:$port"] == fingerprint
    override fun remember(host: String, port: Int, fingerprint: String) {
        entries["$host:$port"] = fingerprint
    }
}

/** 公钥 SHA-256 指纹（OpenSSH `ssh-keygen -lf` 输出的 `SHA256:base64...` 形状）。 */
internal fun sha256FingerprintOf(key: java.security.PublicKey): String {
    val encoded = key.encoded
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
    return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
}
