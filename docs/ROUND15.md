# 本轮进展（goal round 15/256）

## ★ 布防数升到 14/14，0 未命中

```
installs=[splash_gate=ok splash_start_gate=ok splash_preload=ok play_strategy=ok
          player_pause_gate=ok hls_midroll=ok pendant_ads=ok feed_ad_cell=ok
          ad_request_gate=ok reduce_preload=ok
          channel_bar=ok channel_bar_tab_v1=ok channel_bar_tab_v1opt=ok channel_bar_tab_v2=ok]
```

上一轮被截断读不到的安装表，本轮用 `grep -o 'installs=\[[^]]*\]'` 完整取出。
**结论：三套变体过滤器的类名/方法名与真机完全一致，全部布防成功** —— round 13/14 的猜测
（"可能类名不对"）被排除。

## 仍未解决：四条频道 hook 全部布防成功，但**一次都没被调用**

- `channel_bar`（`impl.r1.C()`）、`channel_bar_tab_v1/v1opt/v2`（三个数据管理器过滤器）
  **均 `ok`，但日志里没有任何 `hit=channel_bar*`**。
- 排除了"类名错"，剩下的解释只可能是**这几条装配路径在这个 App 状态下根本没走**
  （频道列表可能来自缓存直出 / 或走 KMM·redux 的另一条链 / 或在我的 hook 安装时点之前就已完成）。
- 由于只能通过 adb 驱动（冷启动、切频道、下拉刷新三种都试过），**我无法再扩大触发面**。

## 下一步（明确，且不再依赖"装配出口"）

**方案 B：hook App 自带黑名单 getter `com.tencent.channelnav.uitls.q.y()` 并追加频道 id。**
- 子代理已验证**三个数据管理器都读它**，且这是 App **官方过滤路径**；
- 它由 `filterByBlackList` 消费，属于"数据到来即执行"，比等装配出口更容易被触发；
- 代价：**只能按 id**，频道 id 服务端下发、dex 内无静态 id→名字映射
  → 需先加一个**一次性 dump 频道 id→名字**的诊断（挂到首次命中行，避免被 LSPosed 丢弃），
  拿到映射后再固化进设置项。

次选：逆向报告给出的三族闸门（频道级运营浮层 `ChannelFullFloatPlugin.onCheckView()`、
侧边入口 `uitls.i1.n()`、顶部强插 `NavServerForceInsertUtil.d()`），
它们与本需求（"频道/元素精简"）同样相关，且都已确认是一处覆盖多处的闸门。

## 交付物现状

`qqliveclean-v0.2.0.apk`：
- **14/14 规则布防、0 miss**；
- 广告侧实测命中（`play_strategy` / `player_pause_gate` / `hls_midroll` / `ad_request_gate`），
  首页焦点位广告可见消失；
- 配置送达 `target_cache[ok]` 并跨启动持久化；
- logo 自绘换新（非音乐模块图标）；设置页可写。

## 状态
- **顶部频道栏逐项精简：14/14 中 4 条频道 hook 已布防，但实机未观测到调用 → 仍记为未验证。**
- 首页运营元素 / 入口精简：逆向已完成（`scratch-recon/j-home-gates.md`），未实现。
- 目标保持 active。
