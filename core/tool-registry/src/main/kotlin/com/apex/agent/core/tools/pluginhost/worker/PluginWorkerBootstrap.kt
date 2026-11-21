package com.apex.agent.core.tools.pluginhost.worker

import com.apex.agent.core.tools.pluginhost.opencode.OpenCodeWorkerScript
import java.io.File

/**
 * # Plugin Worker Bootstrap —— Worker 侧 JS 引导脚本（协议 v1）
 *
 * 宿主把它与插件同目录落盘后经 RuntimeProvider 启动（`node bootstrap.js`
 * 或 `bun bootstrap.js`）。职责：
 *
 * 1. **STDOUT 纯协议**：每行一个 JSON 信封（WorkerProtocol v1）；
 *    console.log 被重定向到 STDERR —— 插件日志永不污染 IPC 流；
 * 2. **通用插件模块契约**（apex-plugin-worker-v1，与生态无关）：
 *    - ESM：`export const tools = { 名字: { description, parametersSchema,
 *      execute(args) } }`，可选 `export async function setup(ctx)`；
 *    - CJS：`module.exports = { tools: {...}, setup: ... }`；
 * 3. **OpenCode 兼容层**（Phase 4）：`load.ecosystem = opencode-v1/v2` 时
 *    走 [OpenCodeWorkerScript.SHIM] 的 ctx 桥 —— V2 `Plugin.define` +
 *    `ctx.tool.transform/hook` + storage；V1 hook map。
 * 4. 工具执行异常 → error 结果（进程不退出，错误隔离）；
 * 5. 致命错误（加载失败）→ fatal 消息 + 退出码 1。
 *
 * Kotlin 侧以原始字符串持有脚本源 —— JS 代码刻意**不用模板字面量**
 * （避免与 Kotlin 字符串插值冲突），全部字符串拼接。
 */
object PluginWorkerBootstrap {

    // .cjs 扩展名：node 恒按 CommonJS 加载 —— 插件目录的 package.json 若声明
    // type:module，同名 .js 引导会被误判 ESM（require 未定义），.cjs 免疫。
    const val FILE_NAME = "apex-plugin-worker-bootstrap.cjs"

