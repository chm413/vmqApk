# V免签监控端 · 全功能审计报告（v3.3.6 → v3.3.7）

> 审计日期：2026-11-15
> 审计对象：`chm413/vmqApk` 分支 `material-you-style`，commit `c74b1ea`（v3.3.6）
> 代码规模：36 个 Java 文件 / 10,905 行；6 个布局；1 个 Manifest

## 审计方法

1. **入口可达性**：解析全部布局，核对每个交互控件是否有代码绑定或 `android:onClick` 处理器
2. **静态模式扫描**：吞异常、主线程 IO、Context 泄漏、广播注册/反注册配对、PendingIntent 标志、
   线程与执行器生命周期
3. **主链路实测**：把「通知文案 → 金额解析」的实现照搬成脚本，用真实通知文案跑用例
4. **逐模块契约核对**：每个功能点检查「入口 → 状态机 → 异常路径 → 文档一致性」

---

## 一、功能矩阵

下表的「结论」列只记**当前版本**的状态。

### A. 收款识别

| # | 功能点 | 入口 | 结论 |
|---|---|---|---|
| A1 | 通知监听总闸 | `onNotificationPosted` 第一行 | ✅ 正常。关闭后不解析、不落库 |
| A2 | 包名过滤 | `processPaymentNotification` | ✅ 正常（v3.3.7 提前到最前，减少无谓日志） |
| A3 | 微信 / 支付宝 / 企业微信 关键词匹配 | `processWechat/AlipayNotification` | ✅ 正常，语义与 v3.3.5 等价 |
| A4 | 金额解析 | `getMoney` / `getMoney2` / `resolveMoney` | ✅ 正常（v3.3.6 重写） |
| A5 | 正文为空的通知 | 同上 | ✅ 正常（v3.3.6 去掉了前置判空） |
| A6 | 重复到账去重 | `markOnceIfNew` / `markAmountOnce` | ✅ 正常（v3.3.6 新增两级） |
| A7 | 测试推送自检 | `checkPush` → `processTestNotification` | ✅ 正常 |
| A8 | 金额定标 | `MonitorSign.priceText` | ✅ 正常，`BigDecimal` 定两位，无科学计数法 |
| A9 | 请求签名 | `MonitorSign` HMAC-SHA256 | ✅ 正常，与后端规范串一致 |

### B. 上报与重试

| # | 功能点 | 入口 | 结论 |
|---|---|---|---|
| B1 | 收款推送 | `appPush` | ✅ 正常 |
| B2 | 失败后前台重试 | `foregroundPost` → `ForegroundServer.tryPushByUrl` | 🔴 **有缺陷，v3.3.7 修复**：重试窗口被 20 秒掐断 |
| B3 | 启动前台服务 | `enterForeground` | 🔴 **有缺陷，v3.3.7 修复**：Android 12+ 后台启动被拒会崩溃 |
| B4 | 锁屏提醒页 | `LockShowActivity` | 🔴 **有缺陷，v3.3.7 修复**：Android 14 注册广播崩溃 |
| B5 | 前台通知点击 | `StartReceive` | 🟠 **有缺陷，v3.3.7 修复**：action 无人处理，点了没反应 |
| B6 | 电源锁 | `acquire/releaseWakeLock` | ✅ 正常（v3.3.5 修掉 under-locked 闪退） |

### C. 心跳

| # | 功能点 | 入口 | 结论 |
|---|---|---|---|
| C1 | 30 秒周期 + 指数退避 | `initAppHeart` | ✅ 正常，退避上限 5 分钟 |
| C2 | 上报关闭后休眠 | `RunSwitch.awaitReportEnabled` | ✅ 正常，挂起不退出 |
| C3 | 手动「立即心跳检测」 | `doStart` | ✅ 正常 |

### D. 保活体系

