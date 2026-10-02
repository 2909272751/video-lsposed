# 本轮进展（goal round 11/256）

## 1. ★ 修掉了通用可观测性缺陷（round 9 无法定性的根因）

LSPosed 会丢弃 Activity 启动窗口内写入的模块日志，而**所有** `rule=…hooked/miss` 都写在那个窗口里
→ 真失败与日志假象无法区分（round 9 就卡在这）。

修法：`H` 内累积每一条规则的结局（`installNotes`），并**把整张表挂在首次 `hit=` 行上**输出
（命中行写在稍后的可靠窗口）。效果立竿见影——一次就拿到了全部 11 条规则的结局：

```
installs=[splash_gate=ok splash_start_gate=ok splash_preload=ok play_strategy=ok
          player_pause_gate=ok hls_midroll=ok pendant_ads=ok feed_ad_cell=ok
          ad_request_gate=ok reduce_preload=ok
          channel_bar=miss(PBChannelListItem.title field not found; cannot match by name)]
```

**10/11 条规则布防成功**，唯一失败的给出了精确原因。这个改动以后每次排查都受益。

## 2. ★ 顺手抓出并修掉一个真 bug：`feed_ad_cell` 从未生效

上一版日志自相矛盾——既说 `ona.ad.universal.g.m(?)Z not found`，又把
`m(AdFeedInfo)` 列在可用方法里。原因是我自己写错：

```java
R.findByShape(dispatcher, new String[]{"m"}, boolean.class, null)   // ✗
```

Java 里把裸 `null` 传给 `Class<?>...` varargs，含义是**整个数组为 null**，不是"一个通配元素"，
于是方法内 NPE 被 catch 掉 → 静默 miss。

修法：`(Class<?>) null` 显式转型。修复后 **`feed_ad_cell=ok`** ——
这条覆盖**信息流 / 焦点图 / 卡片 / 底栏广告 cell**，是覆盖率上的一次实质提升。

## 3. 新功能：减少预加载（与音乐模块对齐）

新增 `PreloadRules.java`：hook `l81.p`（classes27，ChannelNav opt 配置单例）的
`A()`/`B()` 两个总开关 → 恒 false，让 App 走**它自己的低预载分支**
（`RecommendPagerOptFragment.Pc()` 随后会 `setOffscreenPageLimit(1)`，
`sj4.h` / `HomeChannelPreloadTask` 也都有 App 自带的 disable 分支，不与之对抗）。
实测 `reduce_preload=ok`。默认**关闭**（省电收益需真机量化，不凭代码推断）。

## 4. `channel_bar` 失败的精确原因（round 9 结案）

```
channel_bar=miss(PBChannelListItem.title field not found; cannot match by name)
```

`com.tencent.channelnav.PBChannelListItem` **没有可直接反射的 `title` 字段**
（该类全仓无反编译源码，很可能字段名不同或需走 getter）。
→ round 10 子代理的怀疑被证实，我的假设是错的。

**下轮两条路（择一）**：
1. 用 `scratch-recon/dexmethods.py` 读 classes22 的 DEX 方法/字段表，拿到真实访问器名再按名匹配；
2. 改用 App **自带黑名单**：hook `com.tencent.channelnav.uitls.q.y()` 追加 id
   （官方过滤路径、一处覆盖 V1/V1-Opt/V2，但**只能按 id**，而频道 id 服务端下发且无静态 id→名字映射）。

## 5. 状态
- 已交付 `qqliveclean-v0.2.0.apk`：**11 条规则中 10 条实测布防成功**（广告 8 条 + 预载 1 条 + 标签 1 条），
  广告类命中证据齐全，配置送达已打通并持久化，logo 自绘换新，设置页可写。
- `channel_bar`（顶部频道栏逐项精简）**仍待修**，原因已明确、路径已定。
- 目标保持 active。
