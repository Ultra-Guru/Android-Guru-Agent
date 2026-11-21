'use strict';
/*
 * # dsh-shim.js —— 最小 Cordis 兼容层（DSH Native Plugin Host P0/P1）
 *
 * 职责边界（红线）：
 * - 只实现「插件加载 + 注册面捕获 + 受控服务」，不实现完整 Cordis 内核，
 *   不声称完整 Service 生命周期兼容（红线 7）；
 * - 不支持的服务：记录触碰 + 抛结构化错误 —— 绝不静默 mock（红线 11）；
 * - 本文件被 dsh-introspect.js / dsh-run-tool.js 共享；宿主（Kotlin）把
 *   本目录部署到沙箱 <extRoot>/.dsh-host/scripts/ 后以 node 执行。
 *
 * 插件模块形态（官方 docs/cookbook/extension-cookbook）：
 * - 函数模块：export const apply = (ctx) => {...}（CJS/ESM 皆可）；
 * - 对象模块：export default { name, inject, apply }；
 * - 类模块：Cordis Service 构建 —— 本 shim 明确不支持，如实上报。
 */

/** 结构化服务缺失错误（Kotlin 侧按 message 里的 service 名归因）。 */
class ServiceUnavailableError extends Error {
  constructor(service, action) {
    super(`service-unavailable: ${service} (host shim does not provide it; touched via ${action})`);
    this.name = 'ServiceUnavailableError';
    this.service = service;
  }
}

/** 受控 unsupported 服务访问器：记录触碰动作，任何真实使用都抛结构化错误。 */
function makeUnavailableProxy(service, touched) {
  return new Proxy(function () {}, {
    get(_t, prop) {
      if (prop === Symbol.toPrimitive || prop === 'toString') return () => `<unsupported:${service}>`;
      touched.add(service);
      return makeUnavailableProxy(service, touched);
    },
    apply() {
      touched.add(service);
      throw new ServiceUnavailableError(service, 'call');
    },
    construct() {
      touched.add(service);
      throw new ServiceUnavailableError(service, 'construct');
    },
  });
}

/**
 * 构造 shim ctx。
 *
 * @param {object} options
 *   supported: string[]                宿主本进程支持的服务名（tools/logger/config/effect）
 *   config: object                     插件独立配置空间（config 服务的读写域）
 *   onToolRegistered: (def) => void    工具注册回调（捕获面）
 *   pluginName: string                 诊断日志前缀
 * @returns {{ctx: object, state: {disposers: Function[], touched: Set<string>}}}
 *   state 由调用方持有 —— dispose 路径（disposeContext）与触碰上报都要它。
 */
function createShimContext(options) {
  const opts = options || {};
  const supported = new Set(opts.supported || ['tools', 'logger', 'config', 'effect']);
  const state = { disposers: [], touched: opts.touched || new Set() };
  const touched = state.touched;

  function ensureSupported(service) {
    if (!supported.has(service)) {
      touched.add(service);
      throw new ServiceUnavailableError(service, 'direct-access');
    }
  }

  const ctx = {
    /** 工具注册面：defineTool 产物与裸对象同形捕获（name/parameters/output/execute）。 */
    tools: {
      register(def) {
        if (!def || typeof def !== 'object' || typeof def.execute !== 'function') {
          throw new Error('ctx.tools.register: definition must be an object with execute()');
        }
        if (!def.name || typeof def.name !== 'string') {
          throw new Error('ctx.tools.register: definition.name must be a non-empty string');
        }
        if (opts.onToolRegistered) opts.onToolRegistered(def);
      },
    },
    logger: {
      debug() {}, info() {}, warn() {}, error() {},
    },
    /** 插件独立配置空间（命名空间隔离由宿主保证 —— 同插件不同实例共享本对象）。 */
    config: opts.config || {},
    /** 可逆副作用：立即执行收集器，返回 disposer；dispose 在卸载路径统一调用。 */
    effect(collector) {
      ensureSupported('effect');
      if (typeof collector !== 'function') return;
      const disposer = collector();
      if (typeof disposer === 'function') state.disposers.push(disposer);
    },
  };

  // 任意其它属性访问（agents/sessions/storage/llm/skills/slots/…）→ 受控 unsupported 代理
  return { ctx: new Proxy(ctx, {
    get(target, prop) {
      if (prop in target) return target[prop];
      if (typeof prop === 'symbol') return undefined;
      touched.add(String(prop));
      return makeUnavailableProxy(String(prop), touched);
    },
    has(target, prop) {
      // `if (ctx.storage)` 之类的存在性检查：仅报告，不抛（诚实记录触碰）
      if (!(prop in target)) touched.add(String(prop));
      return prop in target;
    },
  }), state };
}