| # | 功能点 | 入口 | 结论 |
|---|---|---|---|
| D1 | Shizuku 绑定 / 授权 | `ShizukuKeepAlive` | ✅ 正常 |
| D2 | Root 探测 | `PrivilegeShell` | ✅ 正常（v3.3.5 加了串行化与短缓存） |
| D3 | `pidof` 精确检测 | `AliveMonitor.checkByPidof` | ✅ 正常 |
| D4 | UsageStats 降级 | `checkByUsageStats` | ✅ 正常（以 `lastTimeUsed` 为主） |
| D5 | 自动拉起 | `AliveMonitor.maybeRevive` | ✅ 正常，10 分钟冷却 + 每小时 6 次 |
| D6 | 开机自启 | `StartReceive` | ✅ 正常 |

### E. 运行控制

| # | 功能点 | 入口 | 结论 |
|---|---|---|---|
| E1 | 主页总开关 | `sw_master` | ✅ 正常 |
| E2 | 三个独立开关 | 设置页运行控制卡 | ✅ 正常，`syncing` 标志防误记 |
| E3 | 休眠 / 恢复 | `RunSwitch` wait / notifyAll | ✅ 正常，5 分钟安全阀兜底 |

### F. 配置

| # | 功能点 | 入口 | 结论 |
|---|---|---|---|
| F1 | 手动输入地址 | `saveConfig` | 🟠 **有缺陷，v3.3.7 修复**：大写字头与空 host 未校验 |
| F2 | 扫码配置 | `btn_qr` → zxing | ✅ 正常 |
| F3 | HTTPS + 可选证书校验 | `sw_verify_ssl` | ✅ 功能正常，但默认不校验（见风险 R1） |

### G. 日志

| # | 功能点 | 入口 | 结论 |
|---|---|---|---|
| G1 | 统一日志（内存 + 按天文件） | `AppLog` | ✅ 正常 |
| G2 | 脱敏 | `Desensitizer` | ✅ 正常，配置页不脱敏（设计如此） |
| G3 | 级别切换 | `showLevelPicker` | ✅ 正常 |
| G4 | 导出 | `exportLogs` | ✅ 正常，后台线程读盘 + 系统分享 |
| G5 | 清除 | `btn_clear_logs` | ✅ 正常 |

### H. UI 与其他

| # | 功能点 | 入口 | 结论 |
|---|---|---|---|
| H1 | 深色模式配色 | `ThemeUtils` | ✅ 正常 |
| H2 | 启动画面 | `SplashScreen*` 全家桶 | ✅ 正常，且**无死代码**（11 个类全部有引用） |
| H3 | 权限引导 | `checkAndRequestPermissions` | ✅ 正常，但请求的权限偏重（见风险 R3） |

---

## 二、v3.3.7 修复的 5 个缺陷

### 🔴 1. 前台重试窗口只有 20 秒，第 4、5 次重试永远等不到

`ForegroundServer.onStartCommand` 里 `postDelayed(stopServerRunnable, MAX_SHOW_TIME=20s)`，
20 秒后无条件 `stopService`。而推送重试按 2s→4s→8s→16s 退避，**第 4 次重试要到第 30 秒**。

后果：断网时「重试 5 次」实际只跑了 3 次，后面两次连同常驻通知一起被清掉 —— 这一笔收款就丢了。

修复：把 `MAX_SHOW_TIME` 的语义从「总时长」改成「**空闲超时**」，每次发起重试前重新计时；
另加 `MAX_FOREGROUND_LIFETIME_MS = 10 分钟` 的绝对寿命上限，避免服务被无限续命挂着常驻通知。

### 🔴 2. Android 12+ 后台启动前台服务会崩溃

`enterForeground` 直接 `startForegroundService`，没有 try/catch。Android 12 起后台启动前台服务受限，
抛 `ForegroundServiceStartNotAllowedException`（`IllegalStateException` 子类）。

而这个方法的两个调用点恰好都在「后台出错」的时刻：心跳失败、收款推送失败。
**越是需要它兜底的场景越容易被拒**，结果是一次网络抖动把整个进程带走。

修复：`catch (IllegalStateException)` + 降级日志，提示用户加入电池优化白名单。

### 🔴 3. Android 14 打开锁屏提醒页必崩

