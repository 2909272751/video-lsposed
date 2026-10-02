# 本轮进展（goal round 9/256）

## 实现：顶部频道栏精简（代码完成，实机未确认）

新增 `ChannelRules.java`：
- hook **`com.tencent.channelnav.impl.r1.C()Ljava/util/List;`**（真实 DEX 名，classes24），
  过滤 `List<PBChannelListItem>`，按 **`.title` 显示名**匹配用户关闭的频道；
- 绝不隐藏「首页」；
- `Method`/`Field` 全部在安装期解析，intercept 内零查找；命中日志只打一次并带 `config_source`。
- 设置项 5 个：`show_channel_master/tv/anime/movie/variety`（大师/电视剧/动漫/电影/综艺），
  默认全部显示（即默认不改动 UI）。

构建通过，产物 `qqliveclean-v0.2.0.apk`。

## 实机验证：**未成功，且原因尚未定位**

做法：用 root 直接把配置写进**目标 App 自己的**缓存
（`/data/data/com.tencent.qqlive/shared_prefs/qqlive_clean_cache.xml`，
置 `_synced=true; show_channel_master=false; show_channel_anime=false`），
再重扫模块并启动腾讯视频。

结果：
- 广告规则全部正常（`config_source=target_cache[ok]`，`play_strategy`/`player_pause_gate`/
  `hls_midroll`/`ad_request_gate` 均命中）→ **说明配置通道与规则安装本身没问题**；
- **`channel_bar` 既无 `hit=` 也无可见的 `rule=` 行**。

无法定性，因为 **LSPosed 会丢弃 Activity 启动窗口内写入的模块日志**（round 2 已证实），
`rule=channel_bar status=miss reason=…` 正好落在该窗口里被丢掉了。

**最可能的三种原因（下轮逐一排除）**：
1. `com.tencent.channelnav.impl.r1` 在安装期用 App ClassLoader 取不到（应能取到，待确认）；
2. `PBChannelListItem.title` **不是可直接反射的字段**（该类**无任何反编译源码**，
   很可能是 Kotlin 属性/带 getter，或真实字段名不同）→ 反射取字段失败即 miss；
3. `r1.C()` 在当前 App 状态（首页已构建/已缓存）下未被调用。

## 下轮做法（明确）
1. **先把可观测性补上**：给 `ChannelRules` 加一条"安装结果"记录，并挂在**首次命中行**上；
   若始终无命中，则改为在 `H.summary()` 里……不可行（同样被丢），
   因此改用 **`H.diag` 延时输出**：在首次 `hit=`（任何规则）时把 `ChannelRules` 的安装结果一起打出来
   ——这样诊断信息就落在日志可靠的窗口内。这是本轮暴露的通用方法论问题。
2. 用 `scratch-recon/dexmethods.py` 直接读 `classes24/22.dex` 的方法表，
   确认 `PBChannelListItem` 的真实字段名与 getter（不要信 jadx 猜测）。
3. 确认哪个变体是活的（`uitls.m0.d()` / `enable_use_concurrent_nav_list`），
   必要时同时 hook 三个实现 `ChannelNavDataManager.A0` / `ChannelNavDataManagerOpt.K0` / `uitls.k.b`。

## 状态
- 已交付 `qqliveclean-v0.2.0.apk`：7 条规则正常 + 广告 4 条实测命中 + 配置送达已打通并持久化 +
  logo 已换 + 设置页可写。**顶部频道栏精简处于"已实现、待验证"**。
- 另一个逆向子代理（首页元素/入口精简/减少预加载）**仍未返回**。
- 目标保持 active。
