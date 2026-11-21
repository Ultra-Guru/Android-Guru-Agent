'use strict';
/*
 * # dsh-analyze.js —— 静态能力分析（Stage 1 Declared；Acorn AST，不执行被检代码）
 *
 * 用法：node dsh-analyze.js <pkgDir> <manifestFile> <analyzerDir>
 *   - manifestFile: JSON 数组文件（相对 pkgDir 的 .js/.mjs/.cjs 清单，宿主预算后下发）
 *   - analyzerDir:  acorn 安装根（<dir>/node_modules/acorn，宿主 npm bootstrap 钉版安装）
 *
 * 契约：stdout 恰好一个 JSON envelope；不 require/不 import 被检文件 —— 只读
 * 源码文本 + AST（红线：静态分析绝不执行插件；eval/动态 require 只标记不跟进）。
 * acorn 不可用 = envelope ok:true + analyzer:null（诚实降级，Kotlin 侧按
 * declared-only 不可用处理，绝不冒充分析结论）。
 */
const fs = require('fs');
const path = require('path');

const SERVICE_PROPS = new Set([
  'tools', 'logger', 'config', 'storage', 'skills', 'llm', 'agents', 'sessions', 'slots', 'effect',
]);

function main() {
  const [, , pkgDir, manifestFile, analyzerDir] = process.argv;
  if (!pkgDir || !manifestFile) {
    envelope({ ok: false, error: { code: 'usage', message: 'usage: node dsh-analyze.js <pkgDir> <manifestFile> <analyzerDir>' } });
    return;
  }

  // acorn 可用性：显式路径 require（宿主 bootstrap 钉版安装的物理位置）
  let acorn = null;
  let acornVersion = null;
  try {
    // eslint-disable-next-line global-require
    acorn = require(path.join(analyzerDir || '', 'node_modules', 'acorn'));
    acornVersion = acorn.version || 'unknown';
  } catch (e) {
    envelope({
      ok: true, kind: 'static-analyze', protocol: 1, analyzer: null,
      note: 'AST 分析器未就绪（analyzerDir 无 acorn）—— 静态面降级为未知，不冒充分析结论',
      serviceTouches: {}, declaredInject: [], dynamic: [], analyzed: 0, skipped: 0, errors: [],
    });
    return;
  }

  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
  } catch (e) {
    envelope({ ok: false, error: { code: 'bad-manifest', message: String(e && e.message) } });
    return;
  }

  const serviceTouches = Object.create(null);
  const declaredInject = [];
  const dynamic = [];
  const errors = [];
  let analyzed = 0;

  for (const rel of manifest) {
    const abs = path.resolve(pkgDir, rel);
    let src;
    try {
      src = fs.readFileSync(abs, 'utf8');
    } catch (e) {
      errors.push({ file: rel, code: 'read-failed', message: String(e && e.message) });
      continue;
    }
    let ast;
    try {
      ast = acorn.parse(src, { ecmaVersion: 'latest', sourceType: 'module', allowReturnOutsideFunction: true, allowAwaitOutsideFunction: true });
    } catch (moduleErr) {
      try {
        ast = acorn.parse(src, { ecmaVersion: 'latest', sourceType: 'script', allowReturnOutsideFunction: true });
      } catch (scriptErr) {
        errors.push({ file: rel, code: 'parse-failed', message: String(scriptErr && scriptErr.message) });
        continue;
      }
    }
    analyzed += 1;
    walk(ast, { file: rel });
  }

  envelope({
    ok: true,
    kind: 'static-analyze',
    protocol: 1,
    analyzer: `acorn@${acornVersion}`,
    serviceTouches: normalizeTouches(serviceTouches),
    declaredInject,
    dynamic,
    analyzed,
    skipped: Math.max(0, manifest.length - analyzed - errors.length),
    errors,
  });

  // ── 轻量 AST 游走（不引入 acorn-walk 依赖 —— 手写递归，节点键白名单防环）──
  function walk(node, ctx, depth) {
    const d = depth || 0;
    if (!node || typeof node !== 'object' || d > 128) return;

    // ctx.<service>.<action>(...) 线索：MemberExpression(object=ctx 标识)
    if (node.type === 'MemberExpression' && !node.computed &&
        node.object && node.object.type === 'Identifier' && node.object.name === 'ctx' &&
        node.property && node.property.type === 'Identifier' && SERVICE_PROPS.has(node.property.name)) {
      const svc = node.property.name;
      (serviceTouches[svc] = serviceTouches[svc] || []).push(actionOf(node));
    }

    // inject 声明线索：Property 键 'inject' + 数组字面量（含 ESM export const inject）
    if (node.type === 'Property' && node.key && node.key.type === 'Identifier' && node.key.name === 'inject' &&
        node.value && node.value.type === 'ArrayExpression') {
      node.value.elements.forEach((el) => {
        if (el && el.type === 'Literal' && typeof el.value === 'string') declaredInject.push(el.value);
      });
    }
    if (node.type === 'ExportNamedDeclaration' && node.declaration &&
        node.declaration.type === 'VariableDeclaration') {
      node.declaration.declarations.forEach((decl) => {
        if (decl.id && decl.id.name === 'inject' && decl.init && decl.init.type === 'ArrayExpression') {
          decl.init.elements.forEach((el) => {
            if (el && el.type === 'Literal' && typeof el.value === 'string') declaredInject.push(el.value);
          });
        }
      });
    }

    // 动态面标记（只标记不跟进 —— 诚实 unknown）
    if (node.type === 'CallExpression' && node.callee && node.callee.type === 'Identifier' && node.callee.name === 'require' &&
        node.arguments.length === 1 && node.arguments[0].type !== 'Literal') {
      dynamic.push({ kind: 'dynamic-require', file: ctx.file, line: node.loc && node.loc.start.line });
    }
    if (node.type === 'CallExpression' && node.callee && node.callee.type === 'Identifier' &&
        (node.callee.name === 'eval' || node.callee.name === 'Function')) {
      dynamic.push({ kind: node.callee.name === 'eval' ? 'eval' : 'function-ctor', file: ctx.file, line: node.loc && node.loc.start.line });
    }

    for (const key of Object.keys(node)) {
      if (key === 'loc' || key === 'range' || key === 'start' || key === 'end') continue;
      const v = node[key];
      if (Array.isArray(v)) v.forEach((x) => walk(x, ctx, d + 1));
      else if (v && typeof v === 'object') walk(v, ctx, d + 1);
    }
  }

  function actionOf(memberNode) {
    // property 是否被调用（父节点信息不可得 —— 以 name/register 等动作词启发）
    const p = memberNode.property.name;
    return p;
  }

  function normalizeTouches(t) {
    const out = {};
    for (const k of Object.keys(t)) out[k] = Array.from(new Set(t[k]));
    return out;
  }
}

function envelope(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

main();
