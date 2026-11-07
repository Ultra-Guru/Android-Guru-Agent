'use strict';
/*
 * # dsh-run-tool.js —— DSH 工具冷执行器（P0 模式；P2 持久 Worker 的语义基线）
 *
 * 用法：node dsh-run-tool.js <pkgDir> <toolName>   （参数 JSON 走 stdin）
 *
 * 契约（与 Kotlin 侧 DshBridgeTool 的协议）：
 * - stdout 恰好一个 JSON envelope；stderr = 调试旁路；
 * - envelope: { ok, protocol, kind:"tool-result", tool, durationMs,
 *               result|error{code,message} }
 * - **每次调用完整重跑 loadPlugin + apply(ctx)** —— 闭包/状态不跨调用存活
 *   （冷启动 = 无状态插件模式，如实标注；有状态插件需要 P2 持久 Worker）；
 * - 参数只从 stdin 读（红线：结构化数据永不拼命令串）；
 * - 结果经 safeStringify（函数/循环/深度封顶）—— 只留数据。
 */
const shim = require('./dsh-shim.js');

const SUPPORTED = ['tools', 'logger', 'config', 'effect'];
const APPLY_TIMEOUT_MS = 15_000;
const EXECUTE_TIMEOUT_MS = Number(process.env.DSH_TOOL_TIMEOUT_MS || 120_000);

function readStdin() {
  return new Promise((resolve, reject) => {
    const chunks = [];
    process.stdin.on('data', (d) => {
      chunks.push(d);
      const total = chunks.reduce((n, c) => n + c.length, 0);
      if (total > 1_000_000) reject(new Error('stdin payload exceeds 1MB'));
    });
    process.stdin.on('end', () => {
      process.stdin.destroy(); // 读毕即拆 —— 不留任何挂住事件循环的流
      resolve(Buffer.concat(chunks).toString('utf8'));
    });
    process.stdin.on('error', reject);
  });
}

function withTimeout(promise, ms, label) {
  // 一次性进程契约：竞速结束后必须 clearTimeout —— 否则输掉的定时器把
  // 事件循环钉住，envelope 已输出但进程假活（ stdout 悬挂，宿主超时误判）。
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label} timeout after ${ms}ms`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

async function main() {
  const started = Date.now();
  const pkgDir = process.argv[2];
  const toolName = process.argv[3];
  if (!pkgDir || !toolName) {
    envelope({ ok: false, tool: toolName || null, error: { code: 'usage', message: 'usage: node dsh-run-tool.js <pkgDir> <toolName>' } });
    return;
  }

  // ── 参数：stdin JSON（EOF 后解析；空 = {}）──
  let args;
  try {
    const raw = (await readStdin()).trim();
    args = raw ? JSON.parse(raw) : {};
  } catch (e) {
    envelope({ ok: false, tool: toolName, error: { code: 'bad-arguments', message: `stdin JSON 解析失败: ${e.message}` } });
    return;
  }
  if (!args || typeof args !== 'object' || Array.isArray(args)) {
    envelope({ ok: false, tool: toolName, error: { code: 'bad-arguments', message: 'arguments must be a JSON object' } });
    return;
  }

  // ── 重载插件 + 重跑 apply（冷启动语义：每次调用全新实例）──
  let loaded;
  try {
    loaded = await withTimeout(shim.loadPlugin(pkgDir), APPLY_TIMEOUT_MS, 'plugin load');
  } catch (e) {
    envelope({ ok: false, tool: toolName, error: { code: 'load-failed', message: String(e && e.message) } });
    return;
  }
  if (loaded.unsupportedClass) {
    envelope({ ok: false, tool: toolName, error: { code: 'unsupported-class-form', message: '类形态插件宿主不支持' } });
    return;
  }
  const unsupportedInject = loaded.inject.filter((s) => !SUPPORTED.includes(s));
  if (unsupportedInject.length > 0) {
    envelope({
      ok: false, tool: toolName,
      error: { code: 'service-unavailable', message: `service-unavailable: ${unsupportedInject.join(',')} (inject 依赖未支持服务)` },
    });
    return;
  }

  const capturedTools = [];
  const touched = new Set();
  const { ctx, state } = shim.createShimContext({
    supported: SUPPORTED,
    touched,
    onToolRegistered: (def) => capturedTools.push(def),
  });
  try {
    await withTimeout(Promise.resolve(loaded.apply(ctx)), APPLY_TIMEOUT_MS, 'apply');
  } catch (e) {
    const svc = e instanceof shim.ServiceUnavailableError ? e.service : null;
    envelope({
      ok: false, tool: toolName,
      error: { code: svc ? 'service-unavailable' : 'apply-failed', message: String(e && e.message), service: svc },
    });
    return;
  }

  const tool = capturedTools.find((t) => t.name === toolName);
  if (!tool) {
    const names = capturedTools.map((t) => t.name).join(', ') || '(none)';
    envelope({ ok: false, tool: toolName, error: { code: 'tool-not-found', message: `插件未注册工具 '${toolName}'（已注册: ${names}）` } });
    return;
  }

  // ── 真实执行（execContext = 最小对象 —— 官方 execute(args, execContext) 第二参）──
  try {
    const result = await withTimeout(
      Promise.resolve(tool.execute(args, {})),
      EXECUTE_TIMEOUT_MS,
      'execute'
    );
    envelope({
      ok: true,
      kind: 'tool-result',
      protocol: 1,
      tool: toolName,
      durationMs: Date.now() - started,
      result: JSON.parse(shim.safeStringify(result)),
    });
  } catch (e) {
    envelope({
      ok: false,
      kind: 'tool-result',
      protocol: 1,
      tool: toolName,
      durationMs: Date.now() - started,
      error: { code: 'execute-failed', message: String(e && e.message) },
    });
  } finally {
    shim.disposeContext(state);
  }
}

function envelope(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

main().catch((e) => {
  envelope({ ok: false, error: { code: 'internal', message: String(e && e.stack || e) } });
});
