# 本轮进展（goal round 14/256）

## 实现：改为同时挂三套实现的过滤器（round 13 方案 A）

round 13 已证 `channelnav.impl.r1.C()` 在正常流程里不被调用，所以只赌这一个门面不够。
本轮新增 `ChannelRules.installVariantFilters()`，对三个数据管理器变体**各自独立**挂 hook：

| 规则 id | 目标 |
|---|---|
| `channel_bar_tab_v1` | `com.tencent.channelnav.impl.ChannelNavDataManager.A0(List):List` |
| `channel_bar_tab_v1opt` | `com.tencent.channelnav.impl.ChannelNavDataManagerOpt.K0(List):List` |
| `channel_bar_tab_v2` | `com.tencent.channelnav.uitls.k.b(List):List` |

- 每条独立 id、独立 try/catch：某个变体不存在只记 `miss`，不影响其余；
- 过滤体抽成共用的 `prune()`，与 `r1.C()` 同一套按 `.title` 的匹配逻辑（`NameReader` 仍按
  字段→getter 三种形态解析，且用的是**修好的真实类名** `protocol.pb.PBChannelListItem`）；
- `r1.C()` 那条保留（双保险）。

构建通过、已安装。`channel_bar=ok`（上一轮已达成 11/11 布防）。

## 未完成：仍未观测到任何 channel_bar 的 `hit=`

本轮两次读取日志都被输出截断（我的辅助脚本 `cut -c1-150` + PowerShell 截行），
**没能读到最新的安装表**，因此**无法确认三套变体过滤器是否布防成功**，也**仍无 `hit=channel_bar*`**。

**这不是"已验证通过"，我按未验证记。**

## 下轮必须先解决的两件事（顺序不可颠倒）
1. **把日志读全**（改掉 `cut -c1-150`，或直接 `grep -o 'installs=\[[^]]*\]'` 只取安装表），
   先看清三套变体过滤器的 `ok/miss`；
2. 若三者都 `miss`（说明类名/方法名与真机不符），改走 round 13 的**方案 B**：
   hook App 自带黑名单 `com.tencent.channelnav.uitls.q.y()` 并追加频道 **id**
   —— 子代理已验证三个数据管理器**都**读它，是命中概率最高的一条；
   由于只能按 id，需要先加一个"一次性 dump 频道 id→名字"的诊断（挂在首次命中行上）。

## 状态
- 交付物 `qqliveclean-v0.2.0.apk`：广告 8 条 + 预载 1 条 + 标签 1 条 = **11/11 布防、0 miss**；
  广告命中证据齐全；配置送达已打通并持久化；logo 自绘换新；设置页可写。
- **顶部频道栏逐项精简：已布防，实机未观测到调用/命中** → 记为未验证。
- 首页运营元素 / 入口精简：逆向已完成（`scratch-recon/j-home-gates.md`），**未实现**。
- 目标保持 active。
