package com.apex.agent.core.tools.pluginhost.opencode

import java.io.File

/**
 * # OpenCodeWorkerScript —— Worker 侧 OpenCode 兼容层（追加到通用引导脚本）
 *
 * 官方 V2 插件期望的是「OpenCode 服务器客户端」形状的 ctx（spec §4.6
 * Context Bridge）。本脚本在 Worker 内构造宿主上下文：
 *
 * - **支持**（实测锁定）：`ctx.app.version` / `ctx.location` / `ctx.options`
 *   / `ctx.storage`（插件目录内 JSON 文件持久化，随卸载清理）/
 *   `ctx.tool.transform`（工具注册捕获）/ `ctx.tool.hook` + `ctx.session.hook`
 *   （钩子捕获，宿主事件下发时调用）/ `ctx.event.subscribe`；
 * - **如实不支持**（调用即抛描述性错误，不静默假装成功）：`ctx.$` shell、
 *   `ctx.generate`、`ctx.model`、`ctx.provider` 等宿主服务（spec §4.6：
 *   每项能力必须由宿主提供实现或明确报告不支持）；
 * - **V1 兼容**：具名异步导出（`export const XPlugin: Plugin = async (...) => ({...})`）
 *   被识别并以 `{directory, project, client}` 调用，返回的 hook map
 *   进入同一套分发管线（V1 的 `@opencode-ai/plugin` 是纯类型包，运行时
 *   零依赖 —— 无需 shim）。
 *
 * JS 侧刻意不用模板字面量（Kotlin 原始字符串插值冲突），全部字符串拼接。
 */
object OpenCodeWorkerScript {

    const val ECOSYSTEM_V2 = "opencode-v2"
    const val ECOSYSTEM_V1 = "opencode-v1"

