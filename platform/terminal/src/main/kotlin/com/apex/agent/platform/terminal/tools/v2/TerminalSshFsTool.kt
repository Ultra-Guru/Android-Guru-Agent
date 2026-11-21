package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.TerminalFs.RemoteSshFilesystem
import com.apex.agent.platform.TerminalFs.RemoteSshFilesystem.SshTarget
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: TerminalSshFs —— SSH/SFTP 远程文件系统（Operit 对标 §4.7 缺项）。
 *
 * 在远端主机上做结构化文件操作（SFTP 二进制安全、连接复用、结构化错误），
 * 取代 `ssh user@host "cat > f"` 的引号地狱。与 TerminalFs（Ubuntu/PRoot
 * guest fs）互补：TerminalFs 作用域是本地 rootfs，TerminalSshFs 作用域是
 * 远端主机（经 SSH 通道）。
 *
 * JSON Schema (input):
 *   { action: "ping"|"read"|"write"|"append"|"list"|"stat"|"exists"|"mkdir"|
 *             "remove"|"move"|"copy"|"disconnect"|"fingerprint",
 *     host: string, port?: int, username: string,
 *     password?: string, privateKey?: string, passphrase?: string,
 *     trustUnknownHost?: bool,
 *     path?: string, content?: string, target?: string, recursive?: bool }
 *
 * 写语义：内容经 UTF-8 编码后走 SFTP WRITE 通道（无 shell、无 base64 往返），
 * 二进制安全由 SFTP 协议保证。
 *
 * 安全默认：未知 host key 默认拒绝（SshFsError:ConnectFailed + 指纹未知提示）。
 * 用户先调 fingerprint action 拿到 SHA-256 指纹 → 确认后调用 trustUnknownHost
 * = true 一次性放行（或经 KnownHostsStore 持久信任）。
 *
 * Errors (structured):
 *   SshFsError:InvalidInput / InvalidPath / PathDenied / ConnectFailed /
 *   NoSuchFile / PermissionDenied / AlreadyExists / TooLarge / RemoteFailure /
 *   IOError / Unknown
 */
