<!-- pr: normal-browser-and-git -->
- 修浏览器打开即「人工接管」：openBrowser 不再强制 enterHandoffMode——用户从抽屉打开浏览器看到的是正常浏览器（输入网址即可上网），人工接管回归显式动作（仪表盘按钮 / BrowserShow）；空态引导文案同步改写（中英）
- 新工具 CodeGitRun（任意 git 子命令通道，PRoot 沙箱执行）：clone/pull/push/fetch/rebase/tag 等直达，GitHub 已连接时自动注入凭据桥（GH_TOKEN + credential.helper）——GitHub 仓库任务「clone→改→commit→push→PR」全程 git 命令完成，不经浏览器；交互式子命令前置拒绝并给替代方案；引号感知参数切分（纯 JVM 单测 6 例）
- 新工具 GithubCreatePullRequest（POST /pulls）：补全 git 通路的最后一环，fork PR 支持 user:branch 形态；GithubApiService 增 createPullRequest + PR 数据模型
- 系统提示词新增「Task Routing」四车道分工段：GitHub 仓库操作走 CodeGitRun+GithubCreatePullRequest / 技术问题 WebSearch 后用 GitHub 工具读权威源 / 浏览器族仅用于真实网页自动化（登录/表单/验证码）/ 读页面内容用 WebFetch——纠正「什么都往浏览器塞」的选道错误
- WebSearch 工具描述补 GitHub 优先引导（技术/代码/库问题优先 GithubSearchRepos→SearchCode→ReadFile）；BrowserNavigate 描述明确「自动化专用」定位与替代车道
