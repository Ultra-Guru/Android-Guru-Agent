// defineTool 同形桩：identity —— 真实 @deepseek-ai/dsh-tools 的 defineTool
// 返回的 ToolDefinition 与输入同构（name/parameters/output/execute），
// 捕获面只关心注册对象形状，桩足以覆盖 CI 路径。
export function defineTool(def) {
  return def;
}
