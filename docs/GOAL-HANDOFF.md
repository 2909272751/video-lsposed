
## 零之一、**最高优先级的测量红线**（第 25 轮发现，比所有技术结论都重要）

> **查日志时永远不要把日志级别写进匹配条件。**
>
> 第 20~25 轮我一直用 ` " 15000 I/LSPosedFramework" ` 过滤，
> 于是 **402 条 W/LSPosedFramework 被静默丢弃**——
> 全部诊断行（ui_anchor_resolved 84 条、context_identity 26 条）都走 W 级别。
> 结论「第 20~25 轮驱动一次都没触发」**整段作废**，那是 grep 的锅。
>
> 正确写法：`  -match " 15000 " -and  -match 'qqliveclean' `
>
> **这条比「锁屏不能测」更致命**：它会让人把测量缺陷当成代码缺陷，
> 然后越改越偏（本项目已经为此白改了多轮锚点）。
## 零、硬规矩（任何 UI 类验证都必须遵守，2026-10-01 第 24 轮血泪）

> **1. 锁屏时什么都测不到。** mDreamingLockscreen=true / mWakefulness=Dozing 下，
>    没有任何 Activity 处于 resumed，Activity 只会 created、不会 resume。
>    第 20 轮（0/3）与第 23 轮（0/4）的负结果都因此作废。
>
> **2. 脚本必须先断言 	opResumedActivity 非空**，再开始计时与判断。
>    没断言 = 在测一个没启动的 App = 白测。
>
> **3. keyevent 82 不等于解锁。** 必须真的输入 PIN 并用
>    mDreamingLockscreen=false 复核。
>
> **4. 负结果比正结果更容易骗人。** 「没命中」先怀疑测量方法，再怀疑代码；
>    「命中了」也要确认行号/PID 属于本次会话，不是旧日志残留。# 交接说明书（截至 2026-10-01 18:00）

> 本文件是**单一事实源**，供新会话直接接手。只写有证据的结论；推测一律标注「未验证」。
> 逐版本矩阵与历次证伪记录在 [COMPATIBILITY.md](COMPATIBILITY.md)。

## 一、当前状态

**对外结论已整理进 [COMPATIBILITY.md](COMPATIBILITY.md) 顶部的「结论速览」表**，只列有 `hit=` 行的项。

| App | 版本 | 报告 | 已有命中行 | 结论 |
|---|---|---|---|---|
| 腾讯视频 | 9.04.55.32321 | 无 `install_summary` | `ad_request_gate`、`mine_ad_card`、**`feed_ad_cell`** | **三条规则全部有实测证据**；18:5x 回归复现 `mine_ad_card` |
| 爱奇艺 | 17.9.5 | `hooked=11 miss=0` | `iqiyi_splash`、`iqiyi_home_top_ad` | **开屏广告已真正拦下**（清数据冷启动无开屏广告） |
| 优酷 | 11.2.15 | `hooked=10 miss=0` | `youku_pause_ad`、`youku_tab_filter`、`youku_home_top_ad`、`youku_splash_hot_switch` | 信息流/界面广告已处理；**视频内容侧不下发广告物料**（见第三节） |

已装未验证 / 已撤下（**不得计入成果**）：

- `youku_preroll_probe` — 17 个控制点/广告物料控制点只读探针，**0 命中**（因无前贴下发，见第三节）。
  **保留**：零运行时代价，且是广告真下发时唯一的证据来源。
- `youku_ad_switch` — **已撤下**（15 个访问器 0 命中，挂着只制造「有覆盖」的错觉）。
- `youku_preroll_event` — **已撤下**（3 个重载一次都没触发）。
- `iqiyi_splash_sdk` — 联盟 SDK 层，0 命中，冗余层。

撤下后报告如实显示，APK 131,855 → 127,759 字节：

```
rule=youku_ad_switch     status=skipped reason=withdrawn on 11.2.15: 15 accessors armed, 0 hits…
rule=youku_preroll_event status=skipped reason=withdrawn on 11.2.15: 3 post overloads armed, never fired…
event=install_summary hooked=10 miss=0
```

> **连带事实**：`youku_mine_carousel` / `youku_mine_vip_promo` **在未登录状态下无法复现**——
> 点「我的」直接进登录页（`uiautomator dump` 已确认），到不了那两条规则所在的位置。
> 它们的命中行是退出登录之前取得的。这与「闸门无效」是两回事。

三个 App 均为**未登录**状态（用户要求）。

## 二、验证证据

真机日志读取（logcat 里没有该 tag，只能读 lspd 落盘文件）：

```powershell
adb shell "su -c 'cp /data/adb/lspd/log/modules_2026-10-01T07:09:45.789424.log /sdcard/Download/m1.log; chmod 666 /sdcard/Download/m1.log'"
adb pull /sdcard/Download/m1.log $env:TEMP\m1.log
Select-String -Path $env:TEMP\m1.log -Pattern 'hit=' | ForEach-Object { $_.Line -replace ' config_source=.*','' }
```

爱奇艺（本轮之前已取得）：

```
rule=iqiyi_splash status=hooked  x02.v.requestAdAndDownload()V suppressed
hit=iqiyi_splash requestAdAndDownload suppressed on x02.v
event=install_summary hooked=11 miss=0
```

**对照实验（2026-10-01 17:52，重要）**：把 `youku_preroll_event` 整个摘掉重新构建安装，用完全相同的脚本跑一遍，
优酷 `DetailActivity` **照样黑屏**。结论：**黑屏与模块无关**，是优酷恢复画中画小窗导致的应用状态。

## 三、已被证伪的假设（别重复劳动）

### 先看这两条环境阻断（有硬证据，决定了能不能验证）

1. ~~**优酷广告交换域名被 DNS 解析到 127.0.0.1**~~ **——已更正，见下方「重要更正」。**
2. **`screencap` 抓不到视频 Surface。** 优酷冷启动自动恢复画中画小窗，
   视频渲在 PiP 窗口里，主窗口截图全黑——前几轮误判成"模块拦坏了播放器"。
   → **判据只能用 logcat**：
   `logcat -d --pid=$(pidof com.youku.phone) | grep 'playback state updated'`
   出现 `source:pulse, buffer_in_ms:N` 就说明播放器在正常播放。

### 重要更正：优酷前贴不是被环境阻断的

上一轮写的「广告交换域名被 DNS 沉到 localhost」是**错的**。三家公共 DNS 都返回 `0.0.0.0`：

```
Resolve-DnsName adx-data-u1.ubixioe.com -Server 223.5.5.5   → 0.0.0.0
Resolve-DnsName ubixioe.com           -Server 223.5.5.5   → 0.0.0.0
Resolve-DnsName www.youku.com         -Server 223.5.5.5   → 59.82.31.184
```

`ubixioe.com` 整个域**没有有效 A 记录（已停用）**，Android 把「无记录」显示成 127.0.0.1，
所以看着像被墙。**换网络不会有任何变化，这条不要再拿去当前置条件。**

真正的阻塞是：**这些会话里优酷视频内容侧一个广告物料都没下发**。
探针挂在 `AdVideoView`（任何广告视频都会走的层）上，**现已覆盖全部两份同名副本，17 个控制点**；
换 3 个不同剧目各播 15 秒（38/40/43 条 HLS 状态，全部正常播）、feed 预热后再播，
以及 `pm clear` 清空数据后的全新安装态起播，**全部 0 命中**。

