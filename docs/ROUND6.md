# 本轮进展（goal round 6/256）

## 实现：Intent extra + 目标端自缓存（配置送达的第三条路）

前两条通道（Provider、文件）已被 Android 16 逐路径证伪，本轮实现第三条：

**数据流**
1. 设置页在前台时，用户点「应用设置到腾讯视频」→ `App.applyToTarget()`
   → `getLaunchIntentForPackage(com.tencent.qqlive)` + `putExtra("qqliveclean.config", "k=v;k=v;…")`
   → `startActivity`。
   *合法性*：设置页 manifest 里有 `<queries><package com.tencent.qqlive/></queries>`（**发得出去**），
   且用户在前台（不受后台启动 Activity 限制）。
2. 腾讯视频被拉起 → hook 在首个 Activity（`MainHook.configure`）里
   `Config.storeFromIntent(activity, activity.getIntent())` → 把 payload 写进
   **腾讯视频自己的** SharedPreferences（`qqlive_clean_cache`，带 `_synced` 标记）。
3. `Config.resolve()` 顺序变为：**目标端缓存 → 文件 → Provider → RemotePreferences → 默认值**。
   之后每次启动都直接读本地缓存，**零跨进程依赖**，不再受可见性/作用域存储影响。
- payload 只接受白名单 key（`isExposed`），不会写入任意键。
- 诊断：`cacheReport` 会随首次命中行输出（`config_source=target_cache[ok]`）。

**改动文件**：`Config.java`、`App.java`、`MainHook.java`、`MainActivity.java`；
构建通过，产物 `qqliveclean-v0.2.0.apk`。

## 未完成：本轮**没有拿到实机验证**（如实说明）

`adb shell am start … -p com.tencent.qqlive -e qqliveclean.config '<payload>'`
无法解析到腾讯视频的 launcher Activity（`unable to resolve Intent`），
因此**没法用 adb 注入 extra 来验证 hook 侧**；而 `monkey` 不支持传 extra。
最近的命中行仍是上一条通道的（`config_source=remote_preferences`）。

**下一轮验证步骤（明确）**：
1. 打开设置页 → 用 OCR 定位「应用设置到腾讯视频」按钮坐标 → `input tap`；
2. 此时腾讯视频会被拉起，检查命中行是否变为 `config_source=target_cache[stored=12]`；
3. 再强停并正常启动一次 → 命中行应为 `config_source=target_cache[ok]`（证明本地缓存生效、
   不再依赖任何跨进程通道）。

## 状态

- 两个逆向子代理（顶部频道栏 / 首页元素·入口精简·减少预加载）**仍在运行**。
- 目标保持 active。
