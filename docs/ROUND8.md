# 本轮进展（goal round 8/256）

## 1. ★ 配置送达彻底打通并实机验证（跨越 3 轮的硬阻塞解除）

设置页 → Intent extra → 腾讯视频被拉起 → hook 写入**腾讯视频自己**的 SharedPreferences：

```
hit=player_pause_gate  … config_source=target_cache[ok]
hit=hls_midroll        … config_source=target_cache[ok]
hit=ad_request_gate    … config_source=target_cache[ok]
```

**并且强停后"正常启动"（完全不经过设置页）依然是 `config_source=target_cache[ok]`** ——
证明已彻底脱离所有跨进程通道（Provider / 文件 / RemotePreferences 都不再需要）。

落地证据（目标 App 私有 prefs）：

```
/data/data/com.tencent.qqlive/shared_prefs/qqlive_clean_cache.xml
  block_player_ads=true   block_splash=true   debug_log=false
  show_vip=true  show_live=true  show_gallery=true  show_doki=true …
```

**额外发现（OEM 门）**：ColorOS 首次会弹「"腾讯视频精简"想要打开"腾讯视频"」，
需用户选「30天内允许」/「仅本次允许」。**这是 ColorOS 关联启动限制，manifest 绕不过**，
必须写进设置页文案（下轮实现）。

## 2. ★ 顶部频道栏闸门找到，而且 App 自带"隐藏频道"开关

子代理报告：`scratch-recon/TOP-CHANNEL-BAR-GATE.md`

- 顶部频道栏是 **`com.tencent.channelnav.*`**，**整包在 classes24.dex**（此前无 jadx 树，子代理新建了）。
- **App 自带黑名单机制**：远端配置键 **`black_channel_list`**，由
  **`com.tencent.channelnav.uitls.q.y()Ljava/util/List;`** 唯一提供；
  V1(`ChannelNavDataManager.A0`)、V1-Opt(`ChannelNavDataManagerOpt.K0`)、V2(`uitls.k.b`) 三套实现**共用这一个 getter**。
  → 隐藏频道 = 往这个 list 里加 id，**用 App 官方过滤路径**，零列表手术，一处覆盖三个变体。
- 备用：**`com.tencent.channelnav.impl.r1.C()Ljava/util/List;`** = 顶栏 navList 外观（覆盖三实现），
  可按 `.title`（显示名）过滤——这是**唯一能按"大师/动漫/电影"这种名字**隐藏的入口
  （黑名单只按 id 匹配，而频道 id 是服务端下发，dex 里**没有静态 id→名字映射**）。
- 已证伪、别再追：`com.tencent.qqlive.tabsdk.*` **不是**频道栏（它是 AB 测试配置 SDK）；
  `modules.vb.videodatacenter.data.HomeTabData` 不是 tab 列表；`kmm.tabbar.*` 是**底部**栏
  （与 round 4 的 uiautomator 结论一致）。
- **陷阱**：jadx 把 `com.tencent.channelnav.uitls.q` 重命名成了 `uitls.Config`，
  而 `uitls.Config` 在 realclasses.txt 里**不存在** —— 照 jadx 名字写 hook 会在运行期直接 miss。
  必须用真实名 `uitls.q`。子代理为此产出了 `scratch-recon/dexmethods.py`（直接读 DEX 方法表）。

## 3. 下一轮实现方案（已确定）

1. **顶部频道栏逐项隐藏**：hook `com.tencent.channelnav.impl.r1.C()`
   过滤 `List<PBChannelListItem>`（按 `.title` 显示名匹配用户开关），
   或更保守地 hook `uitls.q.y()` 追加 id 走 App 官方黑名单。
   注意报告中的 caveat：`r1.t(String)` 索引的是**未过滤**的 `this.o`，
   只过滤 `C()` 可能与索引错位（App 自带黑名单也有同样性质，可接受）。
2. **设置页加 ColorOS 提示**："首次会弹「允许启动其他应用」，请选允许"。
3. 两个逆向子代理中的"首页元素/入口精简/减少预加载"**仍未返回**。

## 4. 状态
- 已交付 `qqliveclean-v0.2.0.apk`：7 条规则全部布防 0 miss、4 条广告规则实测命中、
  首页焦点广告消失、logo 已换（自绘）、**设置页配置已可真正送达并持久化**。
- 目标保持 active。
