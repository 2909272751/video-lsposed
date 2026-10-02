# 本轮进展（goal round 4/256）

## 本轮核心发现：这台手机上根本没有底部标签栏

用 `uiautomator dump` 拿到无障碍树（53922 字节），可见文本只有：

```
首页 大师 电视剧 动漫 电影 综艺 筛选          ← 顶部频道栏
一站到底 少年季 第2季 / 仙逆 / 兰香如故 / 抓特务 / 爱情保卫战 / 鸣潮 …
```

`resource-id` 里唯一与导航相关的是 `android:id/navigationBarBackground`（**系统**导航栏）。
**没有任何底部标签项**。

佐证：KMM tabbar 包里同时存在 `PadTabBarViewInnerKt`（Pad 布局）与 `FloatTabBarViewKt`（浮层模式），
说明 `com.tencent.kmm.tabbar` 是 **Pad / 浮层** 形态的底栏；手机形态下该 App 的顶层导航是**顶部频道栏**。

### 结论（重要，影响功能设计）
- 我实现的 `tab_default_list` / `tab_bar_data` 规则**在这台手机上是空转**——
  不是规则失效（`status=hooked`，`miss` 列表为空），而是**这个界面压根不构建底栏**，
  所以那两条 hook 永远不会被调用。
- 要交付与音乐模块「底部标签页」等价的**可见**效果，必须改为精简**顶部频道栏**
  （首页/大师/电视剧/动漫/电影/综艺…）。

### 下一轮的定位起点
候选命名空间：`com.tencent.qqlive.tabsdk.*`
（`TabSDKManager` / `ITabSDKManager` / `TabProfiles` / `TabConfigKey` / `TabSDKExperimentManager`）。
它是频道 Tab 的 SDK，之前 `whichdex` 显示位于 classes2 等多个 dex；但
`reference-apk/jadx-tabbar/sources/com/tencent/qqlive/tabsdk/` **不存在**，即尚未反编译，
需要先解包目标 dex 再搜「频道列表装配」的一次性闸门（不要 hook item 绑定/绘制）。

## 已确认可用（累计，均有实测证据）

| 项 | 证据 |
|---|---|
| 配置送达 | `config_source=module_provider` |
| 7 条规则全部布防 | `status=miss` 列表为空 |
| 广告规则实际命中 | `play_strategy` / `player_pause_gate` / `hls_midroll` / `ad_request_gate` 均有 `hit=` |
| 首页焦点位广告消失 | 「奥利奥・广告」横幅已由自然内容取代（截图） |
| 设置页可用 | 开关可点、本地落盘、并经 provider 送达目标进程 |
| logo 换新 | 自绘，与音乐模块图标 SHA 不同 |
| LSPosed 事故结案 | 根因 Zygisk Next 1.3.2 缺 zygisk API v4，升级 1.5.0 后「已激活」 |

## 与音乐模块的功能对齐表

| 类别 | 音乐模块 | 本模块 |
|---|---|---|
| 开屏广告 | ✅ | ✅ |
| 开屏外广告 | 仅开屏 | ✅ **7 条规则，超出音乐模块** |
| 底部标签逐项 | ✅ 4 项 | ⚠️ 代码已装，**本机无此 UI**（Pad/浮层才生效） |
| 顶部频道栏逐项 | — | ❌ **待做（本机的等价功能）** |
| 首页频道/元素逐项 | ✅ 6 项 | ❌ 待做 |
| 入口精简 | ✅ 2 项 | ❌ 待做 |
| 减少预加载 | ✅ | ❌ 待做 |
| 诊断日志 | ✅ | ✅ |
| 设置页可写 | ✅ | ✅（本地+送达已通） |
| 逐 feature 状态上报 | ✅ `feature=x result=y` | ❌ 待统一口径 |
