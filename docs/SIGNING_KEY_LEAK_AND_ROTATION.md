# 发布签名密钥泄露与轮换预案（S-01 / #803）

> 状态：**风险已确认，轮换待执行**。本文档是 S-01（PR #871）的后续义务：
> 在 docs 中显式声明该密钥已公开泄露、必须轮换，并给出迁移预案。
> 最后核对：2026-11（main v1.5.49）。

## 1. 事实与边界

- v1.4.8 ~ v1.5.23 期间，发布签名 keystore（`keystore/apex-release.jks`）与其
  明文口令曾被提交进本公开仓库（当时的修复方案见
  [docs/v1.4.8-update-fixes.md](v1.4.8-update-fixes.md) §1）。
- **该私钥与口令至今仍留在 git 历史中**，任何能访问本仓库历史的人均可取得，
  并产出与官方构建**签名完全一致**的 APK。
- 按决策**不重写 git 历史**（改写公开仓库历史对已 fork / 已克隆副本无效，
  防线不成立反而破坏审计链与所有下游追踪）。
- S-01（PR #871，commit `ec83b584`）已完成的止损：
  - keystore 本体移出版本控制，`keystore/.gitignore` 拦截再入库；
  - `scripts/check_secrets.py` 按「后缀 + JKS magic 头」双通道兜底拦截；
  - 凭据仅经 Gradle 属性 / 环境变量注入，构建脚本无任何明文回退，
    缺失即 `checkReleaseSigningCredentials` fail-closed 失败；
  - CI 经 repo secrets（`APEX_RELEASE_KEYSTORE_BASE64` 等）恢复密钥材料。

## 2. 风险边界（签名指纹 ≠ 官方性）

对「持有泄露密钥的第三方」而言：

- `version.json.signingCertSha256` 客户端签名预检、覆盖安装签名校验、
  增量更新（VCDIFF 合成保留签名块）等安全前置**全部失效**——恶意 APK
  与官方产物在签名层不可区分；
- 公开渠道若出现仿冒分发，官方签名**无法作为举证手段**。

因此：在完成轮换（§3）之前，**本项目的发布签名指纹不代表构建的官方性**。
用户侧唯一可靠的验证路径：仅从 GitHub Releases 下载，并核对 Release 资产
中的 SHA-256 校验值。

## 3. 轮换预案（必须执行，未排期前按本节执行）

轮换 = 用 `scripts/generate_release_keystore.sh` 生成**全新**密钥并替换
CI repo secrets，随后发布首个新签名版本。约束与迁移窗口：

1. **换钥会破坏覆盖安装与增量更新兼容**（Android 签名不一致，需卸载重装）。
   v1.4.7→v1.4.8 已有一次全量换签先例（当时处理方式：文档明示
   「必须卸载重装一次」），流程与用户沟通模板可直接复用。
2. **建议的迁移窗口（双版本并行）**：
   - 版本 N：Release 说明 + 应用内更新面板公告「下一版将更换签名，
     升级需卸载重装」，给用户一个版本的缓冲期；
   - 版本 N+1：新钥签名。`version.json.signingCertSha256` 在发布流水线
     （release.yml）中由 keystore 现场提取，无需手工同步；
   - 增量补丁（xdelta3）在窗口内只对同签名版本合成，跨换签版本自动
     落回全量包（`patches.json` 按基线版本隔离，无需改动）。
3. **轮换完成后的动作**：更新本文档状态为「已轮换」，并在
   [docs/v1.4.8-update-fixes.md](v1.4.8-update-fixes.md) 的指纹勘误处
   标注旧指纹（`bcb9dfdd…`）已作废。
4. 轮换**不设截止日期**（个人项目，用户基数换签成本需权衡），但在任何
   「官方渠道分发承诺 / 商店上架」之前必须完成。

## 4. 关联

- #803（本风险的首个 issue 记录与修复指令）
- S-01 / PR #871（keystore 出库、fail-closed 注入、双通道拦截）
- `scripts/generate_release_keystore.sh`（新钥生成，口令仅 env 注入）
- `scripts/check_secrets.py` + `scripts/test_check_secrets.py`（入库拦截回归）
