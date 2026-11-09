# Elish Native Volume｜小米平板 5 Pro 原生分应用音量

为小米平板 5 Pro（`elish`）特定 LineageOS 固件制作的 **Magisk 模块 + LSPosed 模块**：降低扬声器低档音量、提供全局 0–100 精细调节、在系统展开音量面板中加入分应用滑块，并为实体音量键增加长按加速。

当前版本：**3.1.0-key-test1**。作者设备上已测试：独立应用音量、原生面板集成、应用图标、活动应用列表、100% 切换以及长按加速，反馈均正常。这是特定设备和固件上的验证，不能推定其他 ROM 兼容。

## 下载

前往本仓库 **[Releases](../../releases)** 下载同版本 Magisk ZIP 和 Native LSPosed APK，以及 SHA256 校验文件。两个组件配套安装；源码 ZIP 无需刷入。仅从可信来源安装。

## 适用范围

| 项目 | 当前适配 |
| --- | --- |
| 设备 | 小米平板 5 Pro，设备代号 `elish` |
| 固件 | `lineage-24.0-20261013-UNOFFICIAL-elish.zip` |
| 系统 | LineageOS 24 / Android 17 / SDK 37 |
| Root | Magisk；安装脚本要求版本代码至少 20400，推荐使用当前设备已正常工作的版本 |
| Hook 框架 | 作者设备使用 LSPosed 2.2.1，传统 Xposed API 82 |
| 系统界面 | 精确匹配已适配的 SystemUI APK 哈希 |
| 长按加速 | 精确匹配已适配的 services.jar；当前仅扬声器媒体音量 |

不适用于直接刷入 MIUI/HyperOS，也未验证小米平板 5、5 Pro 5G、其他型号或其他 LineageOS 构建。固件文件名相同也应核对实际文件。更新 ROM 后，哈希保护可能使相应 Hook 跳过，需要重新适配。

## 功能

- 全局媒体音量范围为 `0–100`，共 101 个整数索引；每个值都可通过原生滑块或单按微调到达。它不是数学意义的连续无级，也不代表声压线性百分比。
- 调整已识别的扬声器媒体音量曲线，让低档更安静；不为未知 XML 格式强行打补丁。
- 按音量键保持系统小窗口，点击“更多”后，在原生展开面板里显示应用音量滑块。没有额外浮窗或无障碍服务。
- 只列出当前用户正在播放媒体的应用，图标使用原色；暂停或退出后隐藏，保存的音量规则保留。
- 应用音量是在全局媒体音量基础上的额外衰减；应用 100% 为 0 dB、不额外衰减，0% 为约 −120 dB 的近似静音。
- 调到 100% 时保留已有会话音效并设为 0 dB，避免在滑块端点卸载、重建音频链。关闭面板不停止后台、不重置声音。
- 全局媒体音量键长按加速，低档继续精调，界面不反复请求 `su`。

### 长按速度

| 操作 | 每次系统重复事件的步幅 |
| --- | --- |
| 单按、快速连续单按 | 1 档 |
| 按住不足 0.8 秒 | 1 档 |
| 按住 0.8–1.8 秒 | 2 档 |
| 按住至少 1.8 秒 | 3 档 |
| 当前音量 0–10 档 | 始终 1 档 |

当前只加速正常音频模式下的扬声器全局媒体音量。蓝牙、投屏、有线/USB 耳机、通话与通知保持系统行为；拖动系统及应用滑块不触发加速。实际速度取决于系统按键重复频率。

## 安装

1. 确认设备、ROM、Magisk 和 LSPosed 符合上表，保留可恢复的刷机环境。
2. 如使用过旧 Elish 浮窗版，先关闭旧“音量键面板”无障碍服务，建议卸载旧浮窗 APK。
3. 在 Magisk“模块 → 从本地安装”选择本版 ZIP。
4. 安装同版本 `Elish-Volume-Native-LSPosed` APK。Magisk 中含 APK 文件不等于已安装应用。
5. 在 LSPosed 中启用“Elish 原生应用音量”，勾选 **系统界面 / System UI（`com.android.systemui`）** 和 **系统框架 / Android（`android`）**。不需要勾选播放应用。
6. 重启平板。播放音乐，按音量键，再点击系统音量窗口的“更多”。

升级可覆盖安装 ZIP 和 APK，保留上述作用域并重启。规则保存在 `/data/adb/elish_volume_steps/rules.tsv`，不要为升级清空它。应用包名 `elish.volume.nativepanel`，Magisk 模块 ID `elish_volume_steps`。

## 实现方式

### 1. 全局范围与低音量曲线

