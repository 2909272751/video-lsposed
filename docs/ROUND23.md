# 本轮进展（goal round 23/256）

## 新增：入口精简（侧边入口图标隐藏）—— 又一条**安全形状**规则

recon（`j-home-gates.md`）给出：`com.tencent.channelnav.uitls.i1` 的静态方法
`n(ng.e) : SidebarContentType` 决定显示哪个侧边小入口图标；**强制返回
`SidebarContentType.Default`** 即让 App 走它自己的"无入口"分支
（消费方 `SidebarPreloadThunk` 按该枚举值发 redux action，后果由 App 自己处理）。

**为什么这是安全形状**：它只是让一个**决策方法返回 App 自己的枚举常量**，
**没有从 UI 正在构建的列表里删条目** —— 出错时最坏是"入口照旧显示"，
而不是底栏/频道栏那种"整块 UI 消失"。与已撤下的两条规则风险相反。

**跨版本核验**：`com.tencent.channelnav.uitls.i1` 在新版本 9.04.55 **present** ✅
（同批核验里 recon 推荐的另一个候选 `NavServerForceInsertUtil` 已 **ABSENT**）。

实现要点：
- 类名/方法名是混淆的（`i1`/`n`），所以**按形状查找**：静态 + 单参数 +
  返回**非混淆**的 `SidebarContentType`；先按已知名 `n`，失败则扫描同类形状，
  若匹配到多个则记 `ambiguous` 而不是随便挑一个（歧义即失败）；
- `Default` 枚举常量在安装期解析一次，intercept 内零查找；
- 已构建并部署。

## 当前规则集

**抑制型/决策型（11 条，安全形状）**：
`splash_gate` `splash_start_gate` `splash_preload` `play_strategy` `player_pause_gate`
`player_ads` `hls_midroll` `pendant_ads` `feed_ad_cell` `home_promo` `ad_request_gate`
`reduce_preload`（多变体回退）+ 本轮 **`sidebar_entrances`**

**已撤下（会裁剪 UI 列表）**：`tab_filter`、`channel_bar*`

## 阻塞未变：新机上模块仍未启用

本轮两次检查仍为 `grep -c qqliveclean …/modules_*.log` = **0**。

> 需要你：LSPosed 管理器 → 模块 → 腾讯视频精简 → 打开开关 → 作用域勾选「腾讯视频」→ 重启腾讯视频

启用后我第一件事就是取**跨版本安装表**（11+ 条规则在 9.04.55 上的实际布防清单），
并据此判定"底栏整栏消失"是否与本模块有关。

## 状态
- 交付物 `qqliveclean-v0.2.0.apk`（本轮已部署）。
- 目标（新 goal「继续推进」）保持 active。
