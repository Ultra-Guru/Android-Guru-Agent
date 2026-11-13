'use strict';
// P0 fixture —— CJS 函数模块（官方形态一）：register 裸对象 + 闭包状态。
module.exports = function apply(ctx) {
  let counter = 0; // 闭包状态：冷启动模式每次调用归零（如实预期），持久 Worker 保留

  ctx.tools.register({
    name: 'fixture_hello',
    description: '按称呼打招呼',
    parameters: {
      name: { type: 'string', required: true, description: '称呼' },
    },
    async execute(args) {
      return `你好, ${args.name}`;
    },
  });

  ctx.tools.register({
    name: 'fixture_stateful_counter',
    description: '闭包计数器（冷启动归零 / 持久 Worker 累加）',
    parameters: {},
    async execute() {
      counter += 1;
      return { count: counter };
    },
  });
};