    private val GENERIC: String = """
'use strict';

// ── STDOUT 保护：console.* 全部改道 STDERR ─────────────────────────────
console.log = function () { console.error.apply(console, arguments); };
console.info = console.log;
console.debug = console.log;

// ── 协议信封 ────────────────────────────────────────────────────────────
const PROTOCOL = 'plugin-host';
const VERSION = 1;

function send(type, requestId, payload) {
  const envelope = { protocol: PROTOCOL, version: VERSION, type: type };
  if (requestId !== undefined && requestId !== null) envelope.requestId = requestId;
  if (payload !== undefined) envelope.payload = payload;
  process.stdout.write(JSON.stringify(envelope) + '\n');
}

function fatal(reason) {
  try { send('fatal', null, { reason: String(reason) }); } catch (e) {
  }
  process.exit(1);
}

// ── 插件模块状态 ────────────────────────────────────────────────────────
let pluginModule = null;
let loaded = false;
const pendingCancels = new Set();

function isNonNullObject(v) { return v !== null && typeof v === 'object'; }

function nearestPackageType(entryFile) {
  const path = require('path');
  const fs = require('fs');
  let dir = path.dirname(path.resolve(entryFile));
  for (let i = 0; i < 8; i++) {
    const pkgPath = path.join(dir, 'package.json');
    if (fs.existsSync(pkgPath)) {
      try {
        const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
        if (pkg.type === 'module') return 'esm';
        if (pkg.type === 'commonjs') return 'cjs';
        return 'cjs';
      } catch (e) { return 'cjs'; }
    }
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return 'cjs';
}

async function loadModule(entryPath, formatHint) {
  const path = require('path');
  const fs = require('fs');
  const resolved = path.resolve(String(entryPath));
  if (!fs.existsSync(resolved)) {
    throw new Error('entry not found: ' + resolved);
  }
  let moduleType = 'cjs';
  let entryFile = resolved;
  let pkgType = null;
  if (fs.existsSync(resolved) && fs.statSync(resolved).isDirectory()) {
    const pkgPath = path.join(resolved, 'package.json');
    if (fs.existsSync(pkgPath)) {
      const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
      pkgType = pkg.type === 'module' ? 'esm' : 'cjs';
      const main = typeof pkg.main === 'string' && pkg.main.length > 0 ? pkg.main : 'index.js';
      entryFile = path.resolve(resolved, main);
    } else {
      entryFile = path.resolve(resolved, 'index.js');
    }
  } else {
    const ext = path.extname(resolved).toLowerCase();
    if (ext === '.mjs') moduleType = 'esm';
    else if (ext === '.cjs') moduleType = 'cjs';
    else if (ext === '.ts' || ext === '.tsx' || ext === '.mts') {
      throw new Error('TypeScript entry requires bun runtime or transpile step; refusing to execute under node');
    }
    // .js：由最近 package.json 的 type 决定（spec §4.3：不能只看扩展名）
    if (moduleType === 'cjs' && pkgType === null) {
      moduleType = nearestPackageType(resolved);
    }
  }
  if (formatHint === 'esm') moduleType = 'esm';
  else if (formatHint === 'cjs') moduleType = 'cjs';
  if (!fs.existsSync(entryFile)) {
    throw new Error('entry file not found: ' + entryFile);
  }
  if (moduleType === 'esm') {
    return await import('file://' + entryFile.split(path.sep).join('/'));
  }
  return require(entryFile);
}

function extractTools(mod) {
  if (!isNonNullObject(mod)) return {};
  const holder = isNonNullObject(mod.tools) ? mod.tools
    : (isNonNullObject(mod.default) && isNonNullObject(mod.default.tools)) ? mod.default.tools
    : {};
  const result = {};
  for (const name of Object.keys(holder)) {
    const tool = holder[name];
    if (!isNonNullObject(tool) || typeof tool.execute !== 'function') continue;
    result[name] = {
      description: typeof tool.description === 'string' ? tool.description : '',
      parametersSchema: typeof tool.parametersSchema === 'string' ? tool.parametersSchema : null,
      execute: tool.execute
    };
  }
  return result;
}

function extractSetup(mod) {
  if (!isNonNullObject(mod)) return null;
  if (typeof mod.setup === 'function') return mod.setup;
  if (isNonNullObject(mod.default) && typeof mod.default.setup === 'function') return mod.default.setup;
  return null;
}

// ── 消息处理 ────────────────────────────────────────────────────────────
async function handleMessage(envelope) {
  if (envelope.protocol !== PROTOCOL) throw new Error('protocol mismatch');
  const type = envelope.type;
  const requestId = envelope.requestId !== undefined ? envelope.requestId : null;

  if (type === 'hello') {
    send('ready', requestId, {
      runtime: process.release && process.release.name ? process.release.name : 'node',
      runtimeVersion: process.version,
      protocolVersion: VERSION
    });
    return;
  }

  if (type === 'load') {
    try {
      const ecosystem = envelope.payload ? envelope.payload.ecosystem : '';
      if (ecosystem === 'opencode-v1' || ecosystem === 'opencode-v2') {
        await loadOpenCode(envelope, requestId);
      } else {
        const entryPath = envelope.payload ? envelope.payload.entryPath : '';
        const formatHint = envelope.payload ? envelope.payload.format : '';
        pluginModule = await loadModule(entryPath, formatHint);
        const tools = extractTools(pluginModule);
        const setup = extractSetup(pluginModule);
        if (setup) {
          await setup({ worker: { log: function (m) { console.error('[plugin] ' + m); } } });
        }
        const toolList = Object.keys(tools).map(function (name) {
          return {
            name: name,
            description: tools[name].description,
            parametersSchema: tools[name].parametersSchema
          };
        });
        send('loaded', requestId, { tools: toolList, setupOk: true });
      }
      loaded = true;
    } catch (err) {
      send('loaded', requestId, { tools: [], setupOk: false, error: String(err && err.message ? err.message : err) });
    }
    return;
  }

  if (type === 'tool.execute') {
    if (!loaded) {
      send('tool.result', requestId, { ok: false, error: 'plugin not loaded' });
      return;
    }
    const toolName = envelope.payload ? envelope.payload.toolName : '';
    const argsJson = envelope.payload ? envelope.payload.argsJson : '{}';
    let args = {};
    try { args = argsJson ? JSON.parse(argsJson) : {}; } catch (e) {
      send('tool.result', requestId, { ok: false, error: 'args json invalid: ' + e.message });
      return;
    }
    if (pendingCancels.has(requestId)) {
      pendingCancels.delete(requestId);
      send('tool.result', requestId, { ok: false, error: 'cancelled before dispatch' });
      return;
    }
    // OpenCode 注册表优先（ctx.tool.transform 注册的工具）
    const ocTool = (typeof ocState !== 'undefined' && ocState && ocState.tools) ? ocState.tools[toolName] : null;
    if (ocTool) {
      try {
        const output = await executeOcTool(toolName, args, requestId);
        send('tool.result', requestId, { ok: true, output: output });
      } catch (err) {
        send('tool.result', requestId, { ok: false, error: String(err && err.message ? err.message : err) });
      }
      return;
    }
    const tools = extractTools(pluginModule);
    const tool = tools[toolName];
    if (!tool) {
      send('tool.result', requestId, { ok: false, error: 'unknown tool: ' + toolName });
      return;
    }
    Promise.resolve()
      .then(function () { return tool.execute(args); })
      .then(function (result) {
        if (pendingCancels.has(requestId)) {
          pendingCancels.delete(requestId);
          send('tool.result', requestId, { ok: false, error: 'cancelled' });
          return;
        }
        let output = '';
        if (typeof result === 'string') output = result;
        else if (isNonNullObject(result) && typeof result.output === 'string') output = result.output;
        else output = JSON.stringify(result === undefined ? null : result);
        send('tool.result', requestId, { ok: true, output: output });
      })
      .catch(function (err) {
        send('tool.result', requestId, { ok: false, error: String(err && err.message ? err.message : err) });
      });
    return;
  }

  if (type === 'event.emit') {
    // OpenCode 兼容层钩子 + ctx.event.subscribe 订阅方分发
    const eventName = envelope.payload ? envelope.payload.eventName : '';
    const payloadJson = envelope.payload ? envelope.payload.payloadJson : '{}';
    let payload = {};
    try { payload = payloadJson ? JSON.parse(payloadJson) : {}; } catch (e) { payload = {}; }
    let delivered = 0;
    if (typeof ocState !== 'undefined' && ocState) {
      try { delivered = await dispatchOcEvent(eventName, payload); } catch (err) {
        console.error('[oc-dispatch] ' + eventName + ' failed: ' + err);
      }
    }
    send('tool.result', requestId, {
      ok: true,
      output: JSON.stringify({ delivered: delivered > 0, count: delivered })
    });
    return;
  }

  if (type === 'cancel') {
    const target = envelope.payload ? envelope.payload.targetRequestId : null;
    if (target !== null && target !== undefined) pendingCancels.add(String(target));
    return;
  }

  if (type === 'ping') {
    send('pong', requestId, {});
    return;
  }

  if (type === 'shutdown') {
    if (typeof ocState !== 'undefined') {
      try { await ocShutdown(); } catch (err) {
        console.error('[oc-shutdown] failed: ' + err);
      }
    }
    send('pong', requestId, {});
    process.exit(0);
  }
}

// ── STDIN 行读取 ────────────────────────────────────────────────────────
let buffer = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', function (chunk) {
  buffer += chunk;
  let idx;
  while ((idx = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, idx).trim();
    buffer = buffer.slice(idx + 1);
    if (line.length === 0) continue;
    let envelope = null;
    try { envelope = JSON.parse(line); } catch (e) {
      console.error('[bootstrap] bad json line: ' + line.slice(0, 120));
      continue;
    }
    handleMessage(envelope).catch(function (err) {
      console.error('[bootstrap] handler error: ' + err);
    });
  }
});
process.stdin.on('end', function () {
  // 宿主关闭 stdin = 停机指令（防孤儿进程）
  process.exit(0);
});
process.stdin.on('error', function (err) {
  console.error('[bootstrap] stdin error: ' + err);
});

process.on('uncaughtException', function (err) {
  fatal('uncaughtException: ' + (err && err.stack ? err.stack : err));
});
process.on('unhandledRejection', function (reason) {
  console.error('[bootstrap] unhandledRejection: ' + reason);
});

console.error('[bootstrap] worker up (pid ' + process.pid + ')');
""".trimIndent()

    /** 完整脚本 = 通用契约 + OpenCode 兼容层（函数声明提升，拼接安全）。 */
    val SCRIPT: String = GENERIC + "\n\n" + OpenCodeWorkerScript.SHIM

    /** 落盘引导脚本（tmp + rename 原子写纪律）。 */
    fun writeTo(dir: File): File {
        val target = File(dir, FILE_NAME)
        if (target.isFile) {
            // 内容升级：旧脚本缺 OpenCode 层时重写（幂等 + 版本对齐）
            val current = target.readText()
            if (current == SCRIPT) return target
        }
        val tmp = File(dir, FILE_NAME + ".tmp")
        tmp.writeText(SCRIPT, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(SCRIPT, Charsets.UTF_8)
        }
        return target
    }
}