> **定性结论（2026-10-01 19:2x，已排除缓存漏洞）**：
> ① 5+ 剧目、feed 预热、`DetailActivity` 前台、90 条 HLS 播放状态；
> ② **优酷有两份同名 `AdVideoView`**（`xadsdk.ui.component` 在 classes5.dex、
>    `player2.plugin.interact.view` 在 classes2.dex），**两份都已挂探针，控制点 11 → 17**；
> ③ **`pm clear` 清空应用数据后冷启动**（隐私协议 → feed → 起播），
>    排除了「17:14 看到的是缓存创意」这一漏洞（探针 18:11 才存在，那次前贴在探针之前）；
> ④ 17 个控制点**全部 0 命中**，logcat 搜不到 `pread`/`adtype`/`creative`。
>
> 同一会话反而出现一条关键证据：
> `hit=youku_csj_dsp_off CSJ/穿山甲 DSP disabled via the app's own config switch`
> ——**全新安装时广告栈确实在跑**（连穿山甲 DSP 开关都在评估），
> 只是不给这个账号下发视频侧物料。**广告系统活着，物料没来**，与「模块拦掉了」无关。
> → **前贴/中贴在这台设备上无法复现，不要再找锚点。**
> 复现需要外部条件：换有库存的时段 / 换内容类型（电影、综艺贴片策略不同）/ 开会员看会员广告位。
> 探针已就位，真下发时日志里会直接出现 `AdVideoView.setAdType(int) arg0=<位号>`，不必再猜。

### 探针必须覆盖同名副本，否则给出的是「假零」

两份 `AdVideoView` 接口完全相同，只挂一份时「零命中」有可能只是看错了类，
而**看起来像结论**。另外探针的去重键原来用简单类名，
**两个同名类会共用同一个 once 标记**，第二个类即使被调用也不打日志（已改为全限定名 + `#`）。

> 覆盖不全的探针给出的零是假的，比没有探针更危险。
> 判断「被拦的东西没出现」之前，先确认探针覆盖了所有可能的类。

### 点击坐标必须用 uiautomator 的 bounds，不能按截图换算

按截图渲染尺寸估算去点「同意并继续」，点了个空（实际 bounds `[84,2313][1180,2453]` → 中心 **632,2383**，一擊即中）。
但优酷 feed 上 `uiautomator dump` 会报 **`ERROR: could not get idle state`**（自动播放让界面永不空闲），
此时只能用截图 + 已知坐标。
另外 `pm clear` 后首启会有隐私协议页，**不点掉它所有海报坐标都点不动**，看起来像「App 无响应」。

### 本轮最重要的一条通用教训

腾讯 `feed_ad_cell` 一直挂着却 0 命中，原因是**只停在首页没滚动 feed**，
feed 不请求新 cell，就没有广告格子经过那三个闸门；一滚动就命中了。
优酷 17 个控制点 0 命中，原因是**被拦的广告物料根本没出现过**。
和优酷是同一个道理，只是方向相反——

### 零命名的第一个问题不是「锚点对不对」

腾讯 `feed_ad_cell` 长期零命中，原因是**只停在首页没滚动 feed**；
优酷 17 个控制点零命中，原因是**被拦的广告物料根本没出现过**。
两种情况在日志里长得一模一样，都是零命中行。所以：

> 看到零命中，先问「被拦的东西出现过没有」，再问「锚点对不对」。
> 前者靠制造场景回答（滚动 feed / 换剧目 / 换时段），后者才轮到 dex 工具和源码阅读。

### 锁屏会静默吞掉整轮操作（已作废两轮）

`input keyevent KEYCODE_WAKEUP` **只点亮屏幕，不会解除锁屏**。
在它之后直接 `input tap`，点击全部落在锁屏上，App 侧看到的是「用户毫无反应」：

```
input keyevent KEYCODE_WAKEUP; input tap 170 560
→ topResumedActivity=com.youku.kuflix.RootPageActivity
→ playback_updates=0        # 点根本没进 App
```

和「App 卡死」「点击无效」完全一样。**必须用校验式解锁**：
`dumpsys window | grep mDreamingLockscreen` 为 `false` 才算解锁成功，否则重试。

### 工具缺陷（影响过历史判断）

`tools/dexrefs.py` 的 `classes` 子命令**从来没正确工作过**：
`arg = sys.argv[3]` 读到的是标志 `--prefix` 本身（值在 `argv[5]`），
加上描述符 `Lcom/...;` 与点分前缀未归一化，**所有 dex 一律报 0 个类**。
表现为「这个包里没有这个包」，实际是工具坏了。`--owner` 同样问题，均已修复。
→ 用它找锚点前，先拿一个已知存在的类抽查（如 `com.youku.kubus` 在 classes13.dex）。

### 优酷前贴广告，共七次

1. `AdRequestManager.d` 3 参 / `.f` / `buildAdTrackingUrl` — 全部 void 或不存在。
2. `com.kwai.m2.d.b.a$b.g`、快手联盟全屏视频请求。
3. `com.youku.utils.c`、`YkLocalVideoOpenActivity` extra。
4. 网络层（DNS/hosts）——优酷广告与正片共用 CDN。
5. 改写缓存的 Orange 配置 `yk_adsdk_syscfg(*.enable)` 与 `one_ad_config(enable_youku_ssp)` 为 0。
6. `com.youku.xadsdk.config.model.*ConfigInfo.getEnabled()` — **是死代码**，
   `dexrefs.py refs` 证明这些 getter 没有任何消费者；挂 14 个开关 0 命中。
7. `com.youku.xadsdk.config.AdOrangeConfig.getXxxConfig()` 上的无参 boolean 开关 — 0 命中。
8. `com.youku.kubus.EventBus.post/postSticky(Event)` — 钩子挂 3 个重载，**一次都没触发**。

> **根因（第八/九次尝试确认）**：不是锚点猜错，是**这些会话里一个广告物料都没下发**。
> `youku_preroll_probe` 现挂 **17 个控制点**：5 个前贴专用
> （`AndroidPlayer.initPreAdDuration` / `getAdCountDown`、
> `PlayerCorePlugin.skipPreAd`、`LivePlayerView.onPreAdStart` / `onPreAdEnd`）
> + **两份** `AdVideoView` 各 6 个广告物料入口
> （`com.youku.xadsdk.ui.component.AdVideoView` 与
> `com.youku.player2.plugin.interact.view.AdVideoView`，接口完全相同、分散在不同 dex）。
> 全部 0 命中，而同期 logcat 有 52 条 HLS 播放状态。
> 探针会记录 `setAdType(int)` 的整数值（广告位编号）与 `setVideoSource(String)` 的素材地址。

爱奇艺被证伪：`IAdInvoker.updateCupidAd`、`AdsController.onAdDataSourceReady`、`getAdCountDown`、
`QYPlayerADConfig.checkRegister/getDefault`、`PumaPlayer.OnAdPrepared/OnAdCallback`、`nx0.a.*`。
腾讯：`qqlive` 三处 `boolean(AdFeedInfo)Z` 闸门在 9.04.55 上**签名健在**，只是没渲染出广告格子。

## 四、下一步（按优先级，含前置条件）

1. **优酷：前贴当前无法复现，不要再找锚点。** 见第三节的定性结论。
   要复现必须换外部条件（时段 / 内容类型 / 会员态）。
   探针保持挂载即可——它零运行时代价，真下发时日志会直接给出广告位编号。
   可选的后续：把 `youku_ad_switch` 和 `youku_preroll_event` 两个零命中的闸门撤下，
   避免长期挂着报 `hooked` 却无证据（但保留探针，它是取得证据的唯一手段）。
2. **腾讯已完成**：`ad_request_gate` / `mine_ad_card` / `feed_ad_cell` 三条都有命中行。
   复查方法：首页 feed 连滚 5 页 + 切频道再滚 3 页。
3. **爱奇艺已完成**：开屏 `iqiyi_splash` 有稳定命中行，清数据冷启动后无开屏广告。
3. **构建失败必须看完整输出**：`Select-String 'Built|error'` 会把 javac 的中文报错吞掉，
   导致「构建失败 → 拿旧包去测 → 得出错误结论」。本轮因此白跑两次。
   构建后核对 `app/dist` 的 `LastWriteTime`，或直接 grep APK 里的新标识串。
4. 任何新锚点都要跑「logcat 播放状态 + 模块命中行」两步验证再写进 COMPATIBILITY.md。
5. `youku_ad_switch` / `youku_preroll_event` 两个零命中的闸门，在拿到真实广告物料后复核；
   若仍零命中就撤下，不要长期挂着报 `hooked` 却没有证据。

