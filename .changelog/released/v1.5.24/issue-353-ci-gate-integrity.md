# 修复

- CI 门禁可靠性：#353 剩余子项清偿 —— KSP/Hilt 失败不再以 exit 0 放行、Kotlin daemon 崩溃单列硬失败；Gradle wrapper 由「每 job 覆盖生成」改为版本校验；check_code_quality.sh 的 xargs grep 补 -H；fetch_rootfs.sh 坏档自愈且计数变量归一；static-analysis 的 Maven 清单收敛为单一事实源并按内容哈希缓存（#353 ②⑤⑥⑧⑩）。
