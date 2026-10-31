<!-- pr: theme-accent-circle-alias -->

- 修复主题配色选择器圆形色点边缘锯齿：色点改用 M3 Surface 直绘（shape 参数抗锯齿 fill/stroke），移除硬件渲染下不抗锯齿的 clip(CircleShape) 手搓圆——点击选中、描边加粗后锯齿尤其明显的问题一并根治