## 五、真机测试闭环（本轮踩坑总结，务必照做）

**adb 每次往返十几秒，屏幕会在往返之间掉进 Doze**，导致「改了没生效」的假象。
必须把整条时序写成脚本 `adb push` 到设备端 `adb shell sh` 一次跑完。

**已知未解的 UI 问题**：优酷每次冷启动都会自动恢复画中画小窗（PiP），视频渲在 PiP 窗口里，
`screencap` 抓不到，主窗口截图全黑。**这不是故障，是观测手段的盲区**——
用 logcat 的 HLS 播放状态判断播放器是否在播。

解锁（`input swipe` 上滑无效，必须用 keyevent 82 呼出密码盘）：
`input keyevent KEYCODE_WAKEUP` → `input keyevent 82` → 按键 `x=632`，
`y=1310/1611/1912/2213` 四行 → `KEYCODE_ENTER`。
**一次不一定成功，必须校验 `dumpsys window | grep mDreamingLockscreen` 是否为 false，不成功就重试**
（2026-10-01 实测第一次点完仍是 `true`，第二次才解锁；不校验的话后面所有步骤都在锁屏后面跑）。

开机屏保（脚本开头）：
```sh
settings put system screen_off_timeout 1800000
settings put global stay_on_while_plugged_in 7
dumpsys deviceidle unforce
```

**已知未解的 UI 问题**：优酷每次冷启动都会自动恢复画中画小窗（PiP），此时
`topResumedActivity` 是 `com.youku.ui.activity.DetailActivity`，但主界面**黑屏**，
点海报打不开可观测的前贴窗口。`input tap` 点 PiP 无效；`am start HomeActivity` 名字不对。
→ 这是下一轮首先要解决的**前置条件**，否则拿不到任何前贴证据。

`uiautomator dump` 在详情页会报 `could not get idle state`（页面持续动画），此时只能用截图判断。

## 六、纪律

- **没有 `hit=` 行就不算成功**；报告里 `hooked` 只表示方法解析成功。
- 规则 id 必须唯一；同名 id 会让一条已生效的规则被报成 miss。
- 保持模块轻量：不在被 hook 的进程里做 dex 扫描，发现全部离线做、结果硬编码。
- 字符串匹配务必精确：`contains("pread")` 会命中 `threadPrepare`（小写后含 "pread"），
  误拦的表现是「界面坏了」而不是报错，比不拦更难查。
- 手机空闲时锁屏，测完熄屏；测试期间临时改的全局设置必须在收尾恢复（见下）。

## 七、产物、构建与设备

| 项 | 值 |
|---|---|
| 产物 | `app/dist/video-clean-v0.3.13.apk`（versionName 0.3.13 / versionCode 16）<br>SHA-256 `5c77dcdd82de2d22a56060c430b1301670aff826f5f9273d177373ff6a23279c`（`dist/SHA256SUMS.txt`） |
| 模块包名 | `io.github.qqliveclean` / 视频精简（QQLiveClean） |
| 构建 | `app/build.ps1`，javac → d8 → aapt2 → zipalign → apksigner |
| API | libxposed 102（`io.github.libxposed.api`），`staticScope=true` |
| 设备 | realme **RMX5060** · Android 16 / API 36 · 1264×2780 @560dpi |
| 环境 | LSPosed 2.2.0-it + Zygisk Next/APatch，已 root（`su -c`） |

> **版本号写在三处，改一处必须同步另外两处**，否则报告和安装包对不上：
> `app/AndroidManifest.xml`（versionCode/versionName）、
> `app/META-INF/xposed/module.prop`（version/versionCode）、
> `app/build.ps1`（产物文件名）。升版后必须 `adb shell dumpsys package io.github.qqliveclean`
> 确认装机版本，再重新生成 `dist/SHA256SUMS.txt`。

```powershell
$env:QLC_SDK='C:\实用软件开发\开发工具链\.android_build_tools\android-sdk'
$env:QLC_JDK='C:\实用软件开发\开发工具链\.android_build_tools\jdk17\jdk-17.0.19+10'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File <项目>\app\build.ps1
adb install -r <项目>\app\dist\video-clean-v0.3.13.apk
```