/** 调用全部 disposer（unload/dispose 路径 —— D08 验收点）。 */
function disposeContext(state) {
  const errors = [];
  (state.disposers || []).forEach((d) => {
    try { d(); } catch (e) { errors.push(String(e && e.message)); }
  });
  state.disposers = [];
  return errors;
}

/**
 * 插件模块加载（CJS/ESM 双形态；类形态如实上报不支持）。
 *
 * @returns {Promise<{form:string, name:string, inject:string[], apply:Function,
 *                     unsupportedClass:boolean, entry:string}>}
 */
async function loadPlugin(pkgDir) {
  const fs = require('fs');
  const path = require('path');
  const abs = (p) => path.resolve(pkgDir, p);

  const pkgJsonPath = abs('package.json');
  const pkg = fs.existsSync(pkgJsonPath)
    ? JSON.parse(fs.readFileSync(pkgJsonPath, 'utf8'))
    : {};

  // 入口解析：main → module → exports['.'].import/default → index.js（诚实逐级回落）
  let entryRel = pkg.main || pkg.module || null;
  if (!entryRel && pkg.exports && pkg.exports['.']) {
    const e = pkg.exports['.'];
    entryRel = (e.import && (e.import.default || e.import)) || (e.default && (e.default.default || e.default)) || null;
  }
  if (!entryRel) entryRel = 'index.js';
  let entry = abs(entryRel);
  if (!fs.existsSync(entry) && fs.existsSync(entry + '.js')) entry = entry + '.js';
  if (!fs.existsSync(entry) && fs.existsSync(entry + '.cjs')) entry = entry + '.cjs';
  if (!fs.existsSync(entry) && fs.existsSync(entry + '.mjs')) entry = entry + '.mjs';
  if (!fs.existsSync(entry)) {
    throw new Error(`entry-not-found: ${entryRel} (package.json main/module/exports 均未命中)`);
  }

  const isEsm =
    pkg.type === 'module' || /\.mjs$/.test(entry) ||
    (!/\.cjs$/.test(entry) && !fs.existsSync(abs(pkg.main || 'index.js')));

  let mod;
  if (!isEsm) {
    const req = require;
    mod = req(entry); // CJS：require 从 entry 自身目录解析 node_modules
  } else {
    const { pathToFileURL } = require('url');
    mod = await import(pathToFileURL(entry).href);
  }

  // 形态归一（函数 / 对象 / 类）。name 解析按形态分治：
  // - 函数形态：mod.name === fn.name 是语言赠品（如 'apply'），不是插件名 ——
  //   以 npm 包名为准，函数名仅作末位回落；
  // - 对象/命名导出形态：显式 name 导出优先（Cordis 约定 `export const name`）。
  const raw = mod && (mod.default !== undefined ? mod.default : mod);
  let apply = null;
  const exportedName = typeof (mod && mod.name) === 'string' && mod.name ? mod.name : null;
  let name = pkg.name || 'unknown';
  let inject = (mod && mod.inject) || (raw && raw.inject) || [];

  if (typeof raw === 'function') {
    if (/^class\s/.test(Function.prototype.toString.call(raw))) {
      return { form: 'class', name: String(name), inject: normInject(inject), apply: null, unsupportedClass: true, entry: entryRel };
    }
    apply = raw; // 函数模块（CJS module.exports = fn / ESM export default fn）
    if (!inject.length && mod && mod.inject) inject = mod.inject;
    // 函数形态：mod.name === fn.name（语言赠品）—— 只有与 fn.name 相异的
    // name 导出（ESM `export const name`）才算显式插件名
    const fnExplicit = exportedName && exportedName !== raw.name ? exportedName : null;
    return { form: isEsm ? 'esm-function' : 'cjs-function', name: String(fnExplicit || pkg.name || raw.name || 'unknown'), inject: normInject(inject), apply, unsupportedClass: false, entry: entryRel };
  }
  if (raw && typeof raw === 'object' && typeof raw.apply === 'function') {
    if (raw.apply.constructor && /^class\s/.test(Function.prototype.toString.call(raw.apply.constructor)) && !raw.apply.prototype) {
      // 极罕见：apply 本身是未绑定类 —— 按不支持处理
      return { form: 'class', name: String(name), inject: normInject(inject), apply: null, unsupportedClass: true, entry: entryRel };
    }
    name = exportedName || raw.name || pkg.name || 'unknown';
    inject = raw.inject || inject;
    return { form: isEsm ? 'esm-object' : 'cjs-object', name: String(name), inject: normInject(inject), apply: raw.apply, unsupportedClass: false, entry: entryRel };
  }
  throw new Error(`unsupported-module-shape: 既非函数模块也非对象模块（typeof=${typeof raw}）`);

  function normInject(v) {
    if (Array.isArray(v)) return v.map(String);
    if (v && typeof v === 'object') return Object.keys(v).map(String); // Cordis 允许 {service: rename} 形态
    return [];
  }
}

