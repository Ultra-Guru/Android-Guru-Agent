package com.apex.agent.core.tools.pluginhost.worker

/**
 * # Plugin Worker Bootstrap —— Worker 侧 JS 引导脚本（协议 v1）
 *
 * 宿主把它与插件同目录落盘后经 RuntimeProvider 启动（`node bootstrap.js`
 * 或 `bun bootstrap.js`）。职责：
 *
 * 1. **STDOUT 纯协议**：每行一个 JSON 信封（WorkerProtocol v1）；
 *    console.log 被重定向到 STDERR —— 插件日志永不污染 IPC 流；
 * 2. **通用插件模块契约**（apex-plugin-worker-v1，与生态无关 ——
 *    OpenCode/Claude 的 SDK 形状由各自 Loader 在 Phase 4/5 叠加）：
 *    - ESM：`export const tools = { 名字: { description, parametersSchema,
 *      execute(args) } }`，可选 `export async function setup(ctx)`；
 *    - CJS：`module.exports = { tools: {...}, setup: ... }`；
 * 3. 工具执行异常 → error 结果（进程不退出，错误隔离）；
 * 4. 致命错误（加载失败）→ fatal 消息 + 退出码 1。
 *
 * Kotlin 侧以原始字符串持有脚本源 —— JS 代码刻意**不用模板字面量**
 * （避免与 Kotlin 字符串插值冲突），全部字符串拼接。
 */
object PluginWorkerBootstrap {

    const val FILE_NAME = "apex-plugin-worker-bootstrap.js"

    val SCRIPT: String = """
'use strict';

// ── STDOUT 保护：console.* 全部改道 STDERR ─────────────────────────────
const rawLog = console.log.bind(console);
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
  try { send('fatal', null, { reason: String(reason) }); } catch (e) { // 尽力而为
  }
  process.exit(1);
}

// ── 插件模块状态 ────────────────────────────────────────────────────────
let pluginModule = null;
let loaded = false;
const pendingCancels = new Set();
let nextInternalId = 0;

function isNonNullObject(v) { return v !== null && typeof v === 'object'; }

async function loadModule(entryPath, formatHint) {
  const path = require('path');
  const fs = require('fs');
  const resolved = path.resolve(String(entryPath));
  if (!fs.existsSync(resolved)) {
    throw new Error('entry not found: ' + resolved);
  }
  const pkgPath = path.join(resolved, 'package.json');
  let moduleType = 'cjs';
  let entryFile = resolved;
  if (fs.existsSync(pkgPath) && fs.statSync(resolved).isDirectory()) {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
    moduleType = pkg.type === 'module' ? 'esm' : 'cjs';
    const main = typeof pkg.main === 'string' && pkg.main.length > 0 ? pkg.main : 'index.js';
    entryFile = path.resolve(resolved, main);
  } else {
    const ext = path.extname(resolved).toLowerCase();
    if (ext === '.mjs') moduleType = 'esm';
    else if (ext === '.cjs') moduleType = 'cjs';
    else if (ext === '.ts') {
      throw new Error('TypeScript entry requires bun runtime or transpile step; refusing to execute under node');
    }
    if (formatHint === 'esm') moduleType = 'esm';
    else if (formatHint === 'cjs') moduleType = 'cjs';
  }
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
  // ESM 命名导出 或 default 导出 双形状兼容
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
async function handleMessage(env) {
  if (env.protocol !== PROTOCOL) throw new Error('protocol mismatch');
  const type = env.type;
  const requestId = env.requestId !== undefined ? env.requestId : null;

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
      const entryPath = env.payload && env.payload.entryPath;
      const formatHint = env.payload && env.payload.format;
      pluginModule = await loadModule(entryPath, formatHint);
      const tools = extractTools(pluginModule);
      const setup = extractSetup(pluginModule);
      if (setup) {
        await setup({ worker: { log: function (m) { console.error('[plugin] ' + m); } } });
      }
      loaded = true;
      const toolList = Object.keys(tools).map(function (name) {
        return {
          name: name,
          description: tools[name].description,
          parametersSchema: tools[name].parametersSchema
        };
      });
      send('loaded', requestId, { tools: toolList, setupOk: true });
    } catch (err) {
      send('loaded', requestId, { tools: [], setupOk: false, error: String(err && err.message ? err.message : err) });
    }
    return;
  }

  if (type === 'tool.execute') {
    if (!loaded || pluginModule === null) {
      send('tool.result', requestId, { ok: false, error: 'plugin not loaded' });
      return;
    }
    const toolName = env.payload ? env.payload.toolName : '';
    const argsJson = env.payload ? env.payload.argsJson : '{}';
    const tools = extractTools(pluginModule);
    const tool = tools[toolName];
    if (!tool) {
      send('tool.result', requestId, { ok: false, error: 'unknown tool: ' + toolName });
      return;
    }
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
    const internalId = ++nextInternalId;
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
      })
      .finally(function () { void internalId; });
    return;
  }

  if (type === 'event.emit') {
    // Phase 4：插件事件钩子注册后在此分发。当前如实回执未注册。
    send('tool.result', requestId, { ok: true, output: '{"delivered":false,"reason":"no hook subscribers (phase 4)"}' });
    return;
  }

  if (type === 'cancel') {
    const target = env.payload ? env.payload.targetRequestId : null;
    if (target !== null && target !== undefined) pendingCancels.add(String(target));
    return;
  }

  if (type === 'ping') {
    send('pong', requestId, {});
    return;
  }

  if (type === 'shutdown') {
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

    /** 落盘引导脚本（tmp + rename 原子写纪律）。 */
    fun writeTo(dir: java.io.File): java.io.File {
        val target = java.io.File(dir, FILE_NAME)
        if (target.isFile) return target
        val tmp = java.io.File(dir, FILE_NAME + ".tmp")
        tmp.writeText(SCRIPT, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(SCRIPT, Charsets.UTF_8)
        }
        return target
    }
}
