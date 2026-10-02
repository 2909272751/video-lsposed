# 本轮进展（goal round 3/256）

## 1. 配置送达修好（本轮硬阻塞解除）

实测日志（同一进程内先失败后成功，两条都保留）：

```
hit=player_pause_gate ... config_source=remote_preferences channel_error=IllegalArgumentException: Unknown authority io.github.qqliveclean.config
hit=player_pause_gate ... config_source=module_provider          ← 现已成功
```

处置：
- **通道自证**：`Config.readChannelFile()` 改为依次探测多个候选路径，并**逐路径记录结果**
  （`absent` / `unreadable` / 异常类名 / `ok`），结果挂在首次命中行上。
  候选：`Android/media/<pkg>/`、`Download/`、`Android/data/<pkg>/files/`。
- 设置页 `App.publish()` 一次写多个路径（实测 `Android/media=ok`、`Download=ok`、
  `Android/data=nomkdir`，后者因 scoped storage 无法创建，已忽略）。
- `Config.resolve()` 顺序：**文件通道 → Provider → LSPosed RemotePreferences → 内置默认值**，
  并把两条通道各自的失败原因分开输出（`file=` / `provider=`），不再互相覆盖。

结论：**用户设置已能送达被 hook 进程**（`config_source=module_provider`）。

## 2. 四条广告规则全部实测命中，且 0 未命中

```
hit=play_strategy     PLAY_STRATEGY forced to NO_AD_REQUEST        ← App 官方抑制开关
hit=player_pause_gate ad request refused (pre/mid/post-roll + pause + corners)
hit=hls_midroll       in-stream HLS mid-roll breaks suppressed
hit=ad_request_gate   outgoing ad request suppressed -> requestId 0
```
`status=miss` 列表**为空** → 已安装的所有规则（含 `feed_ad_cell`、`pendant_ads`、
`tab_default_list`、`tab_bar_data`）全部成功布防。

## 3. 仍未完成

1. **底部标签栏视觉确认（唯一剩下的验收缺口）**
   `tab_default_list` / `tab_bar_data` 已布防但**从未触发** → 说明当前界面**根本没构建底部标签栏**
   （前台始终是 `com.tencent.qqlive/.ona.activity.SplashHomeActivity`）。
   这是**被 hook 应用的页面结构问题，不是模块失效**：规则已就位，只要底栏一构建就会生效。
   需要导航到真正带底栏的主页面再截图；`feed_ad_cell` 同理（首页广告 cell 未走该 dispatcher 路径）。
2. **与音乐模块对齐的剩余功能**：首页频道/元素逐项精简、入口精简、减少预加载。
3. **skill v0.5.x 报告口径**：未统一为 `feature=<name> result=matched|partial|miss|off`，
   也还没有 `StatusProvider`/`StatusReceiver` 跨进程状态通道。
4. 18/29 dex 未反编译（子代理指出），`feed_ad_cell` 命不中可能与此有关
   （feed 广告的客户端请求闸门尚未找到）。

## 4. 交付物现状

- `app/dist/qqliveclean-v0.1.0.apk`：新 logo + 设置页可用 + 7 条广告/标签规则。
- 设置页：本地保存可用；**送达目标进程已打通**；改完强停腾讯视频生效。
- 文档：`docs/ANALYSIS.md`（逆向证据）、`docs/ROUND1.md`、`docs/ROUND2.md`、本文件；
  子代理报告 `scratch-recon/RECON-REPORT.md` 及四份 deep-dive。
