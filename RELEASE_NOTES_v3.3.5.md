# V免签 v3.3.5 发布说明

> 发布日期：2026-11-15
> 发布 tag：V3.3.5
> Release 地址：https://github.com/chm413/vmqApk/releases/tag/V3.3.5

这是一个**纯缺陷修复版**，不含新功能。v3.3.4 上线后对运行总闸、提权通道、线程生命周期做了一轮逐行复查，
找到 6 个真实缺陷，其中 2 个会导致线程永久挂死、1 个会直接闪退。

**建议所有 v3.3.4 用户升级。**

---

## 🔴 修复 1：推送耗时长时闪退（`WakeLock under-locked`）

v3.3.0 之前就有，v3.3.4 复查时发现。

`appPush()` 先 `acquire(5000)` 申请电源锁（5 秒后系统自动释放），网络回调里再显式 `release()`。
只要后台重试导致请求超过 5 秒，锁已经被系统放掉了，此时 `release()` 会抛：

```
java.lang.RuntimeException: WakeLock under-locked WakeLock
```

而这段代码跑在主线程（`handler.post`）—— 直接闪退。**断网时超过 5 秒是常态，所以这条路径很容易被踩到。**

修复：

- 持有时间 5 秒 → 15 秒
- `setReferenceCounted(false)`：重复 `acquire` 不累加引用，一次 `release` 就能放开
  （否则 acquire 两次只 release 一次，锁会永远留着不放，变成耗电黑洞）
- `release()` 前先判 `isHeld()`，并整体 `try/catch` 兜住厂商 ROM 的计数不一致

---

## 🔴 修复 2：`onListenerConnected` 重复回调留下永久挂死的线程

系统重绑、`requestRebind` 成功后再次回调，`onListenerConnected` 可能被**连续调用两次**。
此前的实现是直接覆盖 `newThread` 字段：

```java
newThread = new Thread(...);   // 旧线程的引用就这么丢了
```

如果旧线程此刻正挂在「上报已关闭」的 `wait()` 上，就再也没人能唤醒它 ——
字段已被换成新线程，`onListenerDisconnected` 也只 `interrupt` 新线程。
结果是**一个永久阻塞在锁上的僵尸线程**，每次重绑漏一个。

修复：`retireThread()` 在覆盖字段前先 `interrupt` 旧线程，且**字段置空必须在 interrupt 之前**：

```java
Thread previousHeart = newThread;
newThread = null;               // 先让旧线程的 newThread == currentThread() 自我校验失败
retireThread(previousHeart, "心跳线程");   // 再把它从 wait() 里拽出来
newThread = new Thread(...);
```

顺序反了会失效：旧线程从 `wait()` 返回后先查循环条件，字段若还是它自己就会若无其事再跑一轮，
等字段被换掉之后又成了孤儿。

心跳线程与存活监控线程两处都改了。

---

## 🟠 修复 3：`RunSwitch` 的 `wait()` 加了 5 分钟安全阀

原来是无超时的 `LOCK.wait()`。当前所有开关写入都走 `RunSwitch` 的 setter（必然伴随 `notifyChanged()`），
所以现状是安全的；但只要将来有人新增了「定时暂停」之类**直接写 `SharedPreferences`** 的路径，
等待中的线程就会永久挂死，既不恢复也没人叫醒。

改成 `LOCK.wait(SAFETY_VALVE_MS)`，5 分钟自行醒来复查一次状态再继续等。

代价可以忽略：`Object.wait(timeout)` 在 CPU 进入 suspend 时定时器不被服务，
**不会把设备从 Doze 里唤醒**，只在本就醒着时由内核调度一次 —— 相对心跳 30 秒一次是两个数量级的差别。

---

## 🟠 修复 4：Root 探测并发弹两个授权框 + 超时被当成"没有 Root"

两个问题：

**① 并发探测。** 存活巡检线程和设置页的「检测 Root」可能同时 fork 两个 `su`，
Magisk 会连弹两个授权框（用户点掉一个还有一个），两次 12 秒探测还互相叠加。
现已用 `PROBE_LOCK` 串行化，后到者直接命中前者的结论。