`tools/dexrefs.py`：解析 dex 索引表（implementors / methods / hierarchy / byreturn / refs / classes），
找锚点用它，不要靠字符串猜名字；它只决定「该挂哪里」，是否生效仍只看真机 `hit=` 行。
`reference-apk\` 下有 17.9.5 / 17.9.2 / 11.2.15 / 9.04.55 的 base APK（约 400 MB，勿删）。

## 八、设备上尚未恢复的临时设置（收尾时务必恢复）

| 项 | 当前值 | 恢复为 |
|---|---|---|
| `window/transition/animator_animation_scale` | 1（已恢复） | — |
| `screen_off_timeout` | **1800000**（本轮为测试改大） | 60000 |
| `stay_on_while_plugged_in` | **7**（本轮为测试改大） | 0 |
| `svc power stayon` | **true** | false |

```powershell
adb shell settings put system screen_off_timeout 60000
adb shell settings put global stay_on_while_plugged_in 0
adb shell su -c 'svc power stayon false'
adb shell input keyevent KEYCODE_SLEEP
```
## 九、两个必须先知道的坑（2026-10-01 19:5x）

### 1. 用户设置此前根本到不了目标进程（已修）

`event=config_source=remote_preferences … provider=IllegalArgumentException: Unknown authority`
三条投递路径全断：provider 被**包可见性**挡（shell 能 `content call`，目标进程不能）、
文件路由被 **scoped storage** 挡、`App.applyToTarget` **没有任何调用者是死代码**。
结果：用户拨开关 → 静默回退内置默认值 → UI 仍提示「已开启，重启目标应用生效」。
**在这之前，本文件里所有依赖用户开关的规则测的都是默认值。**

已修：`App.write()` 提交并 `publish()` 后，对当前页目标包调用 `applyToTarget()`。
修复后 `hidden=1 of 5` + `config_source=module_provider` —— **设置首次真正送达**。

> 排查任何「开关没效果」时，**先看 `event=config_source=` 那一行**，
> 它直接告诉你设置是从哪条路径来的、还是回退到了默认值。

### 2. 私人 DNS 去广告是长期未受控的变量

`private_dns_mode=hostname` / `t363292083.6.00p.net`，**整个优酷排查期间一直开着**，
此前没有任何一轮测试记录过它。关闭后重测：17 个控制点仍 0 命中 → **不是原因**。
但今后任何优酷广告测试**必须先写明 DNS 状态**。目前设备上私人 DNS 为**关闭**（用户要求）。

### 3. `youku_channel_filter` 尚未命中，不计入成果

- 已完成：View 层隐藏实现（避开 `p`/`e` 的 teen-mode/isSelection 守卫问题）、
  设置页「频道入口」分组（此前 `MainActivity` 根本没引用 `SHOW_CHANNEL_*`，功能是半成品）。
- 已验证：读取设置正确（`hidden=1 of 5`）。
- **未验证：无运行时 `hit=` 行**。搜索根从 `kf_root_page` 放宽到 decorView 仍不命中。
- 下一步：**把实际视图树 dump 出来**再定位，不要继续猜坐标。

> 与前贴那次正好相反：前贴是**没有观测手段**只能猜；
> 这里是有确定手段（dump 视图树）却还在猜。**有手段时不要猜。**
### 4. 视图树导出诊断已加入，但尚未跑出数据（第 11 轮遗留）

模块新增 `dumpViewTree()`：受「记录详细日志」开关控制，Activity resume 后导出一行
扁平视图树（类名 + 资源名 + 文本 + 可见性 + 子节点数 + 屏幕坐标，深度 24）。
**目的就是兑现「有观测手段时不要猜」**：用它直接定位频道栏的真实容器结构。

两个已知的实现陷阱（都踩过）：

- **视图树里不能有换行**——日志系统一条记录一行，嵌 `\n` 会导致
  `event=… tree=` 后面全部丢失（表现为「树是空的」）。已改用 `|` + 缩进点。
- **首次试跑仍无输出**：日志里只有一条旧记录。优酷会话的
  `config_source` 显示 `target_cache[ok] debugLog=true`，但 `dumpViewTree` 未被调用，
  怀疑 `Settings` 在安装期捕获、延迟 500 ms 的 runnable 读到的仍是旧值。
  下一步：把 `debugLog` 判断改成运行期读取，或在报告行里直接打印 `debugLog` 实际值，
  先确认开关到底传没传进去，再看树。

> **不要在没有确认开关值的情况下解读「没有输出」**——
> 这正是本项目反复出现的那类错误（工具坏 / 看错类 / 状态没生效，
> 都会长得像「结论」）。
## 十、第 12 轮重大发现：Activity-resume 路径是间歇性失效的（第 12 轮）

**这解释了 `youku_channel_filter` 为什么没有 `hit=` 行，也牵出另外两条规则。**

事实（全部来自日志统计，不是推测）：

- 走 resume 路径的三条规则是 `youku_tab_filter`、`youku_home_top_ad`、`youku_channel_filter`。
- `youku_home_top_ad` **全程只命中过一次**（PID 22920，第 4 轮）。
- 最近 5 个优酷进程中，这条路径的命中数**全是 0**，而同一批进程里
  `rule=youku_tab_filter status=hooked` **照常打印**、`install_summary hooked=11 miss=0` 照常打印。
- PID 4141 是最后一个真正执行到该路径的会话。

> **`hooked` + `install_summary` 正常，但拦截体一次都没被调用。**
> 这是本项目第三次出现「挂着却没被调用」，而且这次影响的不只是一条试验规则。

**换掉换行符之后树依然不导出**，当时一度以为还是日志截断；统计所有会话后才确认：
不是截断，是**这条路径在最近的会话里根本没执行**。
（顺带澄清：`tree=` 后面空白那次是 PID 4141——树**已经生成了**，
只是 `dumpNode` 第一个字符就是 `\n`，日志按行写出后只剩前缀。换行符这个 bug 是真的，但只影响那一次。）

### 最可能的根因（待验证，不要当结论）

`installBottomTabFilter` 挂在 **`Instrumentation.callActivityOnCreate`** 上，
即平台单例。若优酷在模块挂接**之前**就把
`ActivityThread.currentActivityThread().mInstrumentation` 换成了自己的子类，
我们的钩子就再也看不到调用——**时序相关，所以表现为间歇性**。

**下一步应该换锚点，而不是继续调时序**：把入口从可被替换的 `Instrumentation`
改到替换不掉的 `Activity.onCreate` / `Activity.performCreate`
（用 `ClassHandler` 或直接 hook `android.app.Activity` 的生命周期方法）。
改完必须重新统计多个会话的命中数——**只跑一次会话不算数，这个 bug 就是单次会话测出来的。**

> 纪律提醒：这条路径的规则**当前都处于「已挂接、未验证」状态**。
> `youku_tab_filter` 与 `youku_home_top_ad` 的命中行是历史遗留，
> 在锚点修好之前，不能声称它们现在仍然有效。
## 十一、第 13 轮：撤回第 12 轮的结论，频道过滤判定通过

**第 12 轮说「resume 路径间歇性失效」是错的。** 错在统计模式：

```
grep 'hit=youku_(tab_filter|home_top_ad)'      # 漏了 channel_filter
```

补上后，真实证据一直都在日志里：

```
rule=youku_channel_filter status=hooked … hidden=1 of 5
event=install_summary hooked=11 miss=0
hit=youku_channel_filter hidden=1 from channel row [电影]
hit=youku_tab_filter     hidden=3 from five-button bar
```

`[电影]` 正是设置里关掉的频道，**规则准确、可复现、判定通过**。
那批「零命中」会话的真正区别是**根本没走到首页 feed**（坐标全点空、
`playback_updates=0`、连视图树都没导出）——App 不可用，不是钩子没被调用。

### 必须固化的纪律

> **统计命中时，模式必须覆盖全部相关规则。**
> 少写一个规则名，就会把「有命中」读成「零命中」，
> 然后据此去修一个根本没坏的锚点。第 12 轮整整一轮就是这么浪费的，
> 而且中间还顺手把锚点换掉了——换锚点这件事**至今没有证据支持**。

### 锚点更换的定性

`Instrumentation.callActivityOnResume` → `Activity.performCreate`。
框架自己调用、App 替换不掉，方向正确，但**没有证据表明旧锚点曾失效**。
**不把这次更换算作修复**，跨 4 个会话验证均正常（`hooked=11 miss=0`，无 miss）。

### 当前优酷已验证清单（有 `hit=` 行）

`youku_pause_ad`、`youku_home_top_ad`、**`youku_channel_filter`**、
`youku_tab_filter`、`youku_splash_hot_switch`、`youku_csj_dsp_off`

（视频侧前贴仍为环境不可复现，见第三节。）

## 十二、第 14 轮修正：准确说法是「不可靠」，不是「间歇性误判」

第 13 轮把零命中会话解释为「根本没走到首页 feed」，并据此否定了间歇性。
**第 14 轮把 feed 变量控制住之后，这个说法要收回一半。**

受控实验（0.3.14 装机）：

```n top=com.youku.phone/com.youku.kuflix.RootPageActivity   # 确认停在首页 feed
 pid=29773   config_source=target_cache[ok] ... debugLog=true
 -> 0 条 hit，0 条 youku_viewtree（debugLog=true 时本应导出）
