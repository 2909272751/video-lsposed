# 本轮进展（goal round 12/256）

## ★ 11 条规则全部布防成功（0 未命中）—— 目前最好状态

```
installs=[splash_gate=ok splash_start_gate=ok splash_preload=ok play_strategy=ok
          player_pause_gate=ok hls_midroll=ok pendant_ads=ok feed_ad_cell=ok
          ad_request_gate=ok reduce_preload=ok channel_bar=ok]
```

同一份日志里还留着改动前后的对比（同一字段从 miss 变 ok），是本轮修复的直接证据：

| 构建 | feed_ad_cell | channel_bar |
|---|---|---|
| 上一版 | `miss(ona.ad.universal.g.m(?)Z not found…)` | `miss(PBChannelListItem.title field not found)` |
| **本版** | **`ok`** | **`ok`** |

## 本轮修掉的两个 bug（都是"命中不了"的真因）

### 1. `channel_bar`：类名根本不存在
`com.tencent.channelnav.PBChannelListItem` **在 realclasses.txt 里没有**。
真实类是 **`com.tencent.qqlive.protocol.pb.PBChannelListItem`**（line 200277）
与 `…protocol.pb.kmm.PBChannelListItem`（line 208326）。

修法：`NameReader.resolve()` 按候选类名顺序加载，访问器按
**字段 `title` → `getTitle()` → `title()`** 三种形态依次尝试，任一失败只记 `diag` 不静默；
解析结果在安装期定死，intercept 内零查找。

### 2. `feed_ad_cell`：varargs 裸 null 陷阱（round 11 已述，本轮并入 0 miss 状态）
`R.findByShape(..., boolean.class, null)` 里的裸 `null` 被 Java 解释为**整个 varargs 数组为 null**，
方法内 NPE 被 catch 掉 → 静默 miss。改 `(Class<?>) null` 后恢复。

> 这两个 bug 的共性教训：**"方法/字段找不到"必须把候选清单打进日志**，
> 否则只能看到 miss 而看不到"其实有这个方法叫什么名"。`R.describeShapes` / `R.describeCandidates`
> 就是为此加的，本轮两个 bug 都是靠它们的输出定位的。

## 仍未完成：`channel_bar` 的**命中与可见效果**尚未观测到

规则已 `ok`，但日志里**还没有 `hit=channel_bar`** —— 即 `com.tencent.channelnav.impl.r1.C()`
在这次启动流程里没被调用（频道列表可能未重建，或首页尚未走到该装配点）。

**下轮验证步骤（明确）**：
1. 进入腾讯视频首页 → 下拉刷新 / 切换一次频道，促使频道列表重建；
2. 看是否出现 `hit=channel_bar hidden=N kept=M/N`（该行会带 `config_source` 与安装表）；
3. `uiautomator dump` 对比顶部频道栏文本，确认「大师 / 动漫」确实消失
   （当前缓存里已置 `show_channel_master=false`、`show_channel_anime=false`）。
   若 `C()` 始终不被调用，则改 hook 报告给出的三族闸门之一，或直接改用 App 自带黑名单
   `com.tencent.channelnav.uitls.q.y()`（只能按 id）。

## 状态
- 交付物 `qqliveclean-v0.2.0.apk`：**11/11 规则布防、0 miss**；广告类命中证据齐全；
  配置送达已打通并持久化；logo 自绘换新；设置页可写。
- 目标保持 active。
