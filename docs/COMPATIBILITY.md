# 兼容性与验证范围

验证环境：Android 16（API 36.1）、LSPosed 2.2.0 (7854)、Zygisk Next 1.5.0 (843)、APatch、小米 MI 9。
每个版本都是真机安装对应 APK → 冷启动 → 读模块落盘的逐项兼容报告，下表数字即报告原始计数。

## 逐版本实测

### 腾讯视频 `com.tencent.qqlive`

| 版本 | matched | miss | push_notify | 退化的规则 |
|---|---|---|---|---|
| 9.04.51.32299 | 18 | 0 | matched | — |
| 9.04.11.32026 | 17 | 1 | matched | `splash_modern_master_gate` |
| 9.03.58.31594 | 15 | 3 | matched | 上面一条 + `ad_request_gate`、`feed_autoplay` |
| 9.03.10.31245 | 13 | 5 | matched | 上面三条 + `pause_ad_gate`、`feed_ad_cell` |

**结论：9.04.51.32299 起全量生效；9.04.11.32026 起开屏主闸失效；9.03.58 往下广告请求闸与自动播放失效；
9.03.10 再往下暂停广告和信息流广告位也失效。** `push_notify` 在四个版本上全部挂接成功——它只依赖
`NotificationManager` 平台类，不随 App 改版漂移。

模块记录的验证版本 **9.04.55.32321** 高于豌豆荚可获取的最高版本（32299），该版本号沿用自前序逆向，
本轮**未能在真机上复现**。腾讯视频的 `MIN_VERSION_CODE = 31000` 这个地板略乐观：31245 已经掉到 13/5。

**2026-10-01 真机复现 9.04.55.32321（未登录）**

| 项 | 结果 |
|---|---|
| 报告 | `install_summary` 未打印该进程（启动期日志被后续进程覆盖），逐项 `installs` 显示 `feed_ad_cell=ok` |
| 命中 | `hit=ad_request_gate`（outgoing ad request suppressed）、`hit=mine_ad_card` |
| `feed_ad_cell` 三个闸门 | 用 `tools/dexrefs.py` 在 9.04.55 的 dex 上核对，三处签名全部健在：`ona.ad.universal.g.b(AdFeedInfo)Z`、`ona.ad.b.y(AdFeedInfo)Z`、`ona.ad.feed.a.v(AdFeedInfo)Z` |
| 结论 | 结构完好、已挂接，但未登录会话里首页信息流与播放页下方推荐流都没有渲染出广告格子，因此**本轮拿不到 `hit=feed_ad_cell`**。该规则的命中证据仍停留在 9.04.51.32299；不要把「已挂接」当成「已验证」。 |

### 优酷 `com.youku.phone`

| 版本 | matched | miss | push_notify |
|---|---|---|---|
| 11.2.13 | 8 | 0 | matched |
| 11.1.98 | 8 | 0 | matched |
| 11.1.65 | 8 | 0 | matched |

**结论：11.2.13 → 11.1.65 全版本零 miss**，规则集不随小版本漂移。模块记录的 11.2.15（920）同样高于
豌豆荚可获取的最高版本（917），本轮未在真机复现。

**2026-10-01 真机复现 11.2.15（未登录）**

| 项 | 结果 |
|---|---|
| 报告 | `install_summary hooked=10 miss=0`，`self_test` 通过 |
| 命中 | `youku_mine_carousel`、`youku_mine_vip_promo`、`youku_pause_ad`、`youku_tab_filter` |
| 开屏 | 冷启动未出现开屏广告 |
| 前贴 | **未解决**。一次实测中出现「元气森林」15s 前贴（带「广告」角标），一次实测中直接进入正片 |

### 第六次尝试（2026-10-01）：`AdOrangeConfig` 的按广告位开关——被证伪

优酷的广告 SDK 有两套看起来一样的配置，必须分清楚，否则会重复前五次「挂上了但没人调用」的错误：

| 包 | 角色 | 引用分析结果 |
|---|---|---|
| `com.youku.xadsdk.config.model.*ConfigInfo` | **只是 JSON 模型**（`BannerConfigInfo` / `MidConfigInfo` / `PreConfigInfo`…，每个都有漂亮的 `getEnabled()Z`） | 对 11.2.15 全量 dex 做 `refs` 查询，这些类的 getter **没有任何消费者**。挂上去 14 个开关，播放前贴广告时 **0 命中** |
| `com.youku.xadsdk.config.AdOrangeConfig` | **运行时的活路径**：15 个 `getXxxConfig()`，返回 `j.f1.c8.h.*` 上的开关对象 | 活路上只有 pause(`m.f()`)、pre(`p.e()`/`p.f()`)、splash(`r.f()`~`r.i()`) 有无参 boolean |

