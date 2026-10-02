# 本轮进展（goal round 1/256）

## 已完成并验证

| 项 | 证据 |
|---|---|
| **开屏以外的广告拦截** | 首页焦点位原本是「奥利奥・广告」横幅，现为自然内容（剧集「全网独播」）。`hit=ad_request_gate outgoing ad request suppressed -> requestId 0` 在 logcat 中实际出现两次。 |
| **设置页开关可点** | 现象「没法改 / 配置服务未连接」已修：设置页不再依赖 `XposedServiceHelper`，改为本机 SharedPreferences + 模块自有 `ConfigProvider` 转发。实测点击后 `/data/data/io.github.qqliveclean/shared_prefs/qqlive_clean.xml` 落盘 `show_short_video=false` / `block_splash_preload` 往返成功。 |
| **logo 换新** | 新图为自绘（圆角渐变方块 + 白色播放三角 + 红色禁止角标），SHA256 与音乐模块图标不同，体积 20KB（原 93KB）。生成脚本 `tools/make_icon.py`。 |
| **LSPosed 事故结案** | 根因 Zygisk Next 1.3.2 缺 zygisk API v4；升级 1.5.0 后 `unsupported api version` 归零、概览「已激活」，模块保持启用。 |

## 本轮新增的广告闸门（逆向证据来自 `scratch-recon/RECON-REPORT.md`）

| 规则 | 位置 | 语义 |
|---|---|---|
| `player_pause_gate` | `rb6.e.a(Context, QAdRequestInfo)`（classes15，**混淆**） | 返回 null=请求广告，非 null=不请求。11 处调用点覆盖 **前贴/中插/后贴/暂停/富媒体角标**。安装期构造一个「不请求」verdict 常量复用，intercept 零分配。 |
| `player_ads`（兜底） | `mediaad.impl.u.v1(AdInsideVideoRequest, boolean)`、`QAdBaseVideoImpl.x0()` | 非混淆名，checker 被改名时接管。 |
| `pendant_ads` | `...push.channel.QAdChannelPendantRequestController.l(List):int` | 返回 105（≠0）阻断底栏/挂件广告请求。 |
| `ad_request_gate` | `qadcore.network.manager.QAdRequestManager.i(?,?):int` | 返回 0，阻断全局广告 JCE 请求（首页焦点图/信息流/卡片广告的主要来源）。**已实测命中**。 |

## 尚未完成 / 已知问题

1. **`config_source` 日志缺失（可观测性缺陷，待修）**
   新构建里 `configure()` 的 `event=config_source=...` / `rule=...status=hooked` / `install_summary`
   在 LSPosed 日志中**看不到**，但同进程的 `hit=ad_request_gate` 能看到 → 说明规则确实装上了，
   是我自己的日志路径有问题（疑似早期启动窗口内 `H.info` 被丢弃）。必须修好，否则以后无法定位失败。
2. **底部标签栏未视觉验证**
   当前前台是 `com.tencent.qqlive/.ona.activity.SplashHomeActivity`，该页面**没有底部标签栏**，
   所以隐藏「短视频」的效果还看不到。需要导航到带底栏的主页面再截图确认。
3. **焦点图/信息流/卡片广告**（`FocusAdBottomSectionController.parse` /
   `UserCenterCardAdSectionController.parse` / `QAdFeedCarPolicyHandler.J`）尚未实现，
   子代理仍在跑 pause/player/feed 三路。
4. Context 获取：`ActivityThread` 三条静态路由在 `onPackageReady` 时**都返回 null**
   （此刻 Application 已 attach 但 `mInitialApplication` 尚未赋值），当前靠
   `Instrumentation.callActivityOnCreate` 兜底成功；`LoadedApk.mApplication` 路由未生效，待查。
5. 未按 skill v0.5.x 统一 `feature=<name> result=matched|partial|miss|off` 报告口径与
   `StatusProvider`/`StatusReceiver` 状态通道。
