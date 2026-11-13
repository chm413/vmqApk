# V免签 v3.3.1 发布说明

> 发布日期：2026-11-13
> 发布 tag：V3.3.1
> Release 地址：https://github.com/chm413/vmqApk/releases/tag/V3.3.1

## 🎨 修复：深色模式下文字几乎不可见

### 问题现象
开启系统深色模式后，主页仪表盘与设置页的部分文字看不清，状态信息基本读不出来。

### 根因（四处叠加）

| # | 位置 | 问题 | 深色下对比度 |
|---|---|---|---|
| 1 | `MainActivity.refreshStatus()` | 心跳/监听状态用 `R.color.md_theme_light_*` 取色，这是**固定浅色常量**，不随主题变化 | 1.83 ~ 2.66 : 1 |
| 2 | `SettingsActivity.refreshShizukuStatus()` | 同上 | 1.83 ~ 2.66 : 1 |
| 3 | `activity_main.xml` / `activity_settings.xml` | 根布局背景硬编码 `@color/backgroundColor`（固定 `#FFFBFE` 白），深色下页面仍是白的，而文字是 `?attr/colorOnSurface`（浅灰） | 1.26 : 1 |
| 4 | `values-v31/themes.xml` | 整份复制了 `Theme.VMQ` 却漏掉 `windowLightStatusBar`，Android 12+ 状态栏图标是白色、背景也是白色 | 状态栏不可见 |

WCAG AA 对正文的要求是 **4.5:1**，上述四处全部不达标。

### 修复内容

1. **取色改为主题属性**：`ContextCompat.getColor(this, R.color.md_theme_light_*)`
   → `MaterialColors.getColor(view, R.attr.colorPrimary / colorError / colorOnSurfaceVariant / colorOnSurface)`
2. **页面背景改为主题属性**：`@color/backgroundColor` → `?attr/colorSurface`
3. **Toolbar 文字**：`@android:color/white` → `?attr/colorOnPrimary`（深色下 `colorPrimary` 是浅紫，白字对比度只有 1.70:1）
4. **主题结构重构**：抽出 `Theme.VMQ.Base` 承载配色与窗口属性，`values` / `values-night` 各定义一份，
   `values-v31` 只叠加 SplashScreen 属性。这样以后不会再出现「复制主题时漏属性」。
5. **字号上调**：说明文字 12sp → 13sp，状态次要信息 13sp → 14sp。

### 修复后对比度（实测计算，WCAG 2.1 相对亮度公式）

| 场景 | 前景 / 背景 | 对比度 | 等级 |
|---|---|---|---|
| 浅色 正文 | `#1C1B1F` / `#FFFBFE` | 16.71:1 | AAA |
| 浅色 次要 | `#49454F` / `#FFFBFE` | 9.11:1 | AAA |
| 浅色 卡片内正文 | `#1C1B1F` / `#E7E0EC` | 13.27:1 | AAA |
| 浅色 卡片内次要 | `#49454F` / `#E7E0EC` | 7.24:1 | AAA |
| 深色 正文 | `#E6E1E5` / `#1C1B1F` | 13.27:1 | AAA |
| 深色 次要 | `#CAC4D0` / `#1C1B1F` | 10.05:1 | AAA |
| 深色 卡片内正文 | `#E6E1E5` / `#49454F` | 7.24:1 | AAA |
| 深色 卡片内次要 | `#CAC4D0` / `#49454F` | 5.48:1 | AA |
| 深色 Toolbar 标题 | `#381E72` / `#D0BCFF` | 7.71:1 | AAA |

## 🔧 其他

- versionCode 4 → 5，versionName 3.3.0 → 3.3.1
- 无新增依赖，无权限变更，无 API 变更

## ⚠️ 升级说明

- 同签名覆盖安装（vmqfox.keystore）
- 仅界面配色与字号调整，**心跳、推送、Shizuku 保活等逻辑零改动**

## 📋 验证清单

- [ ] 系统浅色模式：主页仪表盘、设置页文字清晰，Toolbar 标题可见
- [ ] 系统深色模式：心跳「在线/离线/等待」三态文字清晰可辨，不再与背景糊在一起
- [ ] 深色模式：页面背景为深色（不是白色），卡片与页面有层次
- [ ] Android 12+ 浅色模式：状态栏时间/电量/信号可见
- [ ] Android 12+ 深色模式：状态栏图标为白色
- [ ] 设置页说明文字（心跳退避说明、保活说明）字号不小于 13sp
- [ ] 切换深浅色后无需重启应用，界面立即正确

---

**相关文档**：[RELEASE_NOTES_v3.3.0.md](RELEASE_NOTES_v3.3.0.md) · [README.md](README.md)