Magisk 在 AudioService 初始化前设置 `ro.config.media_vol_steps=100`，默认值属性为 10；不会主动按比例重写系统已保存音量。安装脚本识别 `/vendor/etc/audio_policy_engine_stream_volumes_mi.xml` 内的目标扬声器曲线，生成模块覆盖文件，保留安装源文件以便排查。未知设备或 XML 格式会中止安装。

### 2. 分应用音频后台

Magisk 启动独立 `app_process` Java 后台。后台读取播放配置，将包名和用户映射为 UID，再对安全、独立的正数音频 session 使用 `DynamicsProcessing` 输入增益。比例到分贝换算为 `20 × log10(percent / 100)`；0% 使用 −120 dB。包含已在设备上使用的 AAudio 播放路径。

增益预先写入后再启用音效，拖动时复用已有对象。短暂播放快照缺口有宽限期；100% 保持已有会话对象、仅设为 0 dB。移除规则、禁用后台或会话结束后释放自己持有的音效；不抢占其他 AudioFX 的控制权。

同 UID 的包共享滑块；共享或无法安全隔离的音频会话会跳过并记录状态。它不绕过音频焦点：若两个播放器互相暂停，应先让它们能同时播放。并非所有播放器、硬件直通或其他音效组合都保证兼容。

### 3. 原生 UI 与本地通信

LSPosed 在 SystemUI 中把应用滑块模型合并到目标 ROM 的原生 StateFlow 列表，复用 Compose 滑块、布局和无障碍接口。只为新增应用图标使用不染色的原生图标入口。展开期间查询活动应用，关闭后清空 UI 缓存，音频后台持续运行。

UI 通过抽象 UNIX socket 与后台通信，不执行 `su`。后台根据 PackageManager 查询 SystemUI UID，并检查内核 `SO_PEERCRED` 和精确进程名，只接受有界的 `HELLO/UI/SET` 协议。设备实际 SystemUI 属于 `platform_app`，模块仅添加该域到 Magisk 的 socket `connectto` 规则；其他进程仍受应用层身份检查限制。

### 4. 长按加速

系统框架 Hook 观察原生音量键事件的方向、重复次数和按住时长，仅在短时间内消费一次匹配事件。在 AudioService 执行原生媒体音量调整时缩放本次标准步幅，保留系统原有边界、静音和安全音量检查。没有模拟按键或持续调音量的定时器。

哈希不匹配、接口缺失或可捕获 Hook 异常时跳过/停用对应功能。哈希保护和异常处理不能保证消除所有系统兼容风险。

## 排查与回退

- 没有应用滑块：确认配套 APK、SystemUI 作用域、重启以及播放器确实在播放。
- 长按不加速：确认 Android/系统框架作用域、扬声器输出、音量高于 10。LSPosed 日志搜索 `ElishKeys:`。
- 导出 LSPosed 模块日志，并在 Magisk 点击本模块 Action 导出后台状态。分享前删除无关应用和个人信息。
- 仅停用加速：取消 Android 作用域并重启，保留 SystemUI 作用域。
- 仅移除新增 UI：在 LSPosed 禁用组件并重启。后台规则不会因此自动清除。
- 停用应用衰减：root 终端执行 `sh /data/adb/modules/elish_volume_steps/app-volume.sh disable`。
- 全部停用：在 Magisk 禁用模块、LSPosed 禁用组件后重启。再次启用可保留原规则。

## 源码与构建

原始项目代码采用 **GPL-3.0-or-later**；Magisk 安装器及其许可说明保留于模块内。ROM、SDK、框架二进制和 Xposed 实现不随源码发布。

查看 [构建指南](docs/BUILDING.md)、[设备测试清单](docs/TESTING.md)、[更新记录](CHANGELOG.md)。当前发布前通过 78 项主机测试、目标 ROM DEX 接口检查、APK 签名与对齐检查；设备上的功能反馈来自作者个人平板，主机测试不模拟真实听音。

```text
work/native/module/        Magisk 脚本、曲线补丁与音频后台源码
work/native/hook/          LSPosed 原生 UI 与音量键 Hook
work/native/host-tests/    后台 Java 测试夹具
work/native/client-tests/  IPC 与按键策略测试夹具
work/native/panel/         旧滑块合并逻辑的回归测试源码（不打包到 APK）
work/native/test_*.py      主机测试
docs/                     构建、测试和酷安帖子草稿
```

本项目源于实际设备调试，开发过程使用 AI 辅助，最终设备验证由作者完成。欢迎附设备代号、ROM 精确版本、复现步骤与脱敏日志提交 Issue；请勿将它直接作为跨机型通用模块传播。
