# 本轮进展（goal round 10/256）

## 第二份逆向报告落地：`scratch-recon/j-home-gates.md`

### 重要结构更正（推翻我先前的搜索方向）
- `com.tencent.qqlive.ona.home.*` / `com.tencent.qqlive.home.*` **在 realclasses.txt 零命中，不存在**；
  真实命名空间是 `com.tencent.qqlive.ona.homecontainer.*`（仅 13 类）。
- `LoadHomeTabDataTask` **全文 12 行、方法体只有一行日志**，不加载数据 → 名字误导，不是闸门。
- `ona.homecontainer.utils.HomeTabHelper` 是 **RAFT 桩**（直接 return null），不可作 hook 目标。
- `HomeTabData` 两个变体都是**底栏/Home tab 的 DTO**，与顶部频道栏无关。
- `kmm.view.tablayout.HomeTabType` 只有 6 个底栏 pageType，也与顶部频道栏无关。
- `prefetch` 类名零命中；`setOffscreenPageLimit` 无统一封装类。

### 三族可用闸门（按置信排序）
| 族 | 目标 | 说明 |
|---|---|---|
| 首页频道级运营浮层 | **`com.tencent.qqlive.ona.channel.ChannelFullFloatPlugin.onCheckView()`**（classes8） | 一处闸门统一关掉 big banner / lottie / foot banner / big H5 / TMS banner / 摇一摇 / 饭团 |
| 顶部强插运营位 | `channelnav.uitls.NavServerForceInsertUtil.d(PBChannelListItem, List)`（classes24） | 返回 `a.d.a(CASE_A)`（occupant=null+deferred）⇒ 不插入也不删本地候选；白名单 AB key `nav_server_force_insert`（**非混淆，可零 hook**） |
| 侧边小入口全隐 | `channelnav.uitls.i1.n(ng.e):SidebarContentType`（classes24，static） | 强制返回 `Default` = 侧边小入口全隐；枚举**非混淆**，类/方法混淆（结构兜底：static + 返回该枚举 + 单参） |
| 减少预加载 | **`l81.p`（=ChannelNavOptConfig，classes27）`A()/B()` → false**；配套 `sj4.h`（ChannelPreloadHelper，classes9）自带 disable 分支；`HomeChannelPreloadTask` 自带 `DISABLE_PRELOAD_HOME_REQUEST` 分支（返回硬编码 false，与开屏 `F()` 同范式） | `RecommendPagerOptFragment.Pc()`（classes8）就是 offscreen 那条：`setOffscreenPageLimit(1)`，带 per-Fragment latch |

**诚实缺口（对方明确声明）**：**顶部频道栏"逐项隐藏"的通用函数没找到**。
可见列表是由 classes24 里多个 `rg.f` 插件逐个 pipe 而成（只读了 `rg.d`）；
`ChannelNavDataManager*` 有 1199/1055 行却找不到单点布尔闸。
→ 这正好与我 round 9 的失败现象吻合：我 hook 的 `impl.r1.C()` 或许不是"最终可见列表"的出口。

## 本轮其他结论
- `PBChannelListItem` 的真实字段名仍未确认（`dexmethods.py` 对该 FQCN 无输出，
  可能类名不在 classes22 或需要另一路径）；**`title` 反射很可能是 round 9 失败的原因**。
- 通用方法论缺陷已确认：**所有"安装期"诊断都可能被 LSPosed 在 Activity 启动窗口丢弃**，
  因此诊断必须改到"首次命中行"这种可靠窗口输出。

## 下轮优先级（明确）
1. 先把诊断搬到可靠窗口（在任意首次 `hit=` 时打印 `ChannelRules` 的安装结果），
   用它判定 round 9 到底是"类/字段取不到"还是"`C()` 未被调用"。
2. 按报告继续逆 classes24 的 `rg` 插件链，找**逐项隐藏**的真正出口；
   或改用报告给出的三族高置信闸门（频道级运营浮层、侧边入口、减少预加载）先交付可见成果。
3. 减少预加载是本轮性价比最高的一项：`l81.p.A()/B() → false` 有现成非混淆 AB key，
   且 App 自己就有 disable 分支。

## 状态
- 已交付 `qqliveclean-v0.2.0.apk`（7 条规则正常、广告 4 条实测命中、配置送达已打通并持久化、
  logo 自绘换新、设置页可写）。
- 顶部频道栏精简：**已实现、实机未确认**（原因待下轮用可靠窗口诊断定性）。
- 目标保持 active。
