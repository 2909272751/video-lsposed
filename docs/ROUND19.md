# 本轮进展（goal round 19/256）

## 完成：`reduce_preload` 改为多变体回退（修掉 round 18 查出的漂移之一）

round 18 的跨版本体检发现 `l81.p` 在 9.04.55 已不存在（只剩 `l81.a/b/c`），
会让 `reduce_preload` 在新机上落成 `miss`。本轮把它改成**多变体回退**：

```java
final String[] candidates = {"l81.p", "l81.a", "l81.b", "l81.c"};
```

- 逐个尝试，**哪个候选提供 `A()Z` / `B()Z` 就挂哪个**，各自独立 hook id
  （`qqlive_preload_l81_a_A` 这类），每个候选独立 `miss` 行；
- 类不存在**不算失败**（只是不是候选），只有"所有候选都没提供开关"才记一条汇总 `miss`；
- 与 `channel_bar_tab_v1/v1opt/v2` 同一套路：**改名的代价是一条 miss 行，而不是功能整体失效**。

构建通过、已安装到新机。

## 阻塞：新机上模块仍未启用（已持续 4 轮）

```
grep -c qqliveclean /data/adb/lspd/log/modules_*.log   →   0
```

即新机日志里**没有任何本模块记录**，说明 LSPosed 里「腾讯视频精简」还没被启用
（或作用域没勾 `com.tencent.qqlive`）。

**这不是我能自己解决的一步** —— 上次我直接改 lspd 数据库造成过运行态异常，已承诺不再碰。
需要你在新机上操作一次：

> LSPosed 管理器 → 模块 → 腾讯视频精简 → 打开开关 → 作用域勾选「腾讯视频」→ 重启腾讯视频

启用后第一件事就是取**跨版本安装表**（round 18 已离线预测：17/19 锚点存活，
`reduce_preload` 本次已加回退，`tab_bar_data` 因底栏规则已撤下而暂不影响）：

```powershell
& C:\Android\adb.exe shell "su -c 'grep -o \"installs=\[[^]]*\]\" /data/adb/lspd/log/modules_*.log | tail -1'"
```

注意：**这也是判定"底栏整栏消失是否由本模块造成"的前提**。
你说过模块可能还没启用 —— 若确实没启用，那底栏消失就与我的规则无关，需要另行定位。

## 不依赖设备的剩余工作（如有需要我可以继续做）

1. **底栏精简重做**（round 17 已撤下）：改成"不删条目、按项隐藏"或"只过滤默认组合"，
   并把新版本漂移的 `re0.i`（现为 `re0.a/b/c/d/e`）纳入多变体回退；
2. **首页运营元素 / 入口精简**：逆向已完成（`scratch-recon/j-home-gates.md` 给出高置信闸门：
   `ChannelFullFloatPlugin.onCheckView()`、`SidebarContentType` 那条、`NavServerForceInsertUtil.d()`），
   可直接实现；
3. **`dexmethods.py` 调用方式确认**（本轮与 round 18 两次调用均无输出），
   否则无法权威读取新版本方法表。

## 状态
- 交付物 `qqliveclean-v0.2.0.apk`：底栏规则已撤下；`reduce_preload` 已多变体回退；
  其余 8 条广告规则锚点经离线核验在新版本**全部存活**。
- 目标保持 active（仍有可做的离线工作，故不判 blocked）。
