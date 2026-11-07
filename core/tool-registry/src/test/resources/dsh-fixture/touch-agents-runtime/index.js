'use strict';
// P0 fixture —— 未声明 inject 但运行时触碰 ctx.agents（存在性检查 + 调用）：
// 诚实路径 = 触碰被记录 + apply 中止（绝不静默 mock 成功）。
module.exports = function apply(ctx) {
  if (ctx.agents) {
    // 存在性检查通过（proxy has → 记录触碰）；真实调用抛 ServiceUnavailableError
    return ctx.agents.list();
  }
  return 'no-agents';
};
