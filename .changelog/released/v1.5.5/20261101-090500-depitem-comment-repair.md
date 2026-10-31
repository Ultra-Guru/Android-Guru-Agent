<!-- pr: depitem-comment-repair -->

- 修复 main 编译红：DepItem.kt KDoc 注释体里的 `/etc/profile.d/*.sh` 写法含 `/*` 序列，在 Kotlin 可嵌套块注释里开了一层悬垂嵌套——注释吞到文件尾触发 "Unclosed comment"（app 编译 / Quality Gate 注释完整性门禁双红）。改为文字描述「/etc/profile.d 目录下的 .sh 片段」（AGENTS.md 注释铁律：注释体内不出现斜杠紧跟星号的序列）