    /** 追加到通用引导脚本尾部的 OpenCode 支持（函数声明提升，顺序无关）。 */
    val SHIM: String = """
// ══ OpenCode 兼容层（apex-plugin-host Phase 4）══════════════════════════
let ocState = null; // { tools: {name→def}, hooks: {name→[cb]}, events: {name→[cb]}, cleanup }

function ocStorage(storageFile) {
  const fs = require('fs');
  const path = require('path');
  let data = {};
  try { data = JSON.parse(fs.readFileSync(storageFile, 'utf8')); } catch (e) { data = {}; }
  let dirty = false;
  return {
    get: async function (key) { return key === undefined ? data : data[key]; },
    set: async function (key, value) { data[key] = value; dirty = true; persist(); },
    delete: async function (key) { delete data[key]; dirty = true; persist(); },
    scan: async function () {
      const out = {};
      for (const k of Object.keys(data)) out[k] = data[k];
      return out;
    }
  };
  function persist() {
    try {
      fs.mkdirSync(path.dirname(storageFile), { recursive: true });
      fs.writeFileSync(storageFile, JSON.stringify(data));
    } catch (err) {
      console.error('[oc-storage] persist failed: ' + err);
    }
  }
}

function ocNotSupported(what) {
  return function () {
    throw new Error('ctx.' + what + ' is not yet supported by the Android plugin host (see compatibility report)');
  };
}

function buildOpenCodeContext(spec) {
  const storage = ocStorage(spec.storageFile);
  const hooks = {};
  const events = {};
  const ctx = {
    app: { version: 'apex-plugin-host/2.0 (opencode-compat)' },
    location: {
      directory: spec.directory,
      project: { id: spec.projectId, directory: spec.directory, canonical: spec.directory }
    },
    options: spec.options || {},
    storage: storage,
    tool: {
      transform: async function (callback) {
        const editor = {
          add: function (tool) { registerOcTool(tool); },
          namespace: function () { },
          update: function () { },
          remove: function () { },
          list: function () { return []; },
          get: function () { return undefined; }
        };
        callback(editor);
        return { dispose: function () { } };
      },
      hook: async function (name, cb) {
        (hooks['tool.' + name] = hooks['tool.' + name] || []).push(cb);
        return { dispose: function () { } };
      },
      list: async function () { return []; },
      reload: async function () { }
    },
    session: {
      hook: async function (name, cb) {
        (hooks['session.' + name] = hooks['session.' + name] || []).push(cb);
        return { dispose: function () { } };
      },
      list: async function () { return []; },
      prompt: async function () { throw new Error('ctx.session.prompt not supported on Android host'); },
      context: async function () { return { messages: [] }; },
      update: async function () { }
    },
    permission: {
      hook: async function (name, cb) {
        (hooks['permission.' + name] = hooks['permission.' + name] || []).push(cb);
        return { dispose: function () { } };
      },
      assert: async function () { }
    },
    shell: {
      hook: async function (name, cb) {
        (hooks['shell.' + name] = hooks['shell.' + name] || []).push(cb);
        return { dispose: function () { } };
      }
    },
    event: {
      subscribe: async function (name, cb) {
        (events[name] = events[name] || []).push(cb);
        return { dispose: function () { } };
      }
    },
    $: ocNotSupported('$ (shell)'),
    generate: ocNotSupported('generate'),
    model: ocNotSupported('model'),
    provider: ocNotSupported('provider'),
    command: ocNotSupported('command'),
    integration: ocNotSupported('integration'),
    credential: ocNotSupported('credential'),
    mcp: ocNotSupported('mcp'),
    plugin: ocNotSupported('plugin'),
    worktree: ocNotSupported('worktree'),
    vcs: ocNotSupported('vcs'),
    websearch: ocNotSupported('websearch')
  };
  return { ctx: ctx, hooks: hooks, events: events, storage: storage };
}

function registerOcTool(tool) {
  if (!tool || typeof tool.name !== 'string' || typeof tool.execute !== 'function') return;
  ocState.tools[tool.name] = {
    name: tool.name,
    description: typeof tool.description === 'string' ? tool.description : '',
    parametersSchema: tool.input ? JSON.stringify(tool.input) : null,
    execute: tool.execute
  };
}

async function loadOpenCode(env, requestId) {
  const payload = env.payload || {};
  const ecosystem = payload.ecosystem === 'opencode-v1' ? 'v1' : 'v2';
  const spec = {
    directory: String(payload.pluginDir || '.'),
    projectId: String(payload.pluginId || 'opencode-plugin'),
    pluginId: String(payload.pluginId || 'opencode-plugin'),
    options: payload.options || {},
    storageFile: String(payload.storageFile || './.apex-plugin-storage.json')
  };
  const mod = await loadModule(payload.entryPath, payload.format);
  ocState = { tools: {}, hooks: {}, events: {}, cleanup: null };

  if (ecosystem === 'v2') {
    const def = (mod && mod.default) ? mod.default : mod;
    if (!isNonNullObject(def) || typeof def.setup !== 'function') {
      throw new Error('V2 plugin must default-export Plugin.define({ id, setup }) — no setup function found');
    }
    const built = buildOpenCodeContext(spec);
    ocState.hooks = built.hooks;
    ocState.events = built.events;
    const maybeCleanup = await def.setup(built.ctx);
    if (typeof maybeCleanup === 'function') ocState.cleanup = maybeCleanup;
  } else {
    // V1：具名异步导出（第一个 async function 导出），入参 {directory, project, client}
    let v1Fn = null;
    for (const key of Object.keys(mod || {})) {
      const val = mod[key];
      if (typeof val === 'function' && key !== 'default') { v1Fn = val; break; }
    }
    if (!v1Fn) {
      throw new Error('V1 plugin must export an async function returning a hook map');
    }
    const built = buildOpenCodeContext(spec);
    ocState.hooks = built.hooks;
    ocState.events = built.events;
    const hookMap = await v1Fn({ directory: spec.directory, project: { id: spec.projectId, directory: spec.directory }, client: built.ctx });
    if (isNonNullObject(hookMap)) {
      for (const name of Object.keys(hookMap)) {
        const cb = hookMap[name];
        if (typeof cb !== 'function') continue;
        (ocState.hooks[name] = ocState.hooks[name] || []).push(cb);
      }
    }
  }

  const toolList = Object.keys(ocState.tools).map(function (name) {
    return {
      name: name,
      description: ocState.tools[name].description,
      parametersSchema: ocState.tools[name].parametersSchema
    };
  });
  const hookList = Object.keys(ocState.hooks);
  const eventList = Object.keys(ocState.events);
  send('loaded', requestId, { tools: toolList, setupOk: true, hooks: hookList, events: eventList });
}

/** 工具执行（OpenCode 注册表优先于通用契约）。 */
async function executeOcTool(toolName, args, requestId) {
  const def = ocState && ocState.tools ? ocState.tools[toolName] : null;
  if (!def) return null; // 通用契约路径兜底
  const context = {
    sessionID: 'apex-session',
    messageID: 'apex-message',
    id: toolName,
    agent: 'apex',
    progress: async function () { }
  };
  const result = await def.execute(args, context);
  if (typeof result === 'string') return result;
  if (isNonNullObject(result) && typeof result.content === 'string') return result.content;
  if (result === undefined || result === null) return 'null';
  return JSON.stringify(result);
}

/** 宿主事件下发到 OpenCode 钩子（映射后的 worker 侧规范名）。 */
async function dispatchOcEvent(hookName, payload) {
  if (!ocState || !ocState.hooks) return 0;
  const cbs = ocState.hooks[hookName] || [];
  for (const cb of cbs) {
    try {
      // OpenCode 钩子形状：(event) 可变更 —— 传可写副本，错误隔离
      await cb(JSON.parse(JSON.stringify(payload)));
    } catch (err) {
      console.error('[oc-hook] ' + hookName + ' failed: ' + err);
    }
  }
  // ctx.event.subscribe 订阅方（事件名直配）
  const subs = (ocState.events && ocState.events[hookName]) || [];
  for (const cb of subs) {
    try { await cb(JSON.parse(JSON.stringify(payload))); } catch (err) {
      console.error('[oc-event] ' + hookName + ' failed: ' + err);
    }
  }
  return cbs.length + subs.length;
}

async function ocShutdown() {
  if (ocState && typeof ocState.cleanup === 'function') {
    try { await ocState.cleanup(); } catch (err) {
      console.error('[oc-cleanup] failed: ' + err);
    }
  }
}
""".trimIndent()

    /**
     * # OpenCodeSdkShim —— `@opencode/plugin` 最小 SDK 垫片（按需落盘）
     *
     * 官方 V2 SDK 的 `Plugin.define` 在**客户端**只是把定义原样返回（服务器
     * 才消费它）—— 垫片等价实现这一行为。插件目录已装真实 SDK 时**不落盘**
     * （真实包优先，垫片只在缺失时兜底），垫片带 `-apex-shim` 版本号防混淆。
     */
    fun vendorSdkShimIfMissing(pkgDir: File): Boolean {
        val sdkDir = File(pkgDir, "node_modules/@opencode/plugin")
        if (File(sdkDir, "index.js").isFile || File(sdkDir, "index.mjs").isFile) return false
        sdkDir.mkdirs()
        File(sdkDir, "package.json").writeText(
            """{"name":"@opencode/plugin","version":"0.0.0-apex-shim","type":"module","main":"index.js"}"""
        )
        File(sdkDir, "index.js").writeText(
            """
            export const Plugin = { define: function (definition) { return definition; } };
            export default Plugin;
            """.trimIndent()
        )
        return true
    }
}
