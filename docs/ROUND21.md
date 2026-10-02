# 本轮进展（goal round 21/256）

## 新增一条**安全形状**的规则：频道级运营浮层抑制

recon（`scratch-recon/j-home-gates.md`）指出
`com.tencent.qqlive.ona.channel.ChannelFullFloatPlugin.onCheckView()` 是**频道级运营浮层的唯一漏斗**：
大 banner / lottie / foot banner / 大 H5 / TMS banner / 摇一摇 / 饭团 全部挂在它下面，
且它每次频道加载只调用一次（`onLoadFinish` 里 `i==0&&z`），属一次性闸门。

**离线核验（新版本 9.04.55 的 class_defs）**：

| 类 | 9.04.55 |
|---|---|
| `com.tencent.qqlive.ona.channel.ChannelFullFloatPlugin` | **present** ✅ |
| `com.tencent.qqlive.ona.channel.ChannelPromotionEventListPlugin` | present |
| `com.tencent.channelnav.uitls.i1` | present |
| `com.tencent.channelnav.uitls.NavServerForceInsertUtil` | **ABSENT**（recon 给的另一个候选，新版本已漂移） |

**为什么这条可以加、而底栏/频道栏那两条必须撤**：这条是**抑制型**（不挂载运营视图），
最坏结果是"运营位照旧出现"，**不会让 UI 失去它正在构建的列表**。
风险形状与被撤下的两条**正好相反**。

实现：`AdRules.installHomePromoGate()`，复用现有 `block_ad_requests` 开关（不新增配置键），
已构建并部署。

## 当前规则集（9 条抑制型 + 2 条已撤下）

**会布防的规则（全部为"抑制广告/运营路径"，无任何"从 UI 列表删条目"）**

| 类别 | 规则 |
|---|---|
| 开屏 | `splash_gate` / `splash_start_gate` / `splash_preload` |
| 播放·贴片·暂停 | `play_strategy` / `player_pause_gate` / `player_ads` |
| 中插(HLS) | `hls_midroll` |
| 挂件/底栏推送 | `pendant_ads` |
| 信息流/焦点图/卡片 | `feed_ad_cell` |
| **频道级运营浮层（本轮新增）** | **`home_promo`** |
| 全局广告请求 | `ad_request_gate` |
| 性能 | `reduce_preload`（多变体回退） |

**已撤下**：`tab_filter`（底栏，会整栏消失）、`channel_bar*`（顶部频道栏，同形状风险且从未命中）。

## 仍在等的一步（已持续 6 轮）

新机上模块**仍未启用**：`grep -c qqliveclean /data/adb/lspd/log/modules_*.log` = **0**。

> LSPosed 管理器 → 模块 → 腾讯视频精简 → 打开 → 作用域勾「腾讯视频」→ 重启腾讯视频

启用后即可取得**跨版本安装表**（10 条规则在新版本上的实际布防清单），
这也是判定"底栏消失是否由本模块造成"的前提。

## 状态
- 交付物 `qqliveclean-v0.2.0.apk`：10 条抑制型规则 + 2 条 UI 规则按风险撤下。
- 目标保持 active。
