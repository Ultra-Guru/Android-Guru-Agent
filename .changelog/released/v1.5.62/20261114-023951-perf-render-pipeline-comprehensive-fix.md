<!-- pr: perf-render-pipeline-comprehensive-fix -->

- 流式渲染性能综合修复（perfetto + gfxinfo 实测驱动）：旧版 jank 33.15%、高输入延迟 58692 次、GPU scratch 169MB/320 面、单帧最长 271ms——本批系统性消除三大根因
- 玻璃层免冗余重录：backdrop 引擎层重录制改为输入快照差分（位置/尺寸/效果/源实例未变即复用已录 display list），流式期间全部玻璃卡不再逐帧整层重录（Record View#draw 尖峰与 flush layers 排队的主源）
- Ambient 档玻璃剥离 blur/lens：程序化静态纹理源不再进 GPU 高斯模糊链（对静态软渐变模糊输出≈输入），直接绘制霜面材质——时间轴卡片群 GPU 负载大幅下降，真实采样源（输入栏/抽屉）blur/lens 语义不变
- 抽屉关闭期间停用真实采样：关闭态 17 个导航项的 blur 采样层全部免掉（924x196 层 x10/帧的实测来源），开启动画起始帧恢复玻璃，观感零变化
- 流式贴底滚动帧对齐：贴尾滚动统一对齐到下一帧起点执行——消除 draw 派发中段的 measureAndLayout 追赶（实测尖峰 106ms），连拍自动合拍
- 动画读取移出组合层：生成卡呼吸圆点/骨架条、流式代码窗光标、Rikka 思考卡/工具块标题脉冲、流式呼吸圆点全部改为 graphicsLayer 层读取——呼吸动画期间零重组零重测（旧版整卡玻璃栈 60fps 重组）
- Markdown 行内样式记忆化：段落 AnnotatedString 按内容+主题色缓存，未变段落流式期间零重建
