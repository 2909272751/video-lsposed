# 本轮进展（goal round 2/256）

## 关键突破

### 1. 配置通道打通（`config_source=module_provider`）
之前 `hit=` 行显示 `config_source=remote_preferences provider_error=IllegalArgumentException:
Unknown authority io.github.qqliveclean.config` —— 即 **Android 11+ 包可见性**挡住了跨应用
ContentResolver（只有调用方 manifest 能声明 `<queries>`，而目标 App 的 manifest 我改不了）。

处置：改为**双通道 + 可自证**
1. **文件通道（首选，无 IPC）**：设置页把配置写到
   `/storage/emulated/0/Android/media/io.github.qqliveclean/qqlive_clean.conf`
   （`Android/media` 就是为跨应用共享而存在的目录）；hook 侧用纯文件 IO 读，**不需要 Context，也不受可见性限制**。
2. **Provider 通道（保留）**：某些 ROM 允许时可用。
3. LSPosed RemotePreferences（只读兜底）→ 4. 内置默认值。

最新实测已变为 `config_source=module_provider`，即用户设置真正送达被 hook 进程。
文件内容实测已落盘（含 `show_short_video=false`）。

### 2. 广告拦截实测命中（不再是「装了但不知道有没有用」）
```
hit=player_pause_gate ad request refused (pre/mid/post-roll + pause + corners) config_source=module_provider
hit=ad_request_gate   outgoing ad request suppressed -> requestId 0            config_source=module_provider
```
- `player_pause_gate` = `rb6.e.a(Context, QAdRequestInfo)`，一处覆盖**前贴/中插/后贴/暂停/富媒体角标**。
- `ad_request_gate` = `QAdRequestManager.i(?,?) -> 0`，全局广告请求。
- 首页焦点位「奥利奥・广告」横幅已由自然内容取代（round 1 已截图确认）。

### 3. 日志可观测性修复（重要方法论）
**LSPosed 会丢弃在 Activity 启动窗口内写入的模块日志**：`configure()` 里的 `config_source` /
`rule=...status=hooked` / `install_summary` 全部看不到，但同一进程稍后产生的 `hit=` 行能看到，
导致「规则明明装上了却像没装」。
处置：新增 `H.configSource` + `H.hit(rule, detail, once)` —— **把配置来源与失败原因挂在首次命中行上**，
因为命中行产生在日志可靠的窗口内。这一条让「配置有没有送达」从此可证。

附带发现：`android.util.Log` 在被 hook 进程里也会被 LSPosed 重定向进它自己的日志文件，
所以 `logcat -s QLCDiag` 永远为空 —— 诊断必须去
`/data/adb/lspd/log/modules_*.log` 里看（`adb logcat -d` 对模块日志无效）。

### 4. 模块重装的运维要点
`adb install -r` 会改变 APK 路径，lspd 缓存的 `apk_path` 可能滞后并**静默不加载模块**（连 `module_loaded` 都没有）。
实测：重装后先打开一次 LSPosed 管理器触发重扫，模块即恢复加载。用户更新模块后若发现失效，
应先在管理器里点一次模块或重启。

## 仍未完成

1. **底部标签栏视觉确认**：前台始终是 `com.tencent.qqlive/.ona.activity.SplashHomeActivity`，
   该页面**没有底部标签栏**，所以「隐藏短视频」看不到效果。需要导航到带底栏的主页面再截图。
   （`tab_default_list` / `tab_bar_data` 均 status=hooked，但 `hit=` 尚未出现 → 该页确实没建底栏。）
2. **feed/焦点图下方/用户中心卡片广告**（`FocusAdBottomSectionController.parse`、
   `UserCenterCardAdSectionController.parse`、`QAdFeedCarPolicyHandler.J`）未实现；子代理三路仍在跑。
3. **与音乐模块对齐的剩余功能**：首页频道/元素逐项精简、入口精简（听歌识曲/福利类）、减少预加载。
4. **skill v0.5.x 报告口径**：尚未统一为 `feature=<name> result=matched|partial|miss|off`，
   也还没有 `StatusProvider`/`StatusReceiver` 跨进程状态上报通道。
5. `onPackageReady` 时 `ActivityThread` 三条静态路由仍返回 null（Application 已 attach 但
   `mInitialApplication` 未赋值）；当前靠 `Instrumentation.callActivityOnCreate` 兜底成功。
   `ContextFinder` 里 `LoadedApk.mApplication` 路由未生效，待查。

---

## 补充（round 2 续 / 子代理最终报告落地）

### 新增并已实测命中的规则
以子代理最终报告（`scratch-recon/RECON-REPORT.md`）的排名前三位为准：

| 规则 | 位置（真实类名） | 实测 |
|---|---|---|
| `hls_midroll` | `com.tencent.qqlive.modules.vb.playerplugin.impl.utils.HLSAdCalculateUtils.parseHLSAdList(TVKNetVideoInfo)` → 空 list | **已命中**：`hit=hls_midroll in-stream HLS mid-roll breaks suppressed` |
| `play_strategy` | `com.tencent.qqlive.tvkplayer.api.TVKPlayerVideoInfo.addAdParamsMap(String,Object)` → 强制 `PLAY_STRATEGY=NO_AD_REQUEST`（**App 自己的官方抑制开关**，非混淆） | 已装，未命中（当前播放场景没走这条） |
| `feed_ad_cell` | `com.tencent.qqlive.ona.ad.universal.g.m(AdFeedInfo)Z` → true（一处覆盖信息流/焦点图/卡片/底栏广告 cell） | 已装，未命中 |

`player_pause_gate`、`ad_request_gate` 持续命中（见 hits）。

### 仍未解决：配置送达（本轮唯一硬阻塞）
日志同时暴露两件事：
- 文件通道 `Android/media` 也没读成（`channel_error` 被 provider 的错误覆盖，需要拆分错误字段才能确认是权限还是路径问题）；
- provider 通道被 Android 11+ 包可见性挡住：`IllegalArgumentException: Unknown authority io.github.qqliveclean.config`。

结果：`config_source=remote_preferences` → 走的是设置页**写不了**的后备通道 → **用户开关仍不生效**。

**下一轮方案**：改用 skill v0.5.x 明确记载的跨进程广播通道
（设置页 → 目标 App 动态注册的 receiver，`setPackage` + API 34+ 必须
`BroadcastOptions.makeBasic().setShareIdentityEnabled(true)`），
hook 侧收到后写进**目标 App 自己的** SharedPreferences 缓存，下次启动直接读本地缓存。
方向可行是因为：设置页 manifest 里有 `<queries><package com.tencent.qqlive/>`（**发得出去**），
而目标 App 读自己的 prefs 不需要任何跨应用权限。

### 子代理给出的两条工具陷阱（已在本仓库踩到）
1. `tools/whichdex.py` 只做原始字节子串匹配，会把「引用该类的 dex」都报出来，**不是**定义 dex 工具；
   要判断定义 dex 得解析 `class_defs`（子代理产出 `scratch-recon/dexfind.py`）。
2. PowerShell `Select-String -Quiet` + 锚定正则 会给出**假阴性**；验证 `realclasses.txt` 请用 `grep` 工具。