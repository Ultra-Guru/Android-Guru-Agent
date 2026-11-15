'use strict';
/*
 * # dsh-introspect.js —— 动态能力探测（Stage 2 Runtime Probe）
 *
 * 用法：node dsh-introspect.js <pkgDir>
 *
 * 契约（与 Kotlin 侧 DshPluginIntrospector 的协议）：
 * - **stdout 恰好一个 JSON envelope**（诊断一律走 stderr）；
 * - envelope: { ok, protocol, kind:"introspect", form, entry, name,
 *               inject[], requiredServices[], unsupportedServices[],
 *               tools[{name,description,parametersSchema,outputSchema}],
 *               servicesTouched{service:[actions]}, disposeErrors[],
 *               error{code,message}? }
 * - apply 执行一次（探测语义）；进程即退 —— 闭包不存活（红线 7：
 *   冷启动模式每次调用重跑 apply 由 dsh-run-tool.js 承担，探测≠运行承诺）；
 * - 任何异常折进 envelope.error + ok:false —— 探测失败是**结果**，不是崩溃。
 */
const shim = require('./dsh-shim.js');

async function main() {
  const pkgDir = process.argv[2];
  if (!pkgDir) {
    envelope({ ok: false, error: { code: 'usage', message: 'usage: node dsh-introspect.js <pkgDir>' } });
    return;
  }

  const touched = new Set();
  const capturedTools = [];
  const appliedServices = [];

  let loaded;
  try {
    loaded = await shim.loadPlugin(pkgDir);
  } catch (e) {
    envelope({
      ok: false,
      form: 'unknown',
      error: { code: 'load-failed', message: String(e && e.message) },
    });
    return;
  }

  if (loaded.unsupportedClass) {
    envelope({
      ok: false,
      form: loaded.form,
      name: loaded.name,
      entry: loaded.entry,
      inject: loaded.inject,
      error: { code: 'unsupported-class-form', message: '类形态插件（Cordis Service 构建）宿主不支持 —— 如实上报，不虚报兼容' },
    });
    return;
  }

  // ── inject 前置检查：声明了宿主不支持的服务 → 不调用 apply（Cordis 依赖就绪语义）──
  const SUPPORTED = ['tools', 'logger', 'config', 'effect'];
  const unsupportedInject = loaded.inject.filter((s) => !SUPPORTED.includes(s));
  if (unsupportedInject.length > 0) {
    envelope({
      ok: true,
      kind: 'introspect',
      protocol: 1,
      form: loaded.form,
      name: loaded.name,
      entry: loaded.entry,
      inject: loaded.inject,
      requiredServices: loaded.inject,
      unsupportedServices: unsupportedInject,
      tools: [],
      servicesTouched: {},
      disposeErrors: [],
      note: '依赖未支持服务，apply 未执行（依赖就绪语义）—— 工具面为空，绝不虚报',
    });
    return;
  }

  const { ctx, state } = shim.createShimContext({
    supported: SUPPORTED,
    touched,
    onToolRegistered: (def) => capturedTools.push(def),
  });

  let applyError = null;
  try {
    await loaded.apply(ctx);
  } catch (e) {
    applyError = e;
    if (e instanceof shim.ServiceUnavailableError) {
      // apply 中途触碰不支持的服务：已捕获的工具仍如实上报（部分可用面）
      envelope({
        ok: true,
        kind: 'introspect',
        protocol: 1,
        form: loaded.form,
        name: loaded.name,
        entry: loaded.entry,
        inject: loaded.inject,
        requiredServices: Array.from(new Set(loaded.inject.concat([e.service]))),
        unsupportedServices: [e.service],
        tools: capturedTools.map(toolEnvelope),
        servicesTouched: Object.fromEntries(Array.from(touched).map((s) => [s, ['apply-aborted']])),
        disposeErrors: shim.disposeContext(state),
        note: 'apply 中止于服务缺失 —— 已注册部分如实上报',
        error: { code: 'service-unavailable', message: String(e && e.message) },
      });
      return;
    }
    envelope({
      ok: false,
      kind: 'introspect',
      protocol: 1,
      form: loaded.form,
      name: loaded.name,
      entry: loaded.entry,
      inject: loaded.inject,
      tools: capturedTools.map(toolEnvelope),
      servicesTouched: Object.fromEntries(Array.from(touched).map((s) => [s, ['apply-error']])),
      error: { code: 'apply-failed', message: String(e && e.message) },
    });
    return;
  }

  envelope({
    ok: true,
    kind: 'introspect',
    protocol: 1,
    form: loaded.form,
    name: loaded.name,
    entry: loaded.entry,
    inject: loaded.inject,
    requiredServices: loaded.inject,
    unsupportedServices: [],
    tools: capturedTools.map(toolEnvelope),
    servicesTouched: Object.fromEntries(Array.from(touched).map((s) => [s, ['apply']])),
    disposeErrors: shim.disposeContext(state),
  });
}

/** 工具定义 → envelope 条目（schema 转换在这里做 —— JS 侧最懂自有格式）。 */
function toolEnvelope(def) {
  const converted = shim.parametersToJsonSchema(def.parameters);
  return {
    name: def.name,
    description: typeof def.description === 'string' ? def.description : '',
    parametersSchema: converted.schema,
    parametersSchemaExact: converted.exact,
    outputSchema: def.output && def.output.schema ? shim.safeStringify(def.output.schema) : null,
  };
}

function envelope(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

main().catch((e) => {
  envelope({ ok: false, error: { code: 'internal', message: String(e && e.stack || e) } });
});