`LockShowActivity.registerBroadCast()` 调 `registerReceiver(receiver, filter)` 没带导出标志。
targetSdk = 34，Android 14 起对**非系统广播**的运行时注册强制要求
`RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED`，否则抛 `SecurityException`。

`SettingsActivity` 与 `ForegroundServer` 早就按新规注册，唯独这里漏了。而它正是收款推送失败时弹出的页面。

修复：按版本分支传 `RECEIVER_NOT_EXPORTED`，并用 `receiverRegistered` 保证反注册不会二次抛异常。

### 🟠 4. 前台通知点了没反应

通知的 `contentIntent` 发的是 `TRY_CLOSE_ACTIVITY_ACTION`，但这个 action 只被声明、**没有任何地方处理**。
用户点通知想结束常驻通知，什么也不会发生。

修复：`StartReceive` 里处理该 action，调用 `exitForeground` 正常收尾。

### 🟠 5. 配置地址规范化不完整

- 只认小写 `https://`，用户从浏览器复制 `HTTPS://xxx` 会整串留在 host 里 → URL 变成 `http://HTTPS://xxx/api/...`
- 只填 `https://` 时剥离后 host 为空，照样保存 → 拼出 `https:///api/...`，心跳全失败且看不出原因

修复：大小写不敏感剥离、去尾斜杠、剥离后为空则拒绝保存并提示。

---

## 三、未修复的风险观察项

这些不是「代码写错了」，而是**产品/安全取舍**，需要你决定是否调整。

| # | 风险 | 说明 | 建议 |
|---|---|---|---|
| R1 | TLS 默认不校验证书 | `verify_ssl` 默认 `false`，走 trust-all + `hostnameVerifier` 恒真。可被中间人伪造服务端响应 | 自建服务用自签证书是刚需，但应引导用户优先开启校验；至少首次配置时提示 |
| R2 | `allowBackup=true` | 商户密钥明文存在 `SharedPreferences`，会被云备份带走 | 改为 `false`，或用 `dataExtractionRules` 排除该 prefs |
| R3 | 申请「所有文件访问」 | `MANAGE_EXTERNAL_STORAGE` 是最高危权限之一，而实际写日志用的是 `getExternalFilesDir`（**不需要任何权限**） | 除扫码选图外无必要；建议降级为 `READ_MEDIA_IMAGES` |
| R4 | Android 15 `dataSync` 6 小时/天上限 | 前台重试服务类型是 `dataSync`，Android 15 起累计超 6 小时会被系统停止 | 长期挂机场景需改类型或改用普通重试策略 |
| R5 | 密钥明文存储 | `SharedPreferences` 未加密 | 可用 `EncryptedSharedPreferences`（需引入 security-crypto） |
| R6 | 启动画面代码量偏大 | `SplashScreen*` 共 10 个类约 2,400 行，占全部代码 22% | 功能正常且有引用，但收益与维护成本需评估 |

---

## 四、真机回归清单（v3.3.7）

按风险从高到低排序，前四项必须验。

### 必须验

- [ ] **断网重试**：开飞行模式收一笔款 → 确认常驻通知出现 → 保持 2 分钟 → 恢复网络 →
      确认推送最终成功，日志里能看到 4~5 次重试记录（验证修复 1）
- [ ] **Android 14 锁屏页**：在 Android 14 设备上让推送失败一次，确认锁屏提醒页能正常弹出且**不闪退**（验证修复 3）
- [ ] **后台启动前台服务**：把 App 移出电池优化白名单，在后台让心跳失败一次，
      确认**不闪退**，日志出现「启动前台重试服务被系统拒绝」（验证修复 2）
- [ ] **点击常驻通知**：点一下前台重试通知，确认通知立即消失（验证修复 4）

### 回归验

- [ ] 微信 / 支付宝各收一笔，金额与提示一致
- [ ] 地址栏填 `HTTPS://your-host` 保存后，主页显示配置正常、心跳成功（验证修复 5）
- [ ] 关闭总开关 → 线程休眠、无新心跳；重开 → 5 秒内恢复
- [ ] 已 Root：15 条保活命令全部 ✔；未 Root：点「检测 Root」12 秒内返回，不 ANR
- [ ] 日志导出、级别切换、清除均正常
- [ ] 深色模式下主页与设置页文字可读