因此新增 `youku_ad_switch`：挂 `AdOrangeConfig` 的 15 个 `getXxxConfig()`（类名方法名都不混淆），
在返回值上按「无参 boolean」形状发现开关并置 false——**不硬编码任何混淆名**。
结果：**仍 0 命中**。这些访问器在本轮会话里一次都没被调用，说明优酷的前贴开关并不走这条分支。

**结论（第六次）：优酷前贴广告的广告位开关不是可拦截点。** 前五次 + 本次共六次尝试的共同点在于：
锚点都落在「SDK 读配置/发请求」这一层，而优酷的前贴素材是由快手联盟在**进入播放页之前**就已经
预取/预渲染好的（截图里前贴在详情页打开后数秒内即出现，且 `stc=tk_…` 的 `noPreAd` 只在播放后才上报）。
要拦它只能落在**素材已经就绪之后**的播放决策上，而不是请求之前。`youku_ad_switch` 保留为
「已挂接但未验证」的候选：它零运行时代价（访问器不被调用），但不能算作已生效的闸门。

### 第七次尝试（2026-10-01）：Kubus 事件总线——同样被证伪

优酷播放器内部用的是应用自有的一套事件总线 `com.youku.kubus.Event` / `EventBus`，
**两者都没有混淆**，`PlayerCorePlugin.skipPreAd(Event)` 就是处理总线事件的，
也就是说「跳过前贴」本来就是往总线上发事件——看起来是个理想拦截点。

新增 `youku_preroll_event`：挂 `EventBus.post(Event)` / `post(Event,Object)` / `postSticky(Event)`
共 **3 个重载**，并按**类型**（不是按名字，字段名会变）读出 `Event` 的 3 个 String 字段作为事件名；
命中 `pre_?ad` 系列就整条丢弃并打命中行。结果：**0 命中**——不是「没拦住」，而是钩子一次都没被调用。

两个待查原因（下一轮）：

- XAdSDK 走独立插件 ClassLoader，`com.youku.kubus.EventBus` 可能存在**第二份副本**，
  钩子挂在了播放器实际不使用的那一份上。判据：分别用 `loader` 与插件 ClassLoader 载入后
  比较两个 Class 的身份是否相同。
- 事件名可能不在 `Event` 自身声明的 3 个 String 字段里（探针也依赖这个读取）。

配套的 `youku_event_probe`（对每个去重后的事件名打一条日志）同样 **0 条**，
说明不是「名字没匹配上」，而是钩子根本没触发。

顺带修掉一个真实隐患：最初的匹配是朴素的 `contains("pread")`，而
`threadPrepare` 小写后是 t-h-r-ead-prepare，里面就含 "pread"——会误杀播放器的准备事件。
现改为整串匹配（`pre_?ad` + 已知后缀，可选剥掉 on/send/post 前缀）：宁可漏拦也不误拦，
因为**误拦的表现是界面黑屏而不是报错**，比不拦更难查。

### 第八次尝试（2026-10-01）：只读探针 —— 前七次失败的原因终于找到了

前面七次都是「猜锚点 → 挂上 → 看命中」，猜错了连方向都看不出来。这一次改成**反向做**：
新增只读规则 `youku_preroll_probe`，把 11.2.15 里所有**未混淆的前贴控制点**一次性挂上，
每个方法被调用就打一行（按方法名各持一个去重标记，否则六个探针只会记下第一个）。

挂上的 5 个控制点：

```
rule=youku_preroll_probe status=hooked 5 read-only pre-roll control points:
  AndroidPlayer.initPreAdDuration, AndroidPlayer.getAdCountDown,
  PlayerCorePlugin.skipPreAd, LivePlayerView.onPreAdStart, LivePlayerView.onPreAdEnd
```

同一次会话里**播放器确实在播**（logcat 有 30 条
`DOWNLOADER_LOG ... playback state updated, source:pulse, buffer_in_ms:27880, play_speed:1`），
而这 5 个控制点 **0 命中**。

结论：不是锚点猜错，是**这些会话里压根没有前贴素材下发**，所以前贴代码路径从未被执行。
下面两条解释了素材为什么拿不到。

### 环境阻断（两条都有硬证据，排查时先看这里）