class TerminalSshFsTool(
    private val fs: RemoteSshFilesystem
) : TerminalTool {

    override val id: String = "TerminalSshFs"
    override val name: String = id
    override val description: String = """
        Structured file operations on a REMOTE host over SSH/SFTP. Complements TerminalFs
        (which targets the local Ubuntu/PRoot rootfs); this tool reaches a remote machine.
        Actions: ping (verify connectivity + auth), read, write, append, list, stat, exists,
        mkdir, remove, move, copy, disconnect (close cached connection), fingerprint
        (return remote SSH host-key fingerprint for first-use trust).
        Connection re-use: one SSHClient per (host,port,username,credential) target, cached
        across calls — multiple ops share a single TCP/SSH session.
        Auth: password OR privateKey (PEM, optional passphrase). Binary-safe via SFTP byte
        streams (no shell quoting, no base64 round-trip).
        Host-key verification: unknown hosts are REFUSED by default (anti-MITM). Call the
        fingerprint action first to show the SHA-256 fingerprint to the user, then re-issue
        with trustUnknownHost=true to bypass (or persist the fingerprint in a KnownHostsStore).
        Write cap: ${RemoteSshFilesystemCompanion.MAX_WRITE_BYTES_DESC}. Refuses removing "/" or ".".
        Structured errors, never message-strings.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{
  "action":{"type":"string","enum":["ping","read","write","append","list","stat","exists","mkdir","remove","move","copy","disconnect","fingerprint"]},
  "host":{"type":"string"},"port":{"type":"integer","default":22},
  "username":{"type":"string"},"password":{"type":"string"},
  "privateKey":{"type":"string"},"passphrase":{"type":"string"},
  "trustUnknownHost":{"type":"boolean","default":false},
  "path":{"type":"string"},"content":{"type":"string"},"target":{"type":"string"},"recursive":{"type":"boolean","default":false}
},"required":["action"]}
""".trim()

    data class Input(
        val action: String,
        val host: String?,
        val port: Int,
        val username: String?,
        val password: String?,
        val privateKey: String?,
        val passphrase: String?,
        val trustUnknownHost: Boolean,
        val path: String?,
        val content: String?,
        val target: String?,
        val recursive: Boolean
    )

    data class Output(
        val ok: Boolean,
        val code: String,
        val message: String?,
        val payload: Any?,
        val durationMs: Long
    )

    suspend fun execute(input: Input): Output {
        if (input.action != "fingerprint" && input.action != "disconnect" &&
            (input.host.isNullOrBlank() || input.username.isNullOrBlank())
        ) {
            return err("SshFsError:InvalidInput", "host and username required for action '${input.action}'")
        }
        val target = SshTarget(
            host = input.host ?: "",
            port = input.port,
            username = input.username ?: "",
            password = input.password,
            privateKey = input.privateKey,
            passphrase = input.passphrase,
            trustUnknownHost = input.trustUnknownHost
        )
        // 路径依赖 action 的：read/write/append/list/stat/exists/mkdir/remove/move/copy
        val needsPath = input.action in PATH_ACTIONS
        if (needsPath && input.path.isNullOrBlank()) {
            return err("SshFsError:InvalidInput", "path required for action '${input.action}'")
        }
        val result = when (input.action) {
            "ping" -> fs.ping(target)
            "read" -> fs.read(target, input.path!!)
            "write" -> fs.write(target, input.path!!, input.content ?: "")
            "append" -> fs.append(target, input.path!!, input.content ?: "")
            "list" -> fs.list(target, input.path!!)
            "stat" -> fs.stat(target, input.path!!)
            "exists" -> fs.exists(target, input.path!!)
            "mkdir" -> fs.mkdir(target, input.path!!)
            "remove" -> fs.remove(target, input.path!!, input.recursive)
            "move" -> {
                val dst = input.target ?: return err("SshFsError:InvalidInput", "target required for move")
                fs.move(target, input.path!!, dst)
            }
            "copy" -> {
                val dst = input.target ?: return err("SshFsError:InvalidInput", "target required for copy")
                fs.copy(target, input.path!!, dst)
            }
            "disconnect" -> fs.disconnect(target)
            "fingerprint" -> fs.hostKeyFingerprint(target)
            else -> return err("SshFsError:InvalidInput", "unknown action '${input.action}'")
        }
        return when (result) {
            is RemoteSshFilesystem.SshFsResult.Ok -> Output(true, "OK", null, result.value, result.durationMs)
            is RemoteSshFilesystem.SshFsResult.Err -> Output(false, result.code, result.message, null, 0)
        }
    }

    private fun err(code: String, msg: String): Output =
        Output(false, code, msg, null, 0)

    companion object {
        private val PATH_ACTIONS = setOf(
            "read", "write", "append", "list", "stat", "exists", "mkdir", "remove", "move", "copy"
        )
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val action = json["action"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("action required")
        val input = Input(
            action = action,
            host = json["host"]?.jsonPrimitive?.contentOrNull(),
            port = json["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 22,
            username = json["username"]?.jsonPrimitive?.contentOrNull(),
            password = json["password"]?.jsonPrimitive?.contentOrNull(),
            privateKey = json["privateKey"]?.jsonPrimitive?.contentOrNull(),
            passphrase = json["passphrase"]?.jsonPrimitive?.contentOrNull(),
            trustUnknownHost = json["trustUnknownHost"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            path = json["path"]?.jsonPrimitive?.contentOrNull(),
            content = json["content"]?.jsonPrimitive?.contentOrNull(),
            target = json["target"]?.jsonPrimitive?.contentOrNull(),
            recursive = json["recursive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        )
        val out = execute(input)
        return buildJsonObject {
            put("ok", JsonPrimitive(out.ok))
            put("code", JsonPrimitive(out.code))
            out.message?.let { put("message", JsonPrimitive(it)) }
            put("durationMs", JsonPrimitive(out.durationMs))
            val p = out.payload
            when (p) {
                is RemoteSshFilesystem.SshReadResult -> {
                    put("text", JsonPrimitive(p.text))
                    put("bytes", JsonPrimitive(p.bytes.size))
                    put("base64", JsonPrimitive(p.base64))
                }
                is RemoteSshFilesystem.SshFileInfo -> {
                    put("path", JsonPrimitive(p.path))
                    put("type", JsonPrimitive(p.type))
                    put("sizeBytes", JsonPrimitive(p.sizeBytes))
                    put("modifiedEpochSec", JsonPrimitive(p.modifiedEpochSec))
                    put("permissions", JsonPrimitive(p.permissions))
                }
                is List<*> -> {
                    val entries = p.map { e ->
                        when (e) {
                            is RemoteSshFilesystem.SshDirEntry -> buildJsonObject {
                                put("name", JsonPrimitive(e.name))
                                put("type", JsonPrimitive(e.type))
                                put("sizeBytes", JsonPrimitive(e.sizeBytes))
                            }
                            else -> null
                        }
                    }.filterNotNull()
                    put("entries", kotlinx.serialization.json.JsonArray(entries))
                }
                is Boolean -> put("exists", JsonPrimitive(p))
                is String -> put("fingerprint", JsonPrimitive(p))
                is Unit -> { /* no payload for write-like ops */ }
            }
        }.toString()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else this.content
}

/** 描述常量（避免 description 模板引用 KDoc 私有 companion）。 */
private object RemoteSshFilesystemCompanion {
    const val MAX_WRITE_BYTES_DESC = "8 MiB per write call"
}
