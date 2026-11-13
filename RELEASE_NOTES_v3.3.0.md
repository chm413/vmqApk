# V免签 v3.3.0 发布说明

> 发布日期：2026-11-13
> 发布 tag：V3.3.0
> Release 地址：https://github.com/chm413/vmqApk/releases/tag/V3.3.0

## ✨ 新功能：Shizuku 增强保活（本应用 + 支付宝 + 微信）

### 原理
Shizuku 以 **shell(uid 2000) / root** 身份提供 Binder 通道，应用无需 root 即可下发受保护的系统命令。
本版在设置页新增「Shizuku 增强保活」卡片，一键完成三类系统的保活/权限设置。

### 一键保活做了什么

对 **本应用 `com.vone.qrcode` / 支付宝 `com.eg.android.AlipayGphone` / 微信 `com.tencent.mm`** 各执行 5 条命令：

| 命令 | 作用 |
|---|---|
| `am set-standby-bucket <pkg> active` | 脱离 rare/restricted 待机桶，后台 CPU/网络窗口更宽松 |
| `cmd appops set <pkg> RUN_IN_BACKGROUND allow` | 允许后台运行 |
| `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow` | 允许任意后台活动 |
| `cmd deviceidle whitelist +<pkg>` | 加入 Doze 白名单，打瞌睡期间不被切断 |
| `am set-inactive <pkg> false` | 从闲置状态唤醒 |

另外提供独立按钮：**授予敏感通知权限（Android 15+）**
`cmd appops set com.vone.qrcode RECEIVE_SENSITIVE_NOTIFICATIONS allow`
—— 替代 README 里手动敲 adb 的步骤。

### 使用步骤
1. 安装 [Shizuku](https://shizuku.rikka.app/)，通过无线调试或 root 启动
2. 打开本应用 → 设置 → Shizuku 增强保活 → 「授权 Shizuku」
3. 点「支付宝 / 微信 / 本应用 一键保活」，逐条查看执行结果（✔/✘ + exit code）
4. Android 15+ 再点「授予敏感通知权限」

### 未安装 Shizuku 怎么办
点「查看 ADB 等效命令」，复制到电脑执行同样的命令，效果一致（一次性，重启后部分设置可能复位）。

## 🔧 其他

- versionCode 3 → 4，versionName 3.1 → 3.3.0（本版起与发布 tag 对齐，此前 tag 与 versionName 不同步）
- 新增依赖：`dev.rikka.shizuku:api:13.1.5`、`dev.rikka.shizuku:provider:13.1.5`（Maven Central）
- Manifest 新增 `ShizukuProvider` 与 `moe.shizuku.manager.permission.API_V23` 声明
- 未安装 Shizuku 时所有入口安全降级，不影响心跳/推送/仪表盘等其他功能

## ⚠️ 升级说明

- 同签名覆盖安装（vmqfox.keystore）
- Shizuku 为**可选**能力：不装也能正常用，只是保活需要手写 adb 命令
- Shizuku 授权可随时在 Shizuku 应用中撤销

## 📋 验证清单

- [ ] 未安装 Shizuku：设置页状态显示「未运行」，点授权弹引导，一键保活提示先授权
- [ ] 已安装未授权：状态「等待授权」，点授权后变「已授权 v13.x」
- [ ] 授权后一键保活：15 条命令（3 个包 × 5 条）全部 `exit=0`
- [ ] `adb shell am get-standby-bucket com.tencent.mm` 返回 `active`
- [ ] `adb shell cmd deviceidle whitelist` 列表包含三个包名

---

**相关文档**：[RELEASE_NOTES_v3.2.0.md](RELEASE_NOTES_v3.2.0.md) · [README.md](README.md)