1. **优酷广告交换域名被解析到 127.0.0.1。**

   ```
   System.err: java.net.ConnectException: Failed to connect to adx-data-u1.ubixioe.com/[::]:443
   Caused by: failed to connect to localhost/127.0.0.1 (port 443) ... ECONNREFUSED
   $ ping adx-data-u1.ubixioe.com → PING 127.0.0.1
   $ cat /etc/hosts            → 只有 localhost 两行，本机没有 hosts 规则
   $ ping www.baidu.com        → 183.2.172.177（其他域名正常）
   ```

   即 **DNS 层**把优酷的广告交换域名沉到了 localhost，`/etc/hosts` 干净，与本模块无关
   （`private_dns_mode=hostname`，specifier 是运营商的 `t363292083.6.00p.net`）。
   之前看到的「元气森林」前贴只能来自**已缓存的创意**，这与它反复出现同一个广告的事实一致。

2. **`screencap` 抓不到视频 Surface，播放器停在小窗时主窗口全是黑的。**

   这条把前几轮带进了沟里：`topResumedActivity` 明明是 `DetailActivity`，
   截图却是一整块黑色，于是误以为是模块拦坏了播放器。
   用 logcat 的 HLS 播放状态一查，播放器其实一直在正常播放。
   优酷冷启动会自动恢复**画中画小窗**，此时视频渲在 PiP 窗口里，主窗口就是空的。
   **判据**：看播放状态只能看 logcat，不要看截图。

### 附：本轮的方法论收获（比结论更重要）

**adb 每次往返要十几秒，屏幕会在往返之间掉进 Doze**，于是「改了没生效」和「改动没加载」无法区分，
白烧了好几轮。正确做法是把整条时序写成脚本 `adb push` 到设备端、`adb shell sh` 一次跑完。
解锁必须用 `input keyevent 82` 呼出密码盘再按坐标点按（本机 `input swipe` 上滑无效）。

**对照实验的价值**：本轮详情页反复黑屏，怀疑是模块拦坏了播放器，于是把 `youku_preroll_event`
整个摘掉重新构建、用完全相同的脚本重跑——**照样黑屏**。一次对照就把「模块的锅」排除掉了。
所以黑屏是优酷**冷启动自动恢复画中画小窗（PiP）**导致的应用状态：
`topResumedActivity` 已经是 `DetailActivity`，但主界面是黑的，点海报也打不开可观测的窗口。
这个前置条件不解决，优酷前贴就拿不到任何证据。

11.1.79 **未能测得**：安装成功（versionCode 874），但四次冷启动都在应用自身侧 ANR
（`ActivityManager: ANR in com.youku.phone … failed to complete startup`），模块根本没有获得执行机会，
因此不计入兼容性数据。这是该旧版本在本机的启动问题，不是模块导致。

### 爱奇艺 `com.qiyi.video`

| 版本 | matched | miss | push_notify | 退化的规则 |
|---|---|---|---|---|
| 17.9.2 | 5 | 0 | matched | — |
| 17.5.0 | 4 | 1 | matched | `iqiyi_splash` |
| 17.3.0 | 4 | 1 | matched | `iqiyi_splash` |
| 14.9.5 | 4 | 1 | matched | `iqiyi_splash` |

表中 17.9.2 的 `matched` 是当时的计数。本轮新增了 `iqiyi_ad_api` / `iqiyi_ad_api2`
（真机运行时签名转储，只为重新定位锚点，不做拦截）与 `iqiyi_ad_request`，
所以当前真机报告里 `matched` 会比表上多。

`iqiyi_splash` 的 miss 原因是 `no known ISplashScreenApi implementation has requestAdAndDownload()V`
——实现类换名了，属于锚点漂移而非功能整体失效；开屏之后的首页与个人中心规则仍全部命中。
**结论：开屏规则在 17.9.2 可用，往下到 14.9.5 持续失效；其余规则跨这四个版本稳定。**

**2026-10-01 真机复现 17.9.5（未登录）：开屏广告已真正被拦下**

| 项 | 结果 |
|---|---|
| 报告 | `install_summary hooked=11 miss=0`（改造前为 `hooked=8 miss=2`） |
| 命中 | `hit=iqiyi_splash requestAdAndDownload suppressed on x02.v`，每次冷启动都出现 |
| 实测 | 清空应用数据后首次冷启动，**没有开屏广告**，直接进入首页 |

三处改动与各自的证据：

1. **`iqiyi_ad_request` 的假 miss（bug，不是锚点问题）**：探测 `fw0.a0` 时抛出的
   `ClassNotFoundException` 会跳出循环落进外层 `catch`，把**已经挂好的请求闸**记成 miss。
   改为每个候选各自 try/catch，并把这个类升级为独立的 `iqiyi_ad_reach` 规则，
   报告里能看到 `PumaPlayer(armed), nw0.a0(armed), fw0.a0(absent)`。
   混淆前缀每次发版都换（17.9.2 是 `fw0.a0`，17.9.5 是 `nw0.a0`），候选不存在只应被记录，不该影响别的规则。