/** 安全序列化（结果面：函数剔除 / 循环断开 / 深度封顶 —— 结果只留数据）。 */
function safeStringify(value, maxDepth) {
  const depth = maxDepth || 12;
  const seen = new WeakSet();
  function walk(v, d) {
    if (v === null || typeof v !== 'object') {
      if (typeof v === 'function' || typeof v === 'symbol' || typeof v === 'undefined') return null;
      if (typeof v === 'number' && !Number.isFinite(v)) return String(v);
      if (typeof v === 'bigint') return v.toString();
      return v;
    }
    if (seen.has(v)) return '[Circular]';
    if (d <= 0) return '[DepthLimit]';
    seen.add(v);
    if (Array.isArray(v)) return v.map((x) => walk(x, d - 1));
    const out = {};
    for (const k of Object.keys(v)) out[k] = walk(v[k], d - 1);
    return out;
  }
  return JSON.stringify(walk(value, depth));
}

/** DSH parameters 自有 Schema → JSON Schema（白名单转换；转换不了 = 宽容对象 + noted）。 */
function parametersToJsonSchema(parameters) {
  if (!parameters || typeof parameters !== 'object') {
    return { schema: '{"type":"object","additionalProperties":true}', exact: false };
  }
  // 形态一：已是合法 JSON Schema（含 type/properties 顶层键）
  if (parameters.type === 'object' || parameters.properties) {
    return { schema: safeStringify(parameters), exact: true };
  }
  // 形态二：DSH 字段映射 {field: {type, required, description}}
  const props = {};
  const required = [];
  let exact = true;
  const TYPE_MAP = { string: 'string', number: 'number', boolean: 'boolean', object: 'object', array: 'array' };
  for (const [field, spec] of Object.entries(parameters)) {
    const s = spec && typeof spec === 'object' ? spec : { type: 'string' };
    const t = TYPE_MAP[s.type];
    if (!t) { exact = false; props[field] = {}; continue; }
    const p = { type: t };
    if (typeof s.description === 'string') p.description = s.description;
    if (Array.isArray(s.enum)) p.enum = s.enum.slice();
    props[field] = p;
    if (s.required === true) required.push(field);
  }
  const schema = { type: 'object', properties: props };
  if (required.length) schema.required = required;
  if (!exact) schema.additionalProperties = true;
  return { schema: JSON.stringify(schema), exact };
}

module.exports = {
  ServiceUnavailableError,
  createShimContext,
  loadPlugin,
  disposeContext,
  safeStringify,
  parametersToJsonSchema,
};
