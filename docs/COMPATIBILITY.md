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

### 优酷 `com.youku.phone`

| 版本 | matched | miss | push_notify |
|---|---|---|---|
| 11.2.13 | 8 | 0 | matched |
| 11.1.98 | 8 | 0 | matched |
| 11.1.65 | 8 | 0 | matched |

**结论：11.2.13 → 11.1.65 全版本零 miss**，规则集不随小版本漂移。模块记录的 11.2.15（920）同样高于
豌豆荚可获取的最高版本（917），本轮未在真机复现。

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

## 如何理解兼容结果

“matched”表示能够安装规则；“off”表示该规则是主动撤下（附原因）或在设置里关闭，不是失败。
广告投放、登录状态、缓存和服务端实验可能改变界面。当前不保证所有版本、所有广告路径。
结构不匹配会保留原始界面并写出诊断，而不是为了隐藏入口强行删除导航。

本轮没有耗电对照测试，也没有手机系统重启测试；已完成的是应用进程强停重开、逐版本装包实测和设置持久化验证。