2. **开屏实现类重锚**：17.9.2 的 `lz1.v` 在 17.9.5 变成了 `x02.v`（`super=BaseCommunication`，
   `implements ISplashScreenApi`）。与其继续维护混淆类名单，不如用插件框架的公开入口
   `org.qiyi.android.plugin.mm.ModuleFetcher.getSplashScreenModule()`（**未混淆**）在运行时拿到实现对象，
   再在它上面挂 `requestAdAndDownload()V`。
   两条路径同时生效：候选类立刻挂上（保证当前版本有效），getter 作为发版后自动跟上的兜底。
3. **开屏广告素材有本地缓存**：清数据前每次冷启动都播同一条「摇一摇手机」广告，即使请求被拦也照播。
   17.9.5 有 `cache_splash_ad` / `SplashAdCacheManager` / `TYPE_CACHED_SPLASH` 这套缓存，
   请求闸只挡「新素材」，不挡「已缓存的素材」。清一次应用数据即可，之后不再复现。

新增的 `iqiyi_splash_sdk`（联盟 SDK `ya1.i.loadSplashAd(ya1.p, ya1.h)` 逐实现类置空）**已挂接但 0 命中**，
属于冗余层：真正生效的是上面第 2 条。它零代价地留着，但要按「未验证」看待。

## 未登录各界面的广告排查（2026-09-30 实测）

三个 App 全部在**未登录**状态下冷启动逐屏截图核对。「第三方广告」指外部投放（带「广告」标签的
推广卡、开屏品牌广告、播放前贴片）；App 自有的会员促销卡不算广告，本模块也不拦它。

### 腾讯视频 9.04.51.32299 —— 已处理

| 界面 | 现象 | 规则 | 命中 | 结论 |
|---|---|---|---|---|
| 开屏 | 品牌开屏广告 | `splash_modern_master_gate` | 1 | 已清除 |
| 首页顶部 | 推广横幅 | `tencent_home_top_ad` | 1 | 已清除 |
| 首页信息流 | 「广告」标签推广卡 | `ad_request_gate` | 1 | 已清除 |
| 播放页 | 前贴/中贴广告 | `play_strategy`、`hls_midroll` | 0 | 已清除 |

前贴的判定用的是**因果证据**，不是「没看见横幅」：拦截开→ `play_strategy` 与 `hls_midroll` 命中 0；
同一集、同一入口下关闭拦截重跑（对照臂）→ 两者各命中 1，且详情页出现「广告」标签卡。
说明闸门在源头掐断了广告数据，下游消费者根本没被调用。控制臂跑完已删除设置文件恢复默认。

### 爱奇艺 17.9.2 —— 界面已处理，播放页前贴未解决

| 界面 | 现象 | 规则 | 命中 | 结论 |
|---|---|---|---|---|
| 首页顶部 | 会员推广横幅 | `iqiyi_home_top_ad` | 1 | 已清除 |
| 首页信息流 | 「广告」标签卡（抖音精选/越彩神捕/海量漫剧/罗云熙等） | `iqiyi_home_member_banner` | 1 | 已清除 |
| 我的 | 仅自有会员卡 | `iqiyi_mine_banner` | 1 | 无第三方广告 |
| 免费（短视频流） | 无 | — | — | 无第三方广告 |
| 观看历史 | — | — | — | **需登录，本轮不排查** |
| **播放页前贴** | **有**（迪士尼、阿斯塔纳航空、壁纸 App、Friso 等） | 已全部撤下 | — | **未解决，见下** |
| 播放页「广告」标签卡 | 有 | 无规则 | — | **未处理** |

**播放页前贴为什么撤下。** 在广告正在播放的样本上，以下 Java 锚点实测全部 0 命中：

`IAdInvoker.updateCupidAd`、`AdsController.onAdDataSourceReady`、`getAdCountDown`、
`QYPlayerADConfig.checkRegister`、`QYPlayerADConfig.getDefault`、
`PumaPlayer.OnAdPrepared` / `OnAdCallback`、`nx0.a.getCurrentPosition` / `getDuration`、
`AdsClient.requestAd`。

