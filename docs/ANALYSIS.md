# video-lsposed —— 逆向证据 / 状态 / 下一步

目标：腾讯视频 `com.tencent.qqlive` **9.03.95.31890**（versionCode 31890），Android 16 / arm64-v8a。
框架：libxposed API 102，LSPosed 2.2.0 (7854) + Zygisk Next **1.5.0 (843)**。
模块：`io.github.qqliveclean` v0.1.0，作用域仅 `com.tencent.qqlive`。

---

## 1. 环境事故与根因（已结案）

| 时间点 | Zygisk Next | LSPosed 概览 | 模块 |
|---|---|---|---|
| 事故期 | 1.3.2 (688) | **部分激活 / 系统框架注入失败** | 装或卸都一样 |
| 修复后 | **1.5.0 (843)** | **已激活 ✅** | 已启用，仍正常 |

根因日志（Zygisk Next 拒绝加载 LSPosed 的原生库）：

```
E/zn-module-loader64: unsupported api version 4 of module
  /data/adb/modules/zygisk_lsposed/zygisk/arm64-v8a.so, at most 3
```

- 该 `.so`（2026-08-31）声明 **zygisk API v4**；Zygisk Next 1.3.2 只支持 **≤3** → 注入被拒。
- 失败仅限 **`artd` 服务注入路径**（`/apex/com.android.art/bin/artd`），故表现为「App 可用、系统框架失败」。
- 关键反证：该行在**模块未安装的干净启动**里同样出现（重启后 `grep -c` = 1），而升级 Zygisk Next 后**归零**，LSPosed 变为「已激活」且模块保持启用。→ **与本模块无关**。

**我自己的错误（已停止，勿再犯）**：曾直接写 `/data/adb/lspd/config/modules_config.db`（`modules_state` / `scope`）并用脚本 `kill lspd` 重启。这些行会在 lspd 下次重启时被清掉，是造成「模块反复消失」和运行态不稳的原因。**今后只用 LSPosed 管理器 UI 启用模块**，不碰数据库、不杀 lspd。

---

## 2. 已定位并实现的 hook 点

### 2.1 开屏广告（已装，命中未观测）

`com.tencent.qqlive.qadsplash.splash.u`（classes13.dex）是 QAd 开屏管理器单例（`u.x()`），
三个入口共用同一个总闸：

```java
static boolean F()                 // -> QADServiceHandler.needCloseSplashAd()，结果缓存进静态 Boolean j
void          K(String, String)    // requestPreloadOrder : if (F()) return;
void          N(request, cb)       // requestSplashQAd     : if (F()) return;
boolean       Q(Context)           // start splash        : if (F()) return false;
```

`F() == true` 就是 App 自己的「关闭开屏广告」分支（少儿模式/合规用），所以强制为 true 是**走 App 设计好的无广告路径**，
而不是硬改返回值把启动卡死（旧式做法）。

已装 3 条：`F()->true`、`Q(Context)->false`、`K(String,String)` 吞掉（顺带省掉后台广告单预拉取）。

> **诚实说明**：本机冷启动与热启动观测期内 `hit=splash_gate` **始终未出现**，即该流水线未被走到。
> 腾讯视频的热启动开屏广告有 600 秒后台阈值（`aqad_splash_hot_launch_ad_min_background_time`，默认 600），
> 12 秒的置背景测试不触发。所以**规则已装但端到端拦截效果尚未实测**，不能宣称已验证。

### 2.2 底部标签栏（已装，已解析出混淆访问器）

- `com.tencent.kmm.tabbar.data.model.TabBarItemType$a.f()` → 默认底栏组合，`getDefaultPageType()` 取 pageType（**无混淆**，跨版本稳）。
- `re0.i.o()/p()/n()` → 真正渲染用的 `List<qe0.a>`（**类名与方法名均混淆**，jadx 的 `re0.TabBarData` / `qe0.TabBarItemData` 只是 Kotlin 元数据字符串，**运行时不存在**，必须用 `re0.i` / `qe0.a`）。

pageType 对照（取自 `TabBarItemType` 静态块）：0 首页 · 1 短视频 · 2 VIP会员 · 3 直播 · 4 我的 · 5 热议 · 6 消息 · 8 观看历史 · 9 下载 · 10 搜索 · 11/12/13/14 SKP 系列 · 16 好片 · 17 探片 · 18 筛选页 · 19 少儿模式 · 20 浮层搜索。**pageType 0「首页」永不隐藏**（底栏锚点）。

已装 2 条（`tab_default_list` + `tab_bar_data`），pageType 访问器优先取已知名 `U`，取不到则**结构探测**
（必须是 no-arg int，取值全落在已知 pageType 集合内且出现过 `0`），命中即缓存、失败即 `miss` 并放弃（fail-open）。

### 2.3 实机验证（2026-09-26 12:12，处理器正确启用后）