```n
即：**路径能跑（PID 4141 有真命中，含 channel_filter），但多数会话不跑。**
第 13 轮解释了「频道过滤其实命中过」，**没有**解释间歇性。

| 规则 | 状态 |
|---|---|
| youku_channel_filter | 有真命中（PID 4141，hidden=1 [电影]），**触发不稳定** |
| youku_tab_filter | 有真命中（PID 32236 / 4141），**触发不稳定** |
| youku_home_top_ad | 仅 1 次历史命中，**稳定性存疑** |

> **「有 hit= 行」与「可靠生效」是两件事，必须分开说。**

已排除：没进 feed、开关没送达（该修复有效）、Instrumentation 被替换（换 performCreate 后 4/4 会话仍不触发）。
剩下最可能是**模块安装时机晚于首个 Activity 创建**——
需要在拦截体第一行打计数日志，区分「一次都没进」和「进了但没匹配上」，**不要再改锚点猜**。

定位清楚之前，不要把优酷 UI 类规则算作稳定覆盖。
视频侧前贴的结论不受影响（属 17 个广告物料控制点，与 UI 路径无关）。

## 十三、第 15 轮根因定位：框架类钩子挂上了但不生效（0.3.15 运行时枚举）

按第 14 轮定的作业做了两件事：① 在拦截体第一行加无条件面包屑
event=youku_resume_entered；② 挂上 11 个 Activity 生命周期候选锚点，一次跑完看谁触发。

### 结果一：拦截体确实「一次都没进」

youku_resume_entered 在**所有进程**都是 0 行，而同一进程里
ule=youku_tab_filter status=hooked 照常打印。
排除掉了「进了但没匹配上」这种可能。

### 结果二：框架类钩子全部不触发，App 类钩子正常（关键对照）

本会话（优酷 PID 18504）：

``
event=resume_probe performStart absent
event=resume_probe performResume absent
event=resume_probe performStop absent
event=resume_probe onWindowFocusChanged hook_error ClassNotFoundException: boolean
``

- 7 个候选（performCreate/performPause/performDestroy/onCreate/onStart/onResume/onNewIntent）
  **既没有 absent 也没有 fired** → 钩子装上了，**一次都没被调用**。
- 3 个候选在 Android 16 的 ndroid.app.Activity 上**已不存在**：
  performStart / performResume / performStop。
- 同一会话里 App 类钩子**正常命中**：hit=youku_splash_hot_switch 出现两次。
- 那条 hit=youku_channel_filter 位于日志第 10911 行、属 PID 4141，
  早于探针行（12788）——**不是本会话的产物**。

> **这就是根因方向：本机 LSPosed 2.2.0-it / API 102 下，挂在
> ndroid.app.Activity 这类 boot classpath 框架类上的钩子静默不触发，
> 而挂在 App 自身类上的钩子工作正常。**
> 「静默」是最麻烦的部分——hook() 不报错，status=hooked 照常上报，
> 看起来一切正常。

### 第 16 轮的作业（方向已明确，不再猜）

**放弃框架类锚点，改挂 App 自己的 Activity 基类。**
优酷的 Activity 必然继承自它自己的基类（RootPageActivity 的父类链上那一层），
在**那个类**的 onResume / onCreate 上挂钩即可——
既是 App 类（已知可钩），又一定在 UI 创建路径上。

落地步骤：

1. 用 dex 工具（	ools/dexrefs.py hierarchy）对 com.youku.kuflix.RootPageActivity
   拉出继承链，找到优酷自己的基类。
2. 在该基类的 onCreate/onResume 上挂一个只打一次的探针，确认 App 类钩子在这条路径上会触发。
3. 触发确认后，把 youku_tab_filter / youku_home_top_ad / youku_channel_filter
   的挂点整体迁过去，并把 youku_resume_entered 面包屑升级为常规诊断行。

> 附带修正：onWindowFocusChanged 探针是我自己写错的（把 oolean 当类名传给了
> Class.forName），不是设备问题。
## 十四、第 16 轮：绕开框架类钩子，注册成功但回调从不派发

按第 15 轮定的方向做了改造：installBottomTabFilter **不再挂任何框架类钩子**，
UI 规则改由 ActivityLifecycleCallbacks 驱动（MainHook.registerUiLifecycle()）。
这是注册式 API，根本不经过 Xposed 钩子，因此绕开了整个失效面。

装机 0.3.16（versionCode 19）后的实测：

```
rule=youku_ui_lifecycle status=hooked source=ActivityLifecycleCallbacks (no framework hook)
→ 出现两次（主进程 22362 + :channel 13036），两个进程都注册成功
→ ui_lifecycle_created  0 行
→ youku_ui_pass_entered 0 行
```

**注册成功，但一个回调都没派发，连最早的 onActivityCreated 都没有。**

### 已经确定的事

1. **钩子机制本身是好的**：Instrumentation.callApplicationOnCreate / callActivityOnCreate
   上的钩子会触发（configure 能跑、settings 能解析、install_summary 正常）。
2. **ndroid.app.Activity 上的钩子静默不触发**（第 15 轮运行时枚举证实）。
3. **注册式 API 也不派发**——如果且仅如果模块拿到的 Application
   不是 ActivityThread 实际派发用的那一个。

> 现在最可能的原因：**模块拿到的 Application 实例与框架持有的不是同一个。**
> 注意 egisterUiLifecycle 是从 configure() 里调的，而 configure()
   既可能由 app 探针（callApplicationOnCreate 的实参）触发，也可能由 activity 探针
   （ctivity.getApplication()）触发；两条路拿到的未必是同一个实例。

### 第 17 轮的作业（不要再换锚点，换取值方式）

1. 在两个探针里**分别**打出 Application 实例的 identityHashCode 与 	oString()，
   确认 configure() 收到的到底是哪一个实例、是不是同一个。
2. 确认后：统一从 Instrumentation.callApplicationOnCreate 的**实参**取 Application
   （那是框架亲手传进来的、必然是活的实例），activity 探针只作为兜底不注册。
3. 派发恢复后，youku_ui_pass_entered 会带出 Activity 的继承链，
   那时才能谈「挂到优酷自己的基类上」——**那是下一步，不是这一步。**

> 附带修掉一个隐患：第 15 轮为诊断加的 RESUME_PASSED 曾让整段 UI pass 每进程只跑一次，
> 本轮已还原为每次 resume 都跑（0.5s / 3s / 6s 三次补隐藏，覆盖后到视图）。
## 十五、第 17 轮：Activity 创建这一整类事件对模块不可见（第 16 轮假设被推翻）

### 推翻：不是 Application 实例的问题

第 16 轮猜「拿到的 Application 不是框架派发用的那一个」。实测否掉了：

```
event=context_identity via=app_probe ctx=com.youku.phone.Youku id=649561 isApplication=true
event=ui_lifecycle_registering application=com.youku.phone.Youku id=649561
→ ui_lifecycle_created 仍 0 行，youku_ui_pass_entered 仍 0 行
```

拿到的是**真实的 Youku Application 实例**（isApplication=true，id 与注册处一致），
注册成功，回调照样不派发。

### 本轮更硬的发现：callActivityOnCreate 钩子从来没触发过

```
grep 'callActivityOnCreate fired'  →  0 行
```

于是整个画面变成这样：

| 事件 | 是否对模块可见 |
|---|---|
| Instrumentation.callApplicationOnCreate | ✅ 触发（拿到 Application、install_summary 正常） |
| Instrumentation.callActivityOnCreate | ❌ 从不触发 |
| Activity.performCreate / onCreate / onResume 等 7 个 | ❌ 装上零调用（第 15 轮） |
| ActivityLifecycleCallbacks 全部 7 个回调 | ❌ 注册成功，零派发 |
| **App 自身类**（如 SplashConfigInfo.getHotAdEnabled） | ✅ 正常命中 |

进程也排除过了：只有 com.youku.phone 与 com.youku.phone:channel 两个，
**模块两个都加载了**，前台 RootPageActivity 必然在其中之一。

> **结论：在优酷里，Activity 创建这一整类事件对模块完全不可见，
> 而 App 类钩子完全正常。** 模块确实在正确的进程里（同会话 App 类钩子命中两次）。

### 第 18 轮的作业（不再依赖任何 Activity 事件）

既然唯一可靠触发的是 callApplicationOnCreate，就把所有需要的东西都挂在它上面：

1. 在 callApplicationOnCreate 里 Handler.post 一次，**反射遍历
   ActivityThread.currentActivityThread().mActivities**，
   直接把当前活着的 Activity 类名与继承链打进日志。
   —— **完全不依赖 Activity 生命周期事件**，这是本项目第一次这么干。
2. 拿到优酷自己的基类后，把 UI 规则挂到**那个 App 类**上（已知 App 类钩子可用）。
3. 顺带记一笔：第 15 轮枚举已发现 Activity.performStart/performResume/performStop
   在本机 ndroid.app.Activity 上**不存在**，与「Android 16 改了生命周期派发路径」自洽，
   可能是这整类事件失联的原因，待第 1 步拿到证据后一并解释。
## 十六、第 18 轮：拿到优酷自己的 Activity 继承链（关键突破）

在唯一可靠触发的 callApplicationOnCreate 里 Handler.post 一次，
反射遍历 ActivityThread.currentActivityThread().mActivities，直接读运行态：

```
event=live_activities result=ok count=1 visible=false
  com.youku.kuflix.RootPageActivity
    < j.f1.l5.b.b < j.d.m.g.c < j.d.m.g.b
    < androidx.appcompat.app.AppCompatActivity
    < c.l.a.b < c.a.b < c.i.a.e
    < android.app.Activity < android.view.ContextThemeWrapper
    < android.content.ContextWrapper < android.content.Context < java.lang.Object