已排除的误判：目标类的 `System.identityHashCode` 与 ClassLoader 均确认来自主 APK
（`dalvik.system.PathClassLoader[DexPathList[.../base.apk]]`），不是子类加载器持有副本；
播放器 SDK 与 `com.mcto.ads` 不在任何插件 APK 内；`ClassLoader.loadClass` 与
`PluginClassLoader.loadClass` 钩子确实不执行（ART 原生解析绕过 Java 层）。

**决定性证据**：logcat 显示 `Debug:OnMctoPlayerCallback` 在广告播放期间执行 6 次，
而对全部 4 个声明该方法的类（`PumaPlayer`、`IMctoPlayerHandler`、
`MediaPlayerHandlerFunctionID`、`fw0.a0`）同时挂钩，命中仍为 0——两次独立样本复现。
广告 HTTP 栈为 **native libcurl**（`q_h_c_d : curl_easy_setopt CURLOPT_URL`），广告决策与播放
都在 native C++（`MctoPlayer`、`[CUPID] HandleHttpResponse`、`libgdtqjs.so`）内完成。

因此这几条规则按模块约定标记为 `withdrawn` 并附原因，**报告中出现 `matched` 不等于广告已被拦**。
要解决爱奇艺前贴，必须换到不依赖 App Java 层的手段（域名/DNS 层阻断，或 native 层 patch），
不属于当前模块的能力范围。

### 优酷 11.2.13 —— 未处理播放页

| 界面 | 现象 | 规则 | 命中 | 结论 |
|---|---|---|---|---|
| 开屏 | 开屏广告 | `youku_splash_cold_switch` | 1 | 已清除 |
| 首页顶部 | 仅自有会员促销 | `youku_home_top_ad` | 0 | 无第三方广告 |
| 首页信息流 | 无「广告」卡 | — | — | 无第三方广告 |
| 淘好片 | 无 | — | — | 无第三方广告 |
| 我的 | — | — | — | **需登录，本轮不排查** |
| **播放页前贴** | 有（海飞丝、清扬、飘柔、三星等，均带倒计时与「广告」标签） | **无任何规则** | — | **未处理，见下** |

优酷的 `youku_ad_slot_gate` 早前已撤除，当前播放页只有 `youku_pause_ad` 与开屏规则。

**优酷前贴为什么做不掉。** 广告本身确认存在：未登录播放同一集，屏幕上是带倒计时、「广告」标签、
「会员可关闭此广告」和「了解详情」的完整前贴。以下锚点与开关都在真机上验证过，全部无效：

| 尝试 | 结果 |
|---|---|
| `j.b1.w3.b.c.d.u(int)Z` | 该类在本版本已不存在 |
| `AdRequestManager` | 只有 a/b/c/d，全是 void，没有决策方法 |
| 改写 `yk_adsdk_syscfg` 所有广告类型 `"enable":1 → 0` | **前贴照播**（105 秒倒计时，截图留证） |
| 改写 `one_ad_config` 的 `"enable_youku_ssp":"1" → "0"` | **前贴照播**（109 秒倒计时，截图留证） |

后两项是直接改 `/data/user/0/com.youku.phone/files/occ_configs/` 下的缓存 JSON 后冷启动同一集验证的；
测试后两个文件都已还原成原值。`yk_adsdk_syscfg` 管的是广告位能力（摇一摇、responsive 模式），
不是是否投放前贴。

另外摸清了取值面：`OrangeConfigImpl` 自身只声明 `a/3` 和 `j/2`，而已生效的暂停广告闸门钩的是
**四参 `a(String,String,String,String)`，来自父类**；播放期间这两个配置都没在上面任何取值方法上被读到，
说明广告配置是由广告插件直接读的。

因此 `youku_preroll_ad` 按约定标记为 `withdrawn` 并附完整原因——**报告里出现 `matched` 不代表广告被拦**。

**前贴其实来自快手联盟 SDK。** 播放期间的 logcat 显示广告不是优酷自己的代码在投，而是**快手联盟
（Kwai Union）广告 SDK**跑在进程里：`com.kwad.sdk.o`、`com.kwad.sdk.commercial.g.a`、
`ksad-sdk_core1` 线程都在活动。第三方 SDK 加载在本进程内，它的类走同一个 ClassLoader，
**这是目前唯一还没被 native 排除的可钩方向**。本轮试探未成功：

- `com.kwad.sdk.o` 只有无参方法（`FG/FH/FI/FJ` 布尔取值、`GE/GF`），整个继承链里**没有 `init`**。
- `com.kwad.sdk.commercial.g.a.i(String,String)` 确实存在并已挂钩，但**前贴播放期间 0 命中**——
  日志里那行 `Long monitor contention ... at ...i(String,String)` 是**锁竞争**，不是广告请求。

