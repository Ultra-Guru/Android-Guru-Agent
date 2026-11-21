<!-- pr: glasslab-darkbar-liquid-swipe -->

- 玻璃实验室 Dark 栏升级「液态玻璃可滑动预览」：4 项固定导航 → 8 项双页 Dock（HorizontalPager），横向手势自由滑动，导航项目从中央实心发布按钮下滑过——实心如石，项目流过如水
- 唯一液态滑动药丸取代逐项淡入淡出：位置 = f(选中项浮点槽位，pager 实时偏移)，随手势连续跟手位移；点击换选经 MediumBouncy 弹簧落位，跨页选择与页面滚动同程共享连续轨迹，无跳变
- 药丸果冻挤压：滑动偏移分数越大横向拉伸越多、纵向等比压缩（体积近似守恒），回中复原
- 全部形变在 graphicsLayer draw 通道读取 State（animateFloatAsState/pager 偏移均为 State 读数），滑动帧零重组（#262 同款纪律）
- 双页页点指示器（当前页加宽点亮）+ 初始页跟随恢复的选中项（rememberSaveable 跨重建不脱页）
- Dock 保持 kyant0 backdrop 真采样玻璃（Floating 档：blur + lens 折射 + 色散）——滚动内容掠过栏后画面实时变化
