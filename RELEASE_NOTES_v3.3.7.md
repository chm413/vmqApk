# V免签 v3.3.7 发布说明

> 发布日期：2026-11-15
> 发布 tag：V3.3.7
> Release 地址：https://github.com/chm413/vmqApk/releases/tag/V3.3.7
> 完整审计报告：[FUNCTION_AUDIT.md](FUNCTION_AUDIT.md)

这一版做了一次**全功能审计**：36 个 Java 文件 / 10,905 行，逐模块核对入口、状态机与异常路径。
共发现 5 个缺陷，其中 2 个会直接导致**丢单**、1 个在 **Android 14 上必崩**。

**建议所有用户升级。**

---

## 🔴 修复 1：前台重试只有 20 秒，第 4、5 次重试永远等不到

`ForegroundServer.onStartCommand` 里：

```java
handler.postDelayed(stopServerRunnable, MAX_SHOW_TIME);   // MAX_SHOW_TIME = 20 秒
```

20 秒后无条件 `stopService`。而推送重试的退避是 2s → 4s → 8s → 16s，**第 4 次重试要等到第 30 秒**。

也就是说：界面写着「重试 5 次」，实际只跑了 3 次，剩下两次连同常驻通知一起被系统清掉。
**断网时间稍长，这一笔收款就丢了。**

修复：把 `MAX_SHOW_TIME` 的语义从「总时长」改成「**空闲超时**」——
每次真正发起一次重试就重新计时，只有「最后一次尝试结束后 20 秒」才退出。
同时加 `MAX_FOREGROUND_LIFETIME_MS = 10 分钟` 的绝对寿命上限，避免服务被无限续命挂着常驻通知。

---

## 🔴 修复 2：Android 12+ 后台启动前台服务会崩溃

```java
context.startForegroundService(intent);   // 没有 try/catch
```

Android 12 起后台启动前台服务受限，应用在后台（无可见界面、也不在电池优化白名单里）调用它会抛
`ForegroundServiceStartNotAllowedException`（`IllegalStateException` 子类）。

而这个方法的两个调用点恰好都在**后台出错**的时刻：心跳失败、收款推送失败。
**越是需要它兜底的场景，越容易被系统拒绝** —— 一次网络抖动就把整个进程带走，
本来只是丢一次重试，变成丢掉整个监听服务。

修复：`catch (IllegalStateException)` 降级为日志，并提示用户把 App 加入电池优化白名单。

---

## 🔴 修复 3：Android 14 上锁屏提醒页必崩

`LockShowActivity.registerBroadCast()` 调用 `registerReceiver(receiver, filter)` 没带导出标志。

targetSdk 已是 34，**Android 14 起对非系统广播的运行时注册强制要求**
`RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED`，否则抛 `SecurityException`。

`SettingsActivity` 与 `ForegroundServer` 早就按新规注册，唯独这里漏了 ——
而它正是**收款推送失败时弹出的那个页面**。也就是说 Android 14 用户断网收一笔款，会直接闪退。

修复：按版本分支传 `RECEIVER_NOT_EXPORTED`（只有本应用会发这个广播，最小暴露面），
并用 `receiverRegistered` 标志保证反注册不会二次抛异常。

---

## 🟠 修复 4：前台通知点了没反应

通知的 `contentIntent` 发的是 `TRY_CLOSE_ACTIVITY_ACTION`，但这个 action
**只被声明、没有任何地方处理**。用户点通知想结束那根常驻通知，什么也不会发生。

修复：`StartReceive` 里处理该 action，正常收尾前台服务。

---

## 🟠 修复 5：配置地址规范化不完整

两处边界：

| 输入 | 修复前 | 修复后 |
|---|---|---|
| `HTTPS://example.com` | host 保留整串 → URL 变成 `http://HTTPS://example.com/api/...` | 正确识别为 https |
| `https://` | 剥离后 host 为空，照样保存 → `https:///api/...` 全失败 | 拒绝保存并提示 |

修复：大小写不敏感剥离协议前缀、去尾斜杠、剥离后为空则拦截。

---

## 📋 审计覆盖范围

| 模块 | 功能点数 | 结论 |
|---|---|---|
| 收款识别（含金额解析、去重） | 9 | ✅ 无新问题 |
| 上报与重试 | 6 | 🔴 3 处已修 |
| 心跳 | 3 | ✅ 正常 |
| 保活体系（Shizuku / Root / 存活检测 / 自动拉起） | 6 | ✅ 正常 |
| 运行控制（总开关 / 三开关 / 休眠） | 3 | ✅ 正常 |
| 配置（手动 / 扫码 / HTTPS） | 3 | 🟠 1 处已修 |
| 日志（统一 / 脱敏 / 级别 / 导出 / 清除） | 5 | ✅ 正常 |
| UI（深色模式 / 启动画面 / 权限引导） | 3 | ✅ 正常，无死代码 |

另外核对了：所有布局交互控件均有绑定（含 `android:onClick` 声明式处理器全部存在）、
`PendingIntent` 全部带 `FLAG_IMMUTABLE`、广播注册/反注册全部配对。

---

## ⚠️ 未修复的风险项（需你决策）

审计中发现的 6 项**取舍型风险**，属于产品/安全决策而不是代码错误，本版没有擅自改动默认值：

| # | 风险 | 说明 |
|---|---|---|
| R1 | TLS 默认不校验证书 | `verify_ssl` 默认关，走 trust-all。自签证书场景是刚需，但存在中间人风险 |
| R2 | `allowBackup=true` | 商户密钥明文存在 SharedPreferences，会被云备份带走 |
| R3 | 申请「所有文件访问」 | 实际写日志用的是 `getExternalFilesDir`，**不需要任何权限** |
| R4 | Android 15 `dataSync` 6 小时/天上限 | 长期挂机场景可能被系统停止 |
| R5 | 密钥明文存储 | 可用 `EncryptedSharedPreferences` |
| R6 | 启动画面代码 2,400 行 | 占全部代码 22%，功能正常但维护成本需评估 |

详细说明见 `FUNCTION_AUDIT.md` 第三节。**需要我处理哪几项，告诉我编号即可。**

---

## 升级说明

- 覆盖安装即可，无需卸载，配置与日志保留
- `versionCode` 10 → 11，`versionName` 3.3.6 → 3.3.7
- 无新增权限、无新增开关、UI 无变化

---

## 真机回归清单

### 必须验

- [ ] **断网重试**：开飞行模式收一笔款 → 常驻通知出现 → 保持 2 分钟 → 恢复网络 →
      确认推送最终成功，日志里有 4~5 次重试记录
- [ ] **Android 14 锁屏页**：在 Android 14 设备上让推送失败一次，确认锁屏提醒页正常弹出且**不闪退**
- [ ] **后台启动前台服务**：把 App 移出电池优化白名单，在后台让心跳失败一次，确认**不闪退**，
      日志出现「启动前台重试服务被系统拒绝」
- [ ] **点击常驻通知**：点一下前台重试通知，确认通知立即消失

### 回归验

- [ ] 微信 / 支付宝各收一笔，金额与提示一致
- [ ] 地址栏填 `HTTPS://your-host` 保存后，主页配置显示正常、心跳成功
- [ ] 关闭总开关 → 线程休眠、无新心跳；重开 → 5 秒内恢复
- [ ] 已 Root：15 条保活命令全部 ✔；未 Root：点「检测 Root」12 秒内返回，不 ANR
- [ ] 日志导出、级别切换、清除均正常
