# 本轮进展（goal round 5/256）

## 1. 配置通道的确定性结论（逐路径证据）

新加的逐路径探测把问题一次说清：

```
file=io.github.qqliveclean=unreadable Download=unreadable files=absent
provider=IllegalArgumentException: Unknown authority io.github.qqliveclean.config
```

| 通道 | 结果 | 性质 |
|---|---|---|
| `Android/media/io.github.qqliveclean/qqlive_clean.conf` | **unreadable**（文件存在但 EACCES） | Android 16 scoped storage：别的 App 读不了对方 `Android/media` 下的**非媒体文件** |
| `/Download/qqlive_clean.conf` | **unreadable** | 目标 App 未持有所需存储权限（或同样被 scoped 拦截） |
| `Android/data/io.github.qqliveclean/files/` | absent | 我的模块**无权创建**别的包名的 data 目录 |
| ContentProvider | `Unknown authority` | Android 11+ **包可见性**，只有调用方 manifest 能声明 `<queries>`，而目标 App 的 manifest 改不了 |
| LSPosed RemotePreferences | 可读但**设置页写不了** | lspd 从不下发 `SendBinder` binder（已多次验证，重启无效） |

**附加观察（重要线索）**：同一条 provider 探测，在"刚打开过设置页"之后会成功
（`config_source=module_provider`），冷启动时则被可见性挡住。也就是说 **provider 通道并非永远不可用，
而与模块 App 进程是否刚活跃相关**。

### 结论与下一轮方案
所有"写文件让目标读"的路径都被 Android 16 封死；provider 不稳定。可行方向只剩两条：
1. **Intent extra 携带配置 + 目标端自缓存**（最可靠，推荐）：
   设置页前台时启动腾讯视频并带上配置 extra → hook 在首个 Activity 读取 extra，
   写入**腾讯视频自己的** SharedPreferences 缓存 → 之后每次启动直接读本地缓存，零跨进程依赖。
   代价：改设置时会拉起一次腾讯视频（可做成"应用设置"按钮，或自动）。
2. **manifest 级 receiver 不可行**（目标 App 的 manifest 改不了；动态 receiver 需要进程存活，
   而"强停后再开"的交互里它必然已死）。

## 2. 本轮完成的整理工作

- **设置页文案纠错**：底部标签栏一节原先默认存在底栏，现明确标注
  「仅对 Pad／浮层底栏形态生效；手机版顶层导航是顶部频道栏，没有底部标签栏，此项在手机上不会看到变化」。
- **版本号归位**：`0.1.0 (1)` → **`0.2.0 (2)`**，产物改名 `qqliveclean-v0.2.0.apk`，
  以反映规则集从 3 条扩到 7 条 + 新 logo + 设置页打通。
- 实机安装 v0.2.0 后规则照常命中（`player_pause_gate` / `hls_midroll` / `ad_request_gate`）。

## 3. 并行进行中

两个子代理正在做**顶部频道栏闸门**与**首页元素/入口精简/减少预加载闸门**的逆向
（只读，无设备风险）。它们的报告落地后即可实现：
- 顶部频道栏逐项精简 —— 本机唯一可见的频道 UI（round 4 已用 uiautomator 证实无底栏）；
- 首页元素/入口精简、减少预加载 —— 补齐与音乐模块的功能对齐表。
