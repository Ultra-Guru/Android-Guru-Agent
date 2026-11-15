'use strict';
// P0 fixture —— apply 中途触碰 ctx.storage（宿主 PLANNED）：第一个工具已注册，
// 之后中止。预期 envelope：ok=true + tools 恰 1 个 + unsupportedServices=["storage"]。
module.exports = function apply(ctx) {
  ctx.tools.register({
    name: 'fixture_before_abort',
    description: '中止前注册的工具',
    parameters: {},
    async execute() {
      return 'before';
    },
  });
  void ctx.storage.read('k'); // 触发 ServiceUnavailableError
  ctx.tools.register({
    name: 'fixture_never_registered',
    description: '永不到达',
    async execute() {
      return 'never';
    },
  });
};