**② 超时被误缓存 60 秒。** "超时"不等于"没有 Root" —— 它多半意味着授权框刚弹出、用户正在找按钮。
按 60 秒缓存的话，用户 30 秒后点了允许，接下来半分钟里所有保活命令仍然全 ✘，
表现就是"明明授权了却不生效"。现改为三档：

| 结论 | 缓存时长 |
|---|---|
| 明确可用（`uid=0` 且 `exit=0`） | 60 秒 |
| 明确不可用（su 不存在 / 被拒绝） | 60 秒 |
| **超时（不知道）** | **15 秒** |

顺带修了 `rootCheckedAt` 记的是探测**开始**时间的问题：一次 12 秒的探测会让缓存在写下去时就已过期 12 秒。

**失效机制不用锁。** `isRootAvailable()` 持锁最长 12 秒，而 `invalidateRootCache()` 会被设置页在主线程调用
（切换执行通道），抢锁就等于让界面卡 12 秒 → ANR。改用「代号（generation）」：
探测前后比对代号，中途被作废就丢弃自己的结论。

---

## 🟠 修复 5：`execRoot` 可能因管道缓冲写满而互相死等

原来是先 `waitFor()` 再读输出，注释里写的前提是"本应用的命令输出只有一两行，不会写满管道缓冲"。
**这个前提不成立**：管道缓冲区只有约 64KB，`pm list packages`、`dumpsys` 这类命令轻松超限，
子进程会阻塞在 `write` 上等我们读走，而我们又在等它退出 —— 双方互等到 12 秒超时，
命令被误判成"未授权"。

改成**先起读线程、再 `waitFor`**（进程退出后最多再 join 1 秒收尾）。
当前下发的命令集确实不会超限，但这是一颗定时炸弹，趁早拆掉。

---

## 🟡 修复 6：`isRunning` 改为 `volatile`

写在主线程（`onListenerConnected` / `onListenerDisconnected`），读在两条后台线程。
此前靠 `interrupt` / `notifyAll` 的锁语义"顺带"保证可见性 —— 一旦某条路径只置标志不唤醒，线程就看不到变化。

---

## 升级说明

- 覆盖安装即可，**无需卸载**，配置和日志保留
- `versionCode` 8 → 9，`versionName` 3.3.4 → 3.3.5
- 本版无新增权限、无新增开关、UI 无变化

---

## 验证清单

### 闪退修复（修复 1）

- [ ] 开飞行模式后立刻用收款码付一笔（让推送走失败重试路径）
- [ ] 等 15 秒以上，确认 App 没有闪退
- [ ] 日志里应有 `推送失败: ...`，但没有 `WakeLock under-locked`

### 线程泄漏修复（修复 2）

- [ ] 关闭「心跳上报」让心跳线程进入休眠
- [ ] 在系统设置里关掉再打开「通知使用权」，触发服务重绑
- [ ] 反复 3 次，日志里应出现 `心跳线程 已存在，先停旧线程再重启`，且**只出现一次心跳线程启动**
- [ ] 全开开关后心跳频率正常（`adb shell dumpsys cpuinfo | grep vone` 的占用不应异常升高）

### Root 探测修复（修复 4）

- [ ] 未 Root：点「检测 Root」，12 秒内返回不可用，界面不卡死
- [ ] 已 Root 但清空了 Magisk 授权：点「检测 Root」，**授权框弹出后立刻点允许** → 15 秒内再点一次，应立即显示已授权
- [ ] 连点两次「检测 Root」，确认只弹出一个授权框

### 安全阀（修复 3）

- [ ] 关闭总开关，`adb shell ps -A | grep vone` 确认进程仍在
- [ ] 保持关闭 10 分钟，日志中不应出现任何新的心跳或存活检测记录
- [ ] 重新打开总开关，5 秒内应看到 `心跳上报已开启，线程恢复`