```
event=prefs_probe source=onPackageReady available=true
event=splash_manager_found class=com.tencent.qqlive.qadsplash.splash.u
rule=splash_gate       status=hooked F()Z -> true
rule=splash_start_gate status=hooked Q(Context)Z -> false
rule=splash_preload    status=hooked K(String,String)V suppressed
event=tab_filter_armed hidden=[短视频] debugLog=true
rule=tab_default_list  status=hooked
event=tab_accessor rule=tab_bar_data source=preferred name=U on qe0.a
rule=tab_bar_data      status=hooked re0.i filtered on 3 list accessor(s)
event=install_summary hooked=5 miss=0
```

子进程正确跳过：`event=skip_subprocess process=com.tencent.qqlive:k_a` / `:cache`。

---

## 3. 未解决 / 待办

### 3.1 设置页开关点不动（已改，待重启验证）
现象：开关显示但点不动。原因：`App.preferences()` 为 null（`onServiceBind` 从未回调），旧代码据此
`setEnabled(false)` 把整页禁用。
已改为**始终可点**，写入失败时回弹并 toast 说明原因；状态卡显示具体理由。

**为什么 binder 没到**：`XposedServiceHelper` 依赖 lspd 调用本模块 provider 的
`call("SendBinder", extras{“binder”})`（见 `XposedProvider.call`）。实测 lspd **没有**向本模块或参考模块
（`io.github.qqmusicclean`）的自身进程推送 binder（两者 `grep -c "(pkg)"` 均为 0），且两者 DB 状态完全一致
（`enabled=1, scope_request_blocked=0`，各一条 scope）。推测 binder 在**开机时**下发，因此
**需在模块已启用状态下重启手机一次**再验证；这是待办第 1 项。

若重启后仍不回调，改为 skill v0.5.x 的 **模块自有 `StatusProvider`（exported，ContentProvider.call 只读）**
方案，彻底摆脱 `XposedServiceHelper` 依赖。

### 3.2 开屏广告以外的广告全部未处理（用户已指出，已复现）
实机截图可见首页**焦点图/轮播广告**（奥利奥，角标「广告」）仍在。候选闸门（**待逐条逆向确认后才可写**）：

| 广告族 | 候选位置（来自类名普查，**未验证**） |
|---|---|
| 首页焦点图/轮播 | `com.tencent.qqlive.ona.ad.universal.PosterFocusAdCell` / `PosterFocusAdView` |
| 信息流卡片 | `com.tencent.qqlive.ona.ad.v2.feed.AdFeedFlowPosterAdCell` / `AdFeedFlowVideoAdCell` / `AdFeedKMMCell` |
| 底栏广告 | `com.tencent.qqlive.ona.ad.universal.focus.bottom.FocusBottomAdCell` |
| 我的页卡片 | `com.tencent.qqlive.ona.ad.universal.UserCenterCardAdSectionController` |
| 循环卡/多播放器 | `com.tencent.qqlive.ona.ad.v2.cyclecard.AdFeedCycleCardCell` |
| 暂停广告 | `com.tencent.qqlive.ona.ad.carpolicy.IPauseAdCarPolicyBridge` |
| 贴片/播放中 | 待定位（`com.tencent.qqlive.ona.ad.qmtplayer.*`） |
| 统一选单/频控闸 | `com.tencent.qqlive.ona.ad.*.carpolicy.*` / `QAdCarRequest` |

**原则**：优先找「统一入队/选单闸门」（一次性决策），不要 hook `onBindViewHolder`/`onDraw`/动画帧，
也不做视图树遍历兜底。

### 3.3 其他
- 尚未按 skill v0.5.x 统一 `feature=<name> result=matched|partial|miss|off` 报告与逐项状态上报通道。
- 混淆名漂移的 DexKit 指纹回退未实现（当前靠结构探测兜底）。
- `README.md` / `NOTICE` 未写（无第三方库，故暂不需要 licenses）。

---

## 4. 复现命令

```powershell
# 构建
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\实用软件开发\video-lsposed\app\build.ps1"

# 装 + 触发 + 看日志（E1：模块日志挂在 LSPosedFramework tag 下，-s 模块名永远为空）
& C:\Android\adb.exe install -r "C:\实用软件开发\video-lsposed\app\dist\qqliveclean-v0.1.0.apk"
& C:\Android\adb.exe shell am force-stop com.tencent.qqlive
& C:\Android\adb.exe shell monkey -p com.tencent.qqlive -c android.intent.category.LAUNCHER 1
& C:\Android\adb.exe shell "su -c 'grep -h qqliveclean /data/adb/lspd/log/modules_*.log | tail -30'"
```

辅助脚本（已推到 `/data/local/tmp/`，源码在 `tools/`）：`baseline.sh` `env_check.sh` `set_prefs.sh`
`show_log.sh` `restart_lspd.sh`（**仅 emergency，勿常用**）`providers.sh` `svc_diff.sh`。

逆向工具（`tools/`）：`dexscan.py`（raw dex 字符串扫描）· `dexclasses.py`（**真实 class_defs**，用来排除
Kotlin 元数据假类名）· `whichdex.py` · `srcgrep.py`。