```

### 这一条同时确认了三件事

1. **模块确实在正确的进程里**：mActivities 里只有 1 个 Activity，
   就是前台那个 RootPageActivity，由本模块的反射读出。
2. **优酷自己的基类是混淆 App 类**：j.d.m.g.b（其上是 j.d.m.g.c、j.f1.l5.b.b），
   最终才到 AppCompatActivity。**这些是 App 类，而 App 类钩子已被反复证明可用。**
3. **绕开了整个失效面**：这次没挂钩子、没用生命周期回调、没碰框架方法，
   只是问运行中的进程「你现在是什么状态」。

### 第 19 轮的作业（目标明确，一步到位）

在 **j.d.m.g.b**（或 j.d.m.g.c）的 onCreate / onResume 上挂钩——
这是 UI 创建路径必经的 App 类。确认触发后，把三条 UI 规则整体迁过去：

- youku_tab_filter（底部五按钮）
- youku_home_top_ad（首页轮播卡）
- youku_channel_filter（顶部频道行）

届时 hit=youku_channel_filter hidden=1 from channel row [电影]
就会变成**当前版本可复现**的证据，而不再是 PID 4141 那个旧会话的孤证。

> 注意：live_activities 那行里的 isible= 字段名是我写错了，
> 它打的是 isFinishing()，不是可见性。下次读日志别被它误导。
## 十七、第 19 轮：迁到 App 基类锚点，三条 UI 规则全部命中（本项目最关键的一次修复）

在 j.d.m.g.b / j.d.m.g.c / j.f1.l5.b.b（第 18 轮反射读到的优酷自己的基类）
的 onCreate 与 onResume 上挂钩，驱动同一套 UI 逻辑。装机 0.3.19 实测：

```
rule=youku_ui_anchor status=hooked app base class j.d.m.g.b
rule=youku_ui_anchor status=hooked app base class j.d.m.g.c
rule=youku_ui_anchor status=hooked app base class j.f1.l5.b.b

hit=youku_home_top_ad    home carousel card collapsed
hit=youku_tab_filter     hidden=3 from five-button bar
hit=youku_channel_filter hidden=1 from channel row [电影]
```

**这是当前版本可复现的证据，不再是 PID 4141 那个旧会话的孤证。**
框架类锚点的整条死路就此结束——不是绕过，是换到了已知可用的那一侧。

### 同时排查了「是否有别的模块在干扰」

设备上共 23 个 Xposed 模块，其中多款去广告/去 VIP 模块
（io.github.taobaoadclean、io.github.didiadclean、me.neko.fckvip、me.neko.lotus 等）。
查 modules_config.db 的结论：

- **优酷 / 爱奇艺 / 腾讯的作用域里只有 io.github.qqliveclean 一个**，没有冲突。
- 唯一另一个挂到视频 App 上的是 com.flass.layoutinspect（Layout Inspector），
  挂在 com.tencent.qqlive 上——但它 modules_state.enabled=0，**处于停用状态**，
  可以排除「它抢了 Activity 钩子导致我们失效」这个猜想。

### 遗留未解（已不影响交付）

Instrumentation.callActivityOnCreate 从不触发、ndroid.app.Activity 钩子零调用、
ActivityLifecycleCallbacks 零派发——**成因仍未查明**，且既然 App 类锚点已解决交付，
暂时不值得继续投入。第 15 轮的线索（performStart/performResume/performStop
在本机 Activity 上不存在）留待日后解释。
## 十八、第 20 轮：稳定性 0/3，App 基类锚点同样不触发（含钉死性证据）

### 跨会话稳定性结果（0.3.19）

| App | 结果 |
|---|---|
| 优酷 × 3 会话 | **UI 规则命中 0/3** |
| 爱奇艺 | ✅ hooked=11 miss=0，hit=iqiyi_splash requestAdAndDownload suppressed on x02.v |
| 腾讯 | ✅ hit=mine_ad_card user-center ad provider injection suppressed |

第 19 轮那三条命中**不可复现**。

### 钉死性证据：ui_anchor_resolved

新增诊断把「到底钩了哪个方法」打出来，6 行结果全部是 App 类，
**没有框架类混进来**（R.find 沿继承链上溯的担心被排除）：

```
asked=j.d.m.g.b.onResume   declaringClass=j.d.m.g.b       static=false abstract=false
asked=j.d.m.g.b.onCreate   declaringClass=j.d.m.g.b       static=false abstract=false
asked=j.d.m.g.c.onResume   declaringClass=j.d.m.g.b       static=false abstract=false
asked=j.d.m.g.c.onCreate   declaringClass=j.d.m.g.c       static=false abstract=false
asked=j.f1.l5.b.b.onResume declaringClass=j.d.m.g.b       static=false abstract=false
asked=j.f1.l5.b.b.onCreate declaringClass=j.f1.l5.b.b     static=false abstract=false
```

### 本会话（PID 16601）完整画面

| 观测 | 结果 |
|---|---|
| 模块安装 | ✅ quick compatibility probes complete for 优酷 11.2.15 |
| live_activities | ✅ 每次会话都触发，count=1，**RootPageActivity 活着且在本进程** |
| ui_anchor_fired | ❌ 0 |
| youku_ui_pass_entered | ❌ 0 |
| ui_lifecycle_created | ❌ 0 |
| App 类钩子（被 App 自己调用） | ✅ getHotAdEnabled 等正常命中 |

> **收敛到一句话：优酷里「App 自己调用到的方法」钩子正常；
> 「Activity 生命周期」无论用框架钩子、App 基类钩子、还是生命周期回调，全部到不了。**
> 而 Activity 对象本身确实存在（反射可读到），不是没有 Activity。

### 第 21 轮的作业（不要再钩任何生命周期）

既然只有**反射 + Handler** 这条路被证明能碰到活着的 Activity，就用它：

1. 在 live_activities 已经反射拿到 Activity 的地方，直接把 UI 逻辑跑起来——
   **完全不依赖任何生命周期事件**。
2. 为覆盖「视图晚到」，用 Handler 做**有限次**补跑（如 1/2/4/8/12/16 s 共 6 次），
   **不是轮询**：固定 6 次、无 while、无定时器常驻（满足「简洁 / 省电 / 不影响流畅度」）。
3. 跑完再跨 **≥3 个会话**统计命中率，**单次命中不算数**。
4. 顺带清理：为排查加的 installResumeProbes、ui_anchor_resolved 等诊断行
   保留但都挂在「记录详细日志」开关下；egisterUiLifecycle 若最终不用则删除，
   避免死代码（pplyToTarget 那次教训：死代码 = 用户以为有、实际没有）。
## 十九、第 21 轮：反射驱动已实现，但 3/3 会话仍未触发（0.3.21，未验证）

### 做了什么

新增 YoukuRules.scheduleUiPasses(Context, Settings)：在 configure() 末尾调用，
**不再依赖任何生命周期事件**。它按固定 6 个时点（1/2/4/8/12/16 秒）反射
ActivityThread.currentActivityThread().mActivities，取到活着的 Activity 就跑一次 UI pass。

- 无轮询、无常驻定时器、无逐帧工作 —— 满足「简洁 / 省电 / 不影响流畅度」。
- indLiveActivity() 内部 catch (Throwable) { return null; }，
  **失败时静默**，这是本轮最大的问题。

### 实测结果（3 个独立会话）

```
pid=18472  驱动进入=0  UI命中=0
pid=26874  驱动进入=0  UI命中=0
pid=4451   驱动进入=0  UI命中=0
```

配置本身是正常的（config_source=target_cache[ok] youkuAdSlot=true youkuBottomBar=false
debugLog=true，youku_channel_filter status=hooked … hidden=1 of 5），
所以**不是开关没送达**，是反射那一步没拿到 Activity 或者根本没被调度。

### 一个没查清的矛盾（留给下一轮）

同一 PID 4451 里 ule=youku_tab_filter status=hooked（installAppClassResumeAnchor
的**前一行**）打印了，但紧随其后的 ui_anchor_resolved **一条都没有**。
按代码顺序这两行必然相邻，因此二者之一有我没有预料到的情况。

### 第 22 轮的作业（第一步只有一个）

1. **先把静默失败变成可见失败**：indLiveActivity() 不得再 eturn null 吞掉原因，
   要区分并上报 
o_activityThread / 
o_mActivities_field / map_empty / eflect_error:…
   各自的原因，并把每个时点是否取到 Activity 记下来。
   **一个静默返回 null 的驱动，等于没有驱动。**
2. 顺带确认上面那个矛盾：installAppClassResumeAnchor 是否真的执行到了。
3. 拿到原因再谈修法。**不要再改锚点**——锚点已经被证明不是问题（反射能读到 Activity）。

> 0.3.21 已装机但**功能未验证**，文档里不算作修复。
## 二十、第 22 轮：命中回来了，但产生它的不是反射驱动

按第 21 轮的作业把静默失败改成可见失败：indLiveActivity() 现在逐个区分并上报

o_currentActivityThread_method / currentActivityThread_null / 
o_mActivities_field /
mActivities_read_failed / mActivities_not_a_map / map_empty / 
o_live_activity /
eflect_error，并且 6 个时点每次都打一条 event=ui_tick。

### 本会话（PID 6805）实测

```
本 PID 命中数                 = 2
  hit=youku_tab_filter     hidden=3 from five-button bar
  hit=youku_channel_filter hidden=1 from channel row [电影]