顺带修掉一个会误导后续排查的坑：Kwai SDK 的入口方法是 **package-private**，
`getMethods()`（public）看不到它们，只有 `getDeclaredMethods()` 能看到。
`R.findByShape` 走的是 public 路径，所以它对 `i(String,String)` 返回未找到——**这是查找方式的问题，
不是方法不存在**。

`youku_kwad_ad` / `youku_kwad_api` 因此标为 `withdrawn`，**不留下已证伪的钩子**，
避免 `matched` 变成误导。

**第三次尝试：从 APK 里读出真实入口，仍然证伪。**
`dexdump` 优酷 `classes9.dex` 显示快手 SDK 的**对外 API 完全没有混淆**：

```
com.kwad.sdk.api.KsLoadManager (interface)
  void loadFullScreenVideoAd(KsScene, FullScreenVideoAdListener)   ← 全屏视频广告位 = 前贴
  void loadInterstitialAd / loadSplashScreenAd / loadRewardVideoAd
  void loadFeedAd / loadBannerAd / loadDrawAd / loadNativeAd
```

`loadFullScreenVideoAd` 正是前贴的槽位。接口声明是抽象的、libxposed 拒钩抽象方法，
所以改为钩三个具体实现（都是 `PUBLIC FINAL`）：`com.kwad.components.core.b`（路由层）、
`com.kwad.sdk.api.b`、`com.kwad.components.ad.fullscreen.a`。

**结果：闸门挂载成功，但前贴播完倒计时期间 0 命中。** 关掉闸门跑对照（同一集）依然有前贴。

于是可以下一个明确结论：**优酷前贴不是快手的全屏视频广告请求**。
快手 SDK 确实在进程里活动，但它服务的是别的广告位；前贴来自**优酷自己的广告系统**，
走的是本模块尚未定位到的路径。

**第四次尝试：优酷自家广告层（`AdRequestManager`）。**
`dexdump` `classes2.dex` 拿到真实结构：

```
com.youku.oneadsdk.request.AdRequestManager
  void d(int, RequestInfo, j.d1.x3.b.g.f)V
  void c(AdRequestManager$RequestParams)V

j.d1.x3.b.g.f  (广告回调)
  void onFailed(int, String)V          ← 优酷自己屏蔽广告位时调的就是它
  void a(Object, Object, String)V
```

这里纠正了此前一处**错误的结论**：之前记为「`AdRequestManager` 只有 void 方法」，
其实 `d(int, RequestInfo, f)` 正是请求入口。改为拦截后**调用优酷自己的
`onFailed(3, …)` 屏蔽分支**再返回（与腾讯已验证的 `ad_request_gate` 同构），
让播放器走它现成的「无广告」优雅路径。

结果：闸门挂载成功并在**首页信息流命中 1 次**，但**前贴播放期间两个入口都是 0 命中**，
前贴照播到倒计时结束（103 秒、54 秒两个样本）。**前贴请求同样绕开了 `AdRequestManager`。**

`youku_ad_request` 标为 `withdrawn`——注意它与前面几条性质不同：
**不是完全无效，而是「机制成立但未获实证」**。请求确实被拦下并替换成优酷自己的屏蔽分支了，
但没有任何广告被观察到消失，按本模块的取证标准（横幅消失 **且** 闸门命中）不足以宣称它在拦广告，
所以不留在代码里让 `matched` 夸大结论。

至此优酷侧四条路径全部证伪，且每条都是基于真机 dex 签名而非猜测：

| # | 方向 | 结果 |
|---|---|---|
| 1 | 自家配置层 `j.b1.w3.b.c.d` | 类/方法不存在 |
| 2 | Orange 缓存配置改写 | 改为 0 后广告照播 |
| 3 | 快手联盟 SDK（含官方 `loadFullScreenVideoAd`） | 挂载成功但 0 命中 |
| 4 | `AdRequestManager` 两个请求入口 | 首页命中、前贴 0 命中 |

### 第五次尝试：网络层（DNS/hosts）——同样被证伪，且原因是结构性的

前贴的广告判定与创意下载**全部在 native 层**。播放期间 logcat 给出了决定性证据：

```
DOWNLOADER_LOG: [IPcdnDownloadFilter.cpp::IsPreAd:407] pre ad encountered! url:http://vali-g1.cp31.ott.cibntv.net/youku/6910-...
DOWNLOADER_LOG: [NtkDownloadFilter.cpp::CreateRequestCallerAndFillOptions:894] pre ad encountered! url:http://vali-g1.cp31.ott.cibntv.net/...
DnaLog[OpenSourceWrapper] url=https://adsmind.ugdtimg.com/ads_svp_video__*.mp4
```

