# 本轮进展（goal round 18/256）

## ★ 跨版本锚点体检：19 个锚点，17 个存活，2 个漂移

用新机的 APK（`qqlive-9.04.55.32321`，303,651 个真实声明类）逐条比对旧机版本
（`9.03.95.31890`，277,366 类），结果如下：

| 规则 | 9.03.95 | 9.04.55 | 锚点类 |
|---|---|---|---|
| splash_gate | yes | **yes** | `com.tencent.qqlive.qadsplash.splash.u` |
| play_strategy | yes | **yes** | `com.tencent.qqlive.tvkplayer.api.TVKPlayerVideoInfo` |
| player_ads | yes | **yes** | `com.tencent.qqlive.mediaad.impl.u` |
| player_ads_alt | yes | **yes** | `com.tencent.qqlive.mediaad.impl.QAdBaseVideoImpl` |
| hls_midroll | yes | **yes** | `…playerplugin.impl.utils.HLSAdCalculateUtils` |
| pendant_ads | yes | **yes** | `…push.channel.QAdChannelPendantRequestController` |
| feed_ad_cell | yes | **yes** | `com.tencent.qqlive.ona.ad.universal.g` |
| ad_request_gate | yes | **yes** | `…qadcore.network.manager.QAdRequestManager` |
| reduce_preload | yes | **NO** | `l81.p` → 新版本只剩 `l81.a/b/c` |
| channel_bar | yes | **yes** | `com.tencent.channelnav.impl.r1` |
| channel_bar_tab_v1 | yes | **yes** | `…impl.ChannelNavDataManager` |
| channel_bar_tab_v1opt | yes | **yes** | `…impl.ChannelNavDataManagerOpt` |
| channel_bar_tab_v2 | yes | **yes** | `com.tencent.channelnav.uitls.k` |
| channel_blacklist_probe | yes | **yes** | `com.tencent.channelnav.uitls.q` |
| channel_model | yes | **yes** | `…protocol.pb.PBChannelListItem` |
| tab_item_type / companion | yes | **yes** | `…kmm.tabbar.data.model.TabBarItemType[$a]` |
| tab_bar_data | yes | **NO** | `re0.i` → 新版本只剩 `re0.a/b/c/d/e` |
| tab_item_data | yes | **yes** | `qe0.a` |

**结论：17/19 存活，2 个因混淆名漂移丢失（`l81.p`、`re0.i`）。**

### 这说明了什么
1. **非混淆锚点全部存活** —— 8 条广告规则 + 频道栏那套（`channelnav.*`）在新版本里一个都没丢。
   也就是说**广告拦截在新机上应当照常工作**，这正是"不用 versionCode 锁死"的价值。
2. **漂移的恰好是两个混淆名单字母类**：
   - `re0.i`（底栏数据类）→ `re0.a/b/c/d/e`，**而底栏规则本轮已撤下，暂不影响**；
   - `l81.p`（预载配置类）→ `l81.a/b/c`，会让 `reduce_preload` 在新机上落成 `miss`
     ——**这是设计内的优雅降级**：只丢这一条，其余照常，并在安装表里给出 reason。
3. 这正是我在 round 11 建立"安装表"机制要换来的东西：**版本升级不再表现为"模块悄悄失效"，
   而是一张逐条清单**。

## 待办

1. **重建 `reduce_preload` 锚点**：新版本候选为 `l81.a` / `l81.b` / `l81.c`，
   应改为**多变体回退**（各自独立 id，命中即用），与 `channel_bar_tab_v*` 同一套路。
   （本轮尝试用 `scratch-recon/dexmethods.py` 读这三个类的方法表时无输出，
   该工具的调用方式需先确认——这是下轮第一步。）
2. **底栏精简重做**：必须先把"不删条目、改为按项隐藏"或"只过滤默认组合"做出来，
   并在 9.04.55 上实测"隐藏一个 tab 后底栏仍在"，才可重新启用（round 17 已撤下）。
3. 新机上确认模块已启用后，取第一张安装表作为**跨版本实测基线**。

## 状态
- 交付物 `qqliveclean-v0.2.0.apk`（底栏规则已撤下；其余规则不变）。
- 目标保持 active。
