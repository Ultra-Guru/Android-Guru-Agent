// P0 fixture —— ESM 对象模块（官方形态二）：defineTool 构造 + inject 声明。
// 注：defineTool 用 vendored 同形桩（P0 真机验证才用真实 @deepseek-ai/dsh-tools，
// CI/本地无 npm 网络依赖 —— 形态一致性由桩保证，语义一致性由真机 P0 钉定）。
import { defineTool } from './vendor/define-tool.js';

export const name = 'fixture-esm';
export const inject = ['tools', 'agents']; // agents 宿主不支持 → 依赖就绪语义：不执行 apply

export default {
  apply(ctx) {
    ctx.tools.register(
      defineTool({
        name: 'fixture_search',
        description: '按关键词搜索',
        parameters: {
          q: { type: 'string', required: true, description: '关键词' },
        },
        output: { schema: { type: 'string' } },
        async execute(args) {
          return `results for ${args.q}`;
        },
      })
    );
  },
};
