# 本轮进展（goal round 20/256）

## 主动撤下顶部频道栏规则 —— 与底栏 bug 同一个危险形状

你报的 bug（隐藏**一个**底栏项 → **整条**底栏消失）根因是：
**我从"UI 自己构建的那份列表"里删条目**。而顶部频道栏那几条规则用的是**完全相同的手法**
（返回被截短的 `List<PBChannelListItem>`），所以带着同一个失败模式：
过滤不当中一下就会把整条顶部频道栏也弄空。

而且它们**从未被观测到触发过**（round 13–15：四条全部布防、零命中）。
即当前收益为零、风险已被实证 → 我选择撤下，并把理由写进日志：

```
rule=channel_bar status=skipped reason=withdrawn: prunes a UI-built list
  (same shape as the bottom-bar blanking bug); replacement is app-blacklist append
```

**安全替代方案（recon 已给）**：不再裁剪 App 的列表，改为**往 App 自带黑名单
`com.tencent.channelnav.uitls.q.y()` 追加 id** —— 由 App 自己完成过滤与索引映射，天然一致。
前提是要先拿到运行期的 **id→名字映射**（频道 id 服务端下发，dex 内无静态表）。

## 当前规则集（撤下两条 UI 规则后）

| 类别 | 规则 | 锚点在新版本(9.04.55) |
|---|---|---|
| 开屏 | `splash_gate` / `splash_start_gate` / `splash_preload` | ✅ 存活 |
| 播放/贴片/暂停 | `play_strategy` / `player_pause_gate` / `player_ads` | ✅ 存活 |
| 中插(HLS) | `hls_midroll` | ✅ 存活 |
| 挂件/底栏推送 | `pendant_ads` | ✅ 存活 |
| 信息流/焦点图/卡片 | `feed_ad_cell` | ✅ 存活 |
| 全局广告请求 | `ad_request_gate` | ✅ 存活 |
| 性能 | `reduce_preload`（已多变体回退 `l81.p/a/b/c`） | ⚠️ 原锚点漂移，已补回退 |
| ~~底部标签栏~~ | **已撤下**（会整栏消失） | — |
| ~~顶部频道栏~~ | **本轮撤下**（同形状风险、且从未命中） | — |

**共 9 条会布防的规则，全部属于"抑制广告路径"这一类，不再有任何"从 UI 列表里删条目"的规则。**
8 条广告锚点已离线核验在 9.04.55 全部存活（round 18）。

## 仍需你完成的一步（无变化）

新机上模块**仍未启用**（`grep -c qqliveclean` = 0，已持续 5 轮）：
> LSPosed 管理器 → 模块 → 腾讯视频精简 → 打开 → 作用域勾「腾讯视频」→ 重启腾讯视频

启用后我会取第一张**跨版本安装表**作为实测基线。**同时这也是判定"底栏消失是否由本模块造成"的前提**：
若模块始终未启用，则底栏消失与我的规则无关。

## 状态
- 交付物 `qqliveclean-v0.2.0.apk`：9 条广告/性能规则（锚点跨版本已核验）+ 2 条 UI 规则按风险撤下。
- 目标保持 active（仍有可做的离线工作）。
