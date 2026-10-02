# 本轮进展（goal round 7/256）

## 已确认：新配置通道的"发送端"接对了，但被 OEM 权限门拦在最后一步

在设置页用 OCR 定位到「应用设置到腾讯视频」按钮（click=(636,2068)），点按后：

```
topResumedActivity = com.oplus.securitypermission/…AppStartConfirmDialogActivity
```

即 `App.applyToTarget()` 里的 `startActivity(...)` **确实被执行了**，但被
**ColorOS 的「应用启动确认」弹窗**（关联启动/后台拉起确认）拦截，等待用户点确认。

结论：
- 代码路径正确（Intent 已发出，携带了 payload）；
- 在这台 realme/ColorOS 机器上，**首次需要用户手动确认一次**该弹窗，之后才能拉起腾讯视频；
- 因此 `config_source=target_cache[…]` 的实机验证**本轮未完成**（最近的命中行仍是上一条通道的）。

## 尚未拿到实机证据的完整验证链（下轮照做）
1. 点「应用设置到腾讯视频」→ 在 OEM 弹窗上点「允许」；
2. 腾讯视频被拉起 → 命中行应为 `config_source=target_cache[stored=12]`；
3. 强停后正常启动一次 → 命中行应为 `config_source=target_cache[ok]`（证明已脱离所有跨进程通道）。

## 建议的产品化处理（下轮实现）
- `applyToTarget()` 返回后，用 `Toast` 明确提示"若弹出系统『允许启动其他应用』请选择允许（仅首次）"；
- 或退化为**引导文案**：告诉用户先手动打开一次腾讯视频，再回设置页点应用；
- 也可以给设置页加一条 manifest 声明路径，但 ColorOS 的关联启动限制无法用 manifest 绕过。

## 状态
- 两个逆向子代理（顶部频道栏 / 首页元素·入口精简·减少预加载）**仍在运行**，报告未落地。
- 已交付：`qqliveclean-v0.2.0.apk`（7 条规则全部布防、0 miss；4 条广告规则实测命中；
  首页焦点广告已消失；logo 已换;设置页可写）。
- 目标保持 active。