`IsPreAd` 与 `OpenSourceWrapper` 在**全部 14 个 dex 里都搜不到**（`grep -a 'isPreAd'`、
`'IpcdnDownloadFilter'`、`'OpenSourceWrapper'` 全部无命中）——它们只存在于 `.so` 里，
Java 层无从挂钩。腾讯已验证的那类 Java 闸门在这里没有对应物。

于是尝试 hosts 拦截。创意 CDN 有两个来源，**逐个验证都不成立**：

1. `adsmind.ugdtimg.com`（腾讯优量汇）——实测**拦了也没用**，广告照播，
   因为该域名并非本次前贴创意的实际来源。
2. `vali-g1.cp31.ott.cibntv.net`——这条**结构上不可行**：它既是前贴广告创意的主机，
   **也是正片的主机**（正片同为 `/youku/<id>` 路径）。按主机拦会把正片一起打死。

**结论：优酷前贴无法用本模块现有手段去除。** 它的判定函数在 native 里，
而广告与正片共用同一 CDN，网络层拦截在结构上不成立。留下的唯一线索是
`IsPreAd` 判定所用的 URL 特征（广告流为 `/youku/6910-…`、`/youku/6710-…`，
与正片 ID 有形态差异），要利用它需要对 native 库打补丁，超出 LSPosed 模块的范围。

> 设备 `/system/etc/hosts` 在测试期间通过 bind mount 临时改过一次，**已 unmount 并还原**，
> 原始内容（含 `ota.googlezip.net` 三行）保持不变。

### 更正：播放页下方的广告卡仍未清除

前几轮写「各家未登录界面广告已排查+清除」**不完整**。2026-09-30 23:37 复核腾讯时发现：
**播放页选集列表下方仍有广告卡**——一张 1688 商品广告，带「广告 ▾」披露标签、
「去逛逛」CTA 和「该素材由商品供应商提供」免责声明。截图 `E:\apkhist\t6.png`。

现有规则覆盖不到它，原因有二：

- `feed_ad_cell` 钩的是 `ona.ad.universal.g` 这个**信息流**广告分发器，
  而这张卡挂在播放页的模块化内容区，走的是另一条数据链路。
- 这张卡是 **Jetpack Compose** 渲染的——UI 树里对应节点为
  `com.tencent.qqlive.ona.base.adapt.DetachableComposeView`，没有传统 ViewHolder 可钩，
  所以 `onBindViewHolder` 系那套规则在这里不适用（而且按性能规范本来也不该钩它）。

因此要拦它只能回到**数据层**：找到给这块 Compose 区域供数的广告数据入口。
这与前贴不同——前贴在 `ad_request_gate` 上已经解决，本卡是尚未处理的独立项。

### 播放页广告卡：已找到同构闸门（真机验证待设备恢复）

沿「数据层」往下查，路径是通的，全部基于真机 dex 签名：

1. 先排除两条错误假设：
   - `com.tencent.qqlive.protocol.pb.AdCardPalette` 不是卡片数据源，它只服务
     `AdFeedImageStyleInfo`（信息流图片文案调色板），字段全是 String。
   - `com.tencent.qqlive.protocol.pb.DetailModuleType` 枚举只有 6 个常量
     （`UNSPECIFIED` / `SKP_BASE_INFORMATION` / `SKP_INTRODUCTION` / `COMMENT_WRITE` /
     `MULTI_TAB_EMBED_PANEL` / `MULTI_TAB_FLOAT_PANEL`），**没有广告类型**。
     所以 1688 卡不是详情页模块，而是**下方推荐流里注入的广告单元**。
2. APK 内未集成任何电商广告 SDK（无 alibaba / 1688 / tmall / kwad 包），
   确认该广告由腾讯自家广告系统下发。
3. 正解：全 App 存在**多个同构闸门**，签名完全一致——
   `private boolean(com.tencent.qqlive.protocol.pb.AdFeedInfo)`，返回 true 即丢弃该广告流，
   不生成任何广告单元：

   | 类 | 方法 | 作用面 |
   |---|---|---|
   | `ona.ad.universal.g` | `b/j/k/m(AdFeedInfo)Z` | 首页信息流（此前**唯一**被钩的） |
   | `ona.ad.b` | `y(AdFeedInfo)Z` | 通用广告流 |
   | `ona.ad.feed.a` | `v(AdFeedInfo)Z` | **cell ViewModel 层，播放页广告卡的构建点** |

   `ad.feed.a` 同时有 `(BaseCellVM, AdFeedInfo)` 与
   `(BaseCellVM, AdFeedInfo, BaseSectionController)` 重载，正是渲染单元的 VM 层。