---

## 五、结论

- **核心收款链路（识别 → 金额 → 签名 → 上报）**：v3.3.6 之后逻辑正确，本次未发现新问题
- **可靠性链路（重试 → 前台服务 → 通知）**：本轮找到 3 个真实缺陷，其中 2 个会直接导致丢单，
  1 个在 Android 14 上必崩 —— 已全部修复
- **保活与运行控制**：三版迭代后逻辑闭环，未发现新问题
- **安全与合规**：存在 5 项取舍型风险，均需产品决策，不建议擅自改动默认值

---

## 六、v3.3.8 风险项处置记录

上表 R1~R6 已按项目所有者决策逐项处理，处置结果如下。

| # | 决策 | 落地情况 |
|---|---|---|
| R1 | **不改** | TLS 默认不校验证书保持不变。自签证书是本项目的主要使用场景，默认开启校验会让存量用户升级后直接断连 |
| R2 | **改：加密保存** | 新增 `util/SecurePrefs`，AndroidKeyStore AES-256 + GCM 随机 IV，密文前缀 `v1:`；存量明文读取时自动迁移。覆盖 4 读 2 写共 6 处（审计时记为 5 处，实测为 6 处） |
| R3 | **改：移除权限** | 删除 `MANAGE_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES` / `READ+WRITE_EXTERNAL_STORAGE`；同步删除启动流程里的权限硬门控（否则会 `return` 掉后续检查）。已确认扫码链路（`ACTION_GET_CONTENT` + `openInputStream`）不需要任何存储权限 |
| R4 | **改：加实验开关** | 新增 `AutoRestart`，默认关闭。三重校验：通知监听授权有效、有活跃链路、近 90 秒无推送/心跳。时间戳用 `commit()` 同步落盘避免重启风暴。**明确标注「多数机型无效」**，因为它不能可靠重置系统侧的 uid 配额 |
| R5 | **不改：不动依赖** | 不引入 `androidx.security:security-crypto`。`EncryptedSharedPreferences` 会改写整个 prefs 文件格式，与 `vone.xml` 里已有的几十个键混用风险更大；R2 的「值级加密」已覆盖同一威胁模型且零新增依赖 |
| R6 | **不改：不动代码** | `Splash*` 11 个类逐项核对**全部存在活跃调用**（`App` / `MainActivity` / `LegacySplashScreenHelper`），不是死代码。改造收益（少量包体与启动耗时）低于改崩启动画面的风险 |

### 本轮额外发现并修复（不在原审计清单内）

| 缺陷 | 位置 | 后果 |
|---|---|---|
| `startQrCode(null)` 后首行直接解引用 `v` | `MainActivity.startQrCode` | 相机权限授权成功后**必崩**，扫码功能等于不可用 |
| `decodeUri` 返回 null 时仍 `new RGBLuminanceSource(null)` | `CaptureActivity.scanningImage` | 选到非图片/超大图/URI 失效时**必崩**，而非提示识别失败 |
| 主页扫码后只写 `scheme`，`host`/`key` 从未落盘 | `MainActivity.handleQrCodeResult` | 提示「配置成功」，**重启后变回未配置**，心跳一直用空 host；`saveConfigRealtime()` 全类无调用点，注释里所谓「自动触发实时保存」不成立 |
| `UriUtil` 全类无外部调用且依赖 `MediaStore.DATA` | `util/UriUtil.java` | 死代码，且是唯一残留的存储权限依赖点 —— 已整类删除 |

### v3.3.8 结论

- **权限面**：从「可访问所有文件」收敛到**零存储权限**，短信/联系人/定位等敏感权限本就未申请
- **密钥面**：明文存量已可迁移为 Keystore 加密，备份泄露不再等价于密钥泄露
- **未解决**：R1（TLS 默认不校验）与 R4 的系统侧配额限制均无代码级解法，
  前者是场景刚需，后者需 Android 平台侧支持