本 PID ui_tick 数             = 0
本 PID youku_ui_pass_entered 数 = 0
```

**两条规则命中了，但反射驱动一条日志都没打。**
所以产生命中的**不是** scheduleUiPasses，而是另一条路径——
installAppClassResumeAnchor（App 基类钩子）或 egisterUiLifecycle（生命周期回调）。

> 这同时说明：**第 20 轮那 3 次 0/3 与本轮这次成功，用的是同一个版本的规则逻辑，
> 差别只在「哪条驱动路径这次触发了」。** 反射驱动至今一次都没被证明能工作。

### 第 23 轮的作业（唯一一件事）

**给命中行加来源标记**，让三种驱动路径可区分：

- 在 onActivityResumed(Activity, Config.Settings) 增加一个 source 参数，
  由三个调用方分别传入 nchor / callbacks / eflection；
- 三条 hit= 行统一带 src=… 后缀（例如
  hit=youku_channel_filter … [电影] src=anchor）。

之后一次会话就能回答：**到底哪条路径在可靠触发**，
再据此决定留哪条、删哪条——现在三条并存，谁可靠完全 unknowable，
这是继续浪费轮次的原因。

> 顺带记录一次操作失误：用 PowerShell 行切片改文件时 RemoveRange 成功而 InsertRange
> 失败，导致方法被整段删掉、编译失败。已修复。
> **教训：行切片要么整体成功要么整体回退，先验证参数类型再执行。**
## 二十一、第 23 轮：来源标记已上线，但 4/4 会话全部零触发（当前卡点）

按第 22 轮的作业给三条 UI 命中加了来源标记：onActivityResumed(activity, settings, source)，
三个调用方分别传 nchor / callbacks / eflection，命中行统一带 src=… 后缀。

### 4 个独立会话的实测

```
带 src= 的命中      : 0
src=anchor          : 0
src=callbacks       : 0
src=reflection      : 0
ui_tick             : 0
ui_anchor_fired     : 0
ui_lifecycle_created: 0
event=install_summary hooked=14 miss=0   （模块装载正常）
优酷 module_loaded 行数: 142（模块确实反复装载）
```

**三条驱动路径全部没有触发，一次都没有。**
模块装载没问题（hooked=14 miss=0），所以这不是「模块没加载」，也不是「App 没打开」。

顺带记录一条：event=skip_subprocess 说明子进程被跳过，
所以 com.youku.phone:channel 根本不 hook，只有主进程被处理——这条是有意为之
（手册要求「绝不 hook 子进程」），但也意味着**如果 UI 跑在子进程里，我们就永远碰不到**。
这是一个尚未被验证的可能性：**RootPageActivity 是否真的在主进程里？**
第 18 轮反射读到的 mActivities 确实来自模块所在进程，倾向于「在主进程」，但值得复核。

### 当前的卡点（连续第 3 轮）

优酷的 Activity 生命周期**默认到不了**，且**偶尔能到**（第 22 轮 1 次会话命中 2 条）。
成因始终没查明：不是实例不对、不是锚点错、不是设置没送达、不是模块没加载、
不是进程不对、不是静默失败。四种机制（框架钩子 / App 基类钩子 / 生命周期回调 /
反射轮询）全都试过，没有一种可靠。

### 可选的下一步（需要判断，不再盲试）

1. **复核 RootPageActivity 是否真在主进程**：若 UI 其实跑在子进程，
   skip_subprocess 就是唯一的原因，解法是给子进程也装模块（需评估开销与风险）。
2. **回到第 15 轮的线索**：Activity.performStart/performResume/performStop 在本机不存在，
   说明 Android 16 改了派发路径；查 AOSP 该版本 ActivityLifecycleCallbacks
   究竟由谁派发，可能直接解释「注册成功但从不回调」。
3. **接受现状并降级承诺**：把三条 UI 规则标为「尽力而为」，
   在设置页如实告诉用户可能不生效，而不是承诺已验证。

> 这是我连续第 3 轮在同一处受阻（21/22/23）。继续盲试的边际收益已经很低，
> 建议下一轮从上面三条里**明确选一条**执行，而不是再加一种机制。
## 二十二、第 24 轮：子进程理论被推翻；更要紧的是——我自己那些「0/N」读数不可靠

### 发现一：feed UI 确实在主进程

```
ActivityRecord{ com.youku.phone/com.youku.kuflix.RootPageActivity
  packageName=com.youku.phone  processName=com.youku.phone
  app=ProcessRecord{a8f8995 6553:com.youku.phone/u0a442}
ps: 6553 com.youku.phone   /   13945 com.youku.phone:channel
```

模块也装在 com.youku.phone 里。**「UI 跑在子进程、被 skip_subprocess 挡掉」这个理论被推翻。**

### 发现二（更重要）：锁屏让「0 命中」这个结论本身失效

本轮查进程时顺带读到：

```
mWakefulness=Dozing     mDreamingLockscreen=true
isSleeping=true         mLastPausedActivity=…RootPageActivity
topResumedActivity       （空）
```

**锁屏时没有任何 Activity 处于 resumed 状态，Activity 只会被 created、不会 resume。**
而第 23 轮那个 4 会话循环**只发了 keyevent 82、从未输入 PIN**，
所以那 4 次「0 命中」测的根本不是「模块失效」，而是**「App 从未 resume」**。

> **因此第 20 轮（0/3）与第 23 轮（0/4）的负结果都不可信。**
> 这不是模块的问题，是**测试脚本的问题**——
> 本项目栽过的最大一跤，一直在自己制造的测量误差里，而不是在代码里。

解锁后重测（mWakefulness=Awake）仍然是 0 命中，所以「解锁」是**必要不充分**条件；
但可以确定的是：**在锁屏条件下测出的任何 UI 生命周期结论都不成立。**

### 第 25 轮的作业（两条，都要）

1. **先修测试纪律**：所有 UI 类验证脚本，**必须先断言 	opResumedActivity 非空**再开始计时；
   没断言就等于在测一个没启动的 App。把这条写进 docs/GOAL-HANDOFF.md 顶部当硬规矩。
2. **重测命中率**：在「已解锁 + 	opResumedActivity 非空」的前提下，
   重新采 3 个会话的 src= 分布，才第一次得到**真实**的命中率数字。
   在此之前，**优酷三条 UI 规则的可靠性是未知，不是已知很差**。
## 二十三、第 25 轮：守卫通过后重测，并推翻我自己前六轮的核心结论

### 按第 24 轮硬规矩采样的 3 个会话（全部通过守门）

```
session 1  unlock ok (mWakefulness=Awake)  topResumedActivity 非空  pid=13292
session 2  unlock ok (mWakefulness=Awake)  topResumedActivity 非空  pid=17526
session 3  unlock ok (mWakefulness=Awake)  topResumedActivity 非空  pid=24466
```

### 换了过滤条件之后，看到的东西完全不同

先前按 " 15000 I/LSPosedFramework" 过滤 → 0 命中、0 驱动日志。
**去掉级别条件后**（" 15000 " -and 'qqliveclean'）：

```
pid=13292  event=context_identity via=app_probe ctx=com.youku.phone.Youku isApplication=true
           event=context_identity via=first_activity_probe ctx=com.youku.ui.activity.DetailActivity
           hit=youku_pause_ad ad_fullscreen_pause.isEnable -> "false" (r0 stays false)
pid=17526  via=app_probe ✅   via=first_activity_probe ✅
pid=24466  via=app_probe ✅
全局      ui_anchor_resolved=84 条   context_identity=26 条   W/LSPosedFramework=402 行
```

**所以：反射一直能找到活着的 Activity，App 探针一直正常。**
第 20~25 轮「第 20~25 轮驱动 0 触发」的结论**全部作废**，
根因是 grep 把 W 级别行滤掉，**不是代码没执行**。

### 仍然成立的两个真问题

1. ui_tick 仍为 0：scheduleUiPasses 在 372 行被调用（install 在 367 行已执行、
   install_summary 也打印了），但 6 个 postDelayed 的 Runnable 一次都没跑。
2. ui_anchor_fired 仍为 0：App 基类钩子确实装了（ui_anchor_resolved 84 条），
   但从未被调用。
3. 新线索：irst_activity_probe 抓到的是 **DetailActivity（详情页）**，
   不是 RootPageActivity。而 UI 规则跑在首页。

### 第 26 轮的作业

1. **所有脚本改用无级别过滤**（红线已写进文档顶部）。
2. 查清 postDelayed 的 Runnable 为何不执行：
   在 scheduleUiPasses 入口与 Runnable 入口**各打一条 H.info**
   （不是 warn，避免再次被级别过滤），确认「已调度」与「已执行」到底卡在哪一环。
3. 若 Runnable 确实不执行，改用 Activity.runOnUiThread 或在
   irst_activity_probe 已经拿到 Activity 的地方**直接同步调用**一次 UI pass。
## 二十四、第 26 轮：改用唯一被证明可达的触发点（0.3.24）

### 关键发现：Instrumentation.callActivityOnCreate 会触发

第 25 轮日志里的 ia=first_activity_probe 来自
hook(Instrumentation.callActivityOnCreate)——**这个钩子确实会触发**，
而且**每建一个 Activity 都会过一遍**，直接交出活的 Activity 实例。
（此前「callActivityOnCreate 从不触发」的判断，同样是在 W 级别被过滤的年代得出的。）

### 改动

1. MainHook 新增 ACTIVE_SETTINGS 静态字段，configure() 解析完设置后写入。
2. callActivityOnCreate 的 intercept 在 configure() 之后：
   ``
   H.info("event=ui_driver source=callActivityOnCreate activity=…")
   YoukuRules.onActivityResumed((Activity) activity, live, "callActivityOnCreate")
   ``
   ——**只要 Activity 被创建就一定触发**，不再依赖任何「生命周期回调」，也不再依赖 Handler。
3. ui_driver / ui_tick / youku_ui_pass_entered / ui_anchor_fired / ui_anchor_resolved
   全部从 H.warn 改为 **H.info**，杜绝同类测量事故复发。

### 本轮实测（3 个会话全部通过守门）

```
s1 valid   s2 valid   s3 valid
pid=22973   ui_driver=0   UI命中=0
```

**但这个采样是不完整的**：从日志里只提取到 **1 个**优酷 PID（另两个会话的 PID 没取到），
所以 **不能**据此说「新驱动不触发」——**这正是本项目反复犯的那个错误，不再犯一次**。

### 第 27 轮的作业

1. **先把 PID 提取做对**：不要用 Select-Object -Last N | Select-Object -Unique
   去重取 PID（会丢）。改用「会话开始时间戳」划窗，逐个 PID 报数，
   每个 PID 都要打印 ui_driver 与命中数，**缺的 PID 明写「未取到」而不是静默消失**。
2. 3 个会话重新统计 source=callActivityOnCreate 的触发次数。
3. 若 ui_driver 仍为 0：说明连 callActivityOnCreate 都没进，
   下一手就查 ule=context_probe_activity status=hooked 是否仍打印、
   以及这个 hook 是否在 	arget 为优酷时真的装上了。
## 二十五、第 27 轮：驱动能触发，但它只看得见 DetailActivity（0.3.25）

### 两个新事实（都是守门通过后采到的）

**事实一：callActivityOnCreate 只交出 DetailActivity，从不交出 RootPageActivity。**

```
event=ui_driver source=callActivityOnCreate activity=com.youku.ui.activity.DetailActivity
event=ui_driver source=callActivityOnCreate activity=com.youku.ui.activity.DetailActivity
event=ui_driver source=callActivityOnCreate activity=com.youku.ui.activity.DetailActivity
全局 event=ui_driver = 4    全部是 DetailActivity
```

而 UI 规则（底部五宫格 / 顶部频道行 / 首页轮播）**全都在 RootPageActivity 上**。
s2/s3 会话的守门证据是 mCurrentFocus=com.youku.phone/com.youku.kuflix.RootPageActivity，
**首页确实在前台**，但驱动一次都没看到它。

**事实二：onActivityResumed 从未打完入口日志。**

```
event=ui_pass_entry 全局计数 = 0
config_source=target_cache[ok] youkuSplash=true youkuAdSlot=true youkuPauseAd=true youkuBottomBar=false
```

配置是好的（youkuAdSlot=true、隐藏频道 1 个），ui_driver 也证明
ctivity != null && settings != null，**但入口日志一条都没有**。
最合理的解释是 **onActivityResumed 内部抛异常**，
而异常发生在 Xposed 的 intercept 里被吞掉，**不留任何痕迹**。

### 第 28 轮的作业（唯一一件事）

**把 onActivityResumed 包进 try/catch，把异常打出来。**

```
try {
    YoukuRules.onActivityResumed((Activity) activity, live, "callActivityOnCreate");
} catch (Throwable error) {
    H.error("event=ui_pass_threw", error);
}
```

在异常被框架吞掉的地方手动接住并上报——这是本项目第 N 次栽在
「异常无声消失」上（最早是 view tree 以 \n 开头被日志切掉）。

拿到异常之后才谈修法。**不要先猜 hiddenChannelNames 为 null 之类的结论。**

### 附：本轮 harness 的两次自身故障（都记下来）

1. dumpsys activity activities 输出过大 → 管道 Broken pipe → 返回空 →
   守门把**实际已启动**的会话误判成 GUARD_FAIL。
   改用 dumpsys window | grep -m1 mCurrentFocus（输出小、稳定）。
2. 设备卡在 mCurrentFocus=Window{… NotificationShade}，解锁点击全打在通知栏上。
   **解锁流程必须先 cmd statusbar collapse。**
## 二十六、第 28 轮：异常假设被证伪（0.3.26）

按第 27 轮作业把调用包进 try/catch，上报 event=ui_pass_threw。
三个会话全部通过守门（已解锁 / mCurrentFocus 命中优酷）。

```
ui_driver    = 8
ui_pass_threw = 0      ← 没有任何异常
ui_pass_entry = 0      ← 但入口日志一条都没有
```

### 结论：第 27 轮的异常假设**被推翻**

onActivityResumed 被调用 8 次，参数在驱动处已验证非空，
**既不抛异常、也打到不了自己的第一条日志**。
「内部抛异常被 Xposed 吞掉」不成立——catch 一次都没进去。

这排除了一个假设，是本轮的真实收获：
**剩下的可能性只有「方法体根本没执行到日志那一行」或「YoukuRules 侧的日志没落盘」。**

### 第 29 轮的作业

1. 在 onActivityResumed 的**第一行**（CURRENT_SOURCE.set 之前）加一条 H.info。
   第一行都不打印，就说明**方法体压根没执行**——那问题回到「谁在调用它」。
2. 同时在 MainHook 驱动处、YoukuRules.onActivityResumed(...) 调用**返回之后**再打一条，
   用「进入前 / 返回后」两条日志夹住一次调用，直接区分
   **「没进去」/「进去了但内部静默」/「进去了且正常返回」**三种情况。
3. 若确认第一行也不打印：下一步查 YoukuRules 这个类是否被正确加载——
   同一进程内 MainHook 的 H.info 能落盘（ui_driver 就是证据），
   YoukuRules 的却不能，**这本身就是一个待解释的差异**。