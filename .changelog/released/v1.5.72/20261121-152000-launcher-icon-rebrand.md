<!-- pr: launcher-icon-rebrand -->

- 主图标换新：Viro invader 像素吉祥物 → 霓虹环形钻石品牌标（双断环 + 中心圆盘 + 菱形，青→品红渐变）
- Adaptive 图标：前景/monochrome 迁为位图（drawable-nodpi 432px），图案实测占比 54% 完整落在 66dp 安全区（61%），任何 launcher 形状裁切不伤外环；背景保持纯白
- Monochrome 主题图标同步重绘为新品牌剪影（外环 4 断口 + 内环 2 断口 + 圆盘挖菱形孔），Android 13+ 着色图标不再残留旧吉祥物
- Legacy 五密度（mdpi→xxxhdpi）launcher/round 全套重出：图案 72% 居中构图，round 预裁圆形
