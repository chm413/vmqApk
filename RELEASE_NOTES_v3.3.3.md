# V免签 v3.3.3 发布说明

> 发布日期：2026-11-15
> 发布 tag：V3.3.3
> Release 地址：https://github.com/chm413/vmqApk/releases/tag/V3.3.3

本版是 v3.3.2 的收尾：把**剩下的 228 处 `android.util.Log` 全部接进统一日志**，并修正存活检测里
`UsageStats` 的一处误判。没有新界面、没有权限变更。

## 🔍 修正：UsageStats 会把「一直在用」误判成「无前台活动」

### 问题

v3.3.2 的推断逻辑只扫最近 10 分钟的 `MOVE_TO_FOREGROUND` 事件：

```java
if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) { active = true; }
```

该事件**只在切入前台的那一刻产生一次**。如果微信从 3 小时前就一直停在前台（挂机场景很常见），
最近 10 分钟窗口内根本没有新事件 → 判定为 `无前台活动`，与事实相反。

### 修复

改为两级信号，先用更可靠的 `lastTimeUsed`：

| 顺序 | 依据 | 说明 |
|---|---|---|
| ① | `usm.queryUsageStats(INTERVAL_DAILY, from, now).getLastTimeUsed()` | 反映「最后一次被使用」，应用一直停在前台也成立 |
| ② | `queryEvents()` 的 `MOVE_TO_FOREGROUND` | 兜底：个别 ROM 的 `queryUsageStats` 返回空 |

只有 `lastTimeUsed >= 窗口起点` 才判为 `最近活跃`，避免界面出现「3000 分钟前有前台活动」这种废话。

> 仍然只敢说「最近活跃」，不等于进程存活——这是 `UsageStats` 这种手段的上限，不会因为改了实现就变成精确定论。

## 📜 日志全覆盖：228 处 `Log.*` → `AppLog.*`

v3.3.2 只接了心跳、保活、通知、设置这几条主链路，其余仍是裸 `Log`（只进 logcat，用户在手机上永远看不到）。
本版把剩下的一次性补齐：

| 文件 | 处数 | 补上之后能看到什么 |
|---|---|---|
| `SplashScreenPerformanceOptimizer` | 54 | 启动资源预加载、内存优化、缓存清理的每一步耗时 |
| `MainActivity` | 37 | 扫码配置、主题切换、启动画面诊断的完整过程 |
| `SplashScreenThemeAdapter` | 28 | 深色/动态颜色/Material You 适配到底走到了哪一步 |
| `SplashScreenLogger` | 26 | 启动各阶段时间线 |
| `PerformanceMonitor` | 25 | 冷启动耗时、内存占用、网络请求耗时 |
| `LegacySplashScreenHelper` | 17 | 传统启动画面过渡 |
| `SplashScreenErrorHandler` | 14 | 启动画面错误与回退路径 |
| `ThemeUtils` | 8 | 主题初始化 |
| `Utils`（RetryInterceptor） | 6 | **网络重试的每一次**（此前只在 logcat 里） |
| `SplashScreenTestActivity` | 6 | 启动画面测试工具 |
| `CompatibilityChecker` | 2 | 兼容性检查结论 |
| `StartReceive` | 2 | 开机自启（见下） |
| `SplashScreenDiagnosticImpl` / `SettingsActivity` | 3 | 诊断入口、扫码拉起 |
| **合计** | **228** | |

### 顺带修的两个日志缺陷

1. **级别过滤会连 logcat 一起关掉**
   原实现是「先判级别，不够就 return」，导致用户把应用内级别设成 INFO 后，
   `DEBUG` 日志**连 logcat 都不再输出**。而接 adb 看 logcat 恰恰是排障的唯一原始通道。
   现改为：**logcat 永远完整输出，级别只过滤应用内日志（内存 / 文件 / 广播）**。

2. **同一条启动日志会在设置页出现两遍**
   `SplashScreenLogger` 各方法已写 `AppLog`，但内部文件写入那层又调了一次
   （`Utils.logSplash`），且 `SplashScreenDiagnosticImpl.logInfo` 还写第三次。
   现已收敛为「一条日志只落一次」，`Utils.logSplash` 随之删除。

### 开机自启现在可追溯

`StartReceive` 此前只打一句 `Log.d("start")`。现在记录：

```
I/StartReceive: 收到广播 action=android.intent.action.BOOT_COMPLETED
I/StartReceive: 开机自启 | 电池优化白名单=已加入
```

用户报「重启后就不工作了」时，第一件事就是看这两行在不在——不在说明系统没拉起我们（自启被厂商拦了），
在但后续没有心跳说明是别的问题。

## 🔧 其他

- versionCode 6 → 7，versionName 3.3.2 → 3.3.3
- 无新增权限、无新增依赖、无界面改动
- 性能监控类日志保持在 `DEBUG` 级，默认 `INFO` 下不落盘，不会把日志文件撑爆

## ⚠️ 升级说明

- 同签名覆盖安装（`vmqfox.keystore`），配置与日志设置原样保留
- 若之前把日志级别调到了 `WARN`/`ERROR`，升级后仍然生效，需要看详细日志请到设置页切回 `INFO`

## 📋 验证清单

- [ ] 微信停在前台不动 > 10 分钟后点「立即检测」→ 应显示 `最近活跃（0 分钟前…）`，不再显示 `无前台活动`
- [ ] 把微信彻底杀掉且 10 分钟无操作 → `无前台活动`（推断）或 `未运行`（有 Shizuku 时）
- [ ] 设置页日志级别调到 `WARN` → 应用内看不到 `I/` 行，但 `adb logcat` 仍能看到 `I/` 和 `D/`
- [ ] 冷启动后设置页日志里能看到启动画面、主题适配、性能耗时的连续记录，且**每件事只出现一次**
- [ ] 断网状态下能看到 `RetryInterceptor` 的重试记录（`W/RetryInterceptor`）
- [ ] 重启手机 → 日志首行应是 `收到广播 action=...BOOT_COMPLETED`，随后是 `开机自启 | 电池优化白名单=…`
- [ ] 日志文件体积：正常使用一天远小于 1MB 上限

---

**相关文档**：[RELEASE_NOTES_v3.3.2.md](RELEASE_NOTES_v3.3.2.md) · [RELEASE_NOTES_v3.3.1.md](RELEASE_NOTES_v3.3.1.md) · [README.md](README.md)