`feed_ad_cell` 已从「只钩 1 个」扩展为「**3 个闸门全钩**」，
安装日志确认：`3 ad-feed gates -> true: b, y, v`。

> ⚠️ **验证未完成**：APK 已构建并安装成功，但随后测试设备断连（adb 无设备），
> 重试 4 次均未恢复，**尚未确认播放页的 1688 卡是否真的消失**。
> 按本模块的取证标准（横幅消失 **且** 闸门命中），
> 在拿到设备截图 + `feed_ad_cell` 命中计数之前，这项**只能算「已实现待验证」**，
> 不能计入成果。

### 同轮复核：腾讯前贴仍然有效

同一轮重跑腾讯，**前贴确认无广告**：点第 01 话后视频直接起播，
无倒计时、无「广告」标签，画面为首帧正文（字幕「大家都抓紧了」）。
闸门 `ad_request_gate` 命中 1 次，**横幅消失 + 闸门命中**两项证据齐备，
符合本模块的取证标准。首页信息流同样干净（两列正常剧集，无横幅、无顶部广告位）。

## 推送通知广告闸门

三个 App 共用 `NotifyGate`，挂在 `NotificationManager` 上。三个 App 上均为 **4/4 入口挂接**
（`notify` 2 参 / `notify` 3 参 / `createNotificationChannel` / `createNotificationChannels`），
`self_test 8/8 passed`。在上述 **11 个实测版本**（腾讯 4、优酷 3、爱奇艺 4）上 **push_notify 全部 matched**——
它只依赖 `NotificationManager` 平台类，不随 App 改版漂移。

自检样本里 **5 条负样本是视频 App 特有的误伤防线**：追剧更新提醒、追剧更新、正在播放、下载完成、
投屏断开。词表刻意不含"播放/观看/继续/追剧/更新/集数/下载/投屏"，渠道表不含 `play`/`media`/`download`。
这三个 App 自身的其他规则已会隐藏"会员"入口，通知侧再拦会员促销属于同一意图，不构成误伤。

自检只证明**判定函数本身**正确（没有真机广告通知样本）。实际拦截条数需在真实收到促销推送后，
从「兼容结果」页或 `adb logcat -s QQLiveClean | grep push_notify` 读 `已拦广告通知 N 条 / 共见到 M 条`。

## 离线定位工具：`tools/dexrefs.py`

前六次锚点失败有个共同根因：**只能靠字符串猜名字**。`tools/dexscan.py` 只能回答「这个名字在不在」，
而锚点真正需要回答的是「谁实现了这个接口」「这个方法的完整签名是什么」「这个类到底有没有人用」。
`dexrefs.py` 直接解析 dex 的索引表（string/type/proto/method/class_data，只读表不解指令），
所以一个 240 MB 的 12-dex APK 约 3 秒扫完：

```
dexrefs.py implementors <apk> <fqcn>      # 谁实现了这个接口
dexrefs.py methods      <apk> <name>       # 哪些类声明了这个方法，完整签名 + 是否 abstract
dexrefs.py hierarchy    <apk> <fqcn>       # 单个类的父类/接口/全部方法
dexrefs.py byreturn     <apk> <fqcn>       # 哪些方法返回这个类型（找未混淆的 getter）
dexrefs.py refs         <apk> <fqcn>       # 谁引用了这个类型（判断「是不是死代码」）
dexrefs.py classes      <apk> <prefix>     # 按包前缀列类
```

本轮靠它拿到三个决定性结论：爱奇艺开屏实现类 `lz1.v → x02.v`、腾讯三处
`boolean(AdFeedInfo)Z` 闸门在 9.04.55 仍然健在、以及优酷 `*ConfigInfo` 是**没有任何消费者的死代码**
（`refs` 查询只有自己的 setter 返回自己）。

判定「挂上了」的证据仍然只有真机 `hit=` 行；这些离线工具只用来决定**该挂哪里**。

## 如何理解兼容结果

“matched”表示能够安装规则；“off”表示该规则是主动撤下（附原因）或在设置里关闭，不是失败。
广告投放、登录状态、缓存和服务端实验可能改变界面。当前不保证所有版本、所有广告路径。
结构不匹配会保留原始界面并写出诊断，而不是为了隐藏入口强行删除导航。

本轮没有耗电对照测试，也没有手机系统重启测试；已完成的是应用进程强停重开、逐版本装包实测和设置持久化验证。
