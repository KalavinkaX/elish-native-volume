# Elish 原生分应用音量 3.1.0-key-test1

用户反馈3.0.2的应用列表、图标和音量问题均已测试良好。本版保留这些实现，新增扬声器媒体音量键长按加速。0–100索引范围、曲线、单按精度和分应用后台不变；新增系统框架Hook仍需设备测试。

## 安装条件

设备为 elish；固件为 lineage-24.0-20261013-UNOFFICIAL-elish，Android17/SDK37。用户报告 LSPosed 2.2.1 已安装。本包使用传统 Xposed API82，实际框架加载须设备验收。

两个文件必须配套：Magisk ZIP 提供原有全局0–100与低音量曲线、常驻分应用后台；Native LSPosed APK 负责向 SystemUI 原生面板接入应用滑块。新 APK 包名为 elish.volume.nativepanel，不会替换旧的 elish.volume.panel。

## 从3.0.x升级

覆盖安装本版 Magisk ZIP 和同版 LSPosed APK 后重启。LSPosed 保留系统界面，并新增勾选系统框架 / Android（android）。无需清空规则或卸载新版组件。仅勾选系统界面时，原生分应用功能仍可用，但长按加速不会启用。

## 安装顺序

1. 在系统辅助功能设置中关闭旧服务「Elish 音量键面板」。建议随后卸载旧应用「Elish 分应用音量」，避免旧服务/旧界面的刷新继续调用 su。卸载旧 APK 不删除 /data/adb/elish_volume_steps/ 中的规则。
2. 在 Magisk 从本地安装 Elish-Volume-Steps-Magisk-v3.1.0-key-test1.zip。模块 ID 仍为 elish_volume_steps，覆盖升级即可，既有规则保留。
3. 安装 Elish-Volume-Native-LSPosed-v3.1.0-key-test1.apk。
4. 在 LSPosed 启用「Elish 原生应用音量」，作用域勾选「系统界面 / System UI」（com.android.systemui）及「系统框架 / Android」（android）。新增后者是因为实体音量键处理位于 system_server；各播放应用无需勾选。
5. 重启平板，使新后台与 SystemUI Hook 一起生效。
6. 播放 Spotify，按音量键，再点击系统音量窗口的更多按钮。新增滑块应在系统展开面板内，标签为「Spotify · 应用音量」等。

新 APK 的桌面入口只展示说明，不是控制面板，也不请求 root、不启用无障碍、不创建浮窗。

## 操作与音量含义

- 单按音量键保持系统原有小窗口，不自动弹第二个窗口。点击更多后才出现应用滑块。
- 复用此 ROM 的 Compose 原生滑块、颜色和大屏布局，与原有媒体等滑块处于同一面板。新增应用图标使用原色；原有系统滑块图标保持原生着色。
- 媒体滑块控制全局；应用滑块控制在全局音量基础上的额外衰减。应用100%表示0dB、不额外衰减，不会把全局音量调到最大。已经附加的音效保留在当前会话内，拖动到100%不卸载或重建音频链；未附加音效的应用在100%不会新建音效。会话消失超过短暂宽限期、移除规则或禁用后台时才释放。
- 应用0–100调节使用合并后的最新整数值。0%为-120dB近似静音；并非硬件静音接口。
- 如果原生控件调用图标静音回调，会在0%与本次会话上一次非零值之间切换。
- 同 UID 的包共用一个滑块，标签注明共享应用音量，无法拆分同 UID 内的包。
- 仅显示当前 Android 用户正在播放媒体的应用，不显示仅有已保存规则、未启动或暂停的应用。隐藏不删除规则，下次播放自动使用保存值；面板打开时约700ms刷新列表。关闭面板清空界面缓存，重开后重新查询。
- 关闭面板不发送静音、恢复或重置命令；已提交的最后编辑仍会完成保存。未操作的滑块在关闭时不会写入0%。
- 后台独立存活，不通过界面生命周期持有音效、不请求音频焦点、不暂停播放器。规则位于 /data/adb/elish_volume_steps/rules.tsv。

## 全局音量键长按加速

100档指101个离散索引（0到100），并非数学上的连续无级。本版保留每一档可达，改变的是长按重复事件的步幅：

| 操作 | 媒体索引变化 |
| --- | --- |
| 单按、快速连续单按 | 每次1档 |
| 持续按住，不足800ms | 每次重复1档 |
| 持续按住800–1799ms | 每次重复2档 |
| 持续按住至少1800ms | 每次重复3档 |
| 当前音量0–10档 | 始终每次1档 |

目前仅对平板扬声器（AudioService内部输出设备2）、正常音频模式的全局媒体音量启用。蓝牙、投屏、USB/有线耳机、通话和通知保留系统行为。按键经过原生路径后只调整本次步幅，不发送额外按键、不启动加音量定时器、不调用绝对音量设置；松手后不自行继续。拖动系统或应用滑块不触发加速。系统原有边界、静音和安全音量检查继续执行。

实现会核对ROM services.jar SHA256和方法签名；读取真实按键的重复次数与按住时长，仅让250ms内、方向匹配且未消费的重复事件影响一次媒体步幅。系统若不投递该按键路径、输出设备或原生步幅与预期不同，加速会跳过。该保守限制需要实机验证；不能用主机测试推定长按速度。

## 长按加速验收与撤销

1. 保持正常较低音量，先单按几次，确认每次一档；快速单按也应如此。
2. 扬声器播放时，在10档以上长按加减，检查约0.8秒后加快、约1.8秒后进一步加快；实际耗时取决于系统按键重复频率。
3. 松手确认不再自行变化。降到10档以下后恢复一档精调。
4. 拖动系统全局滑块，确认仍可停在任意整数；分应用滑块正常。通话、蓝牙和通知按原方式处理。
5. 若无加速，导出LSPosed日志查找 ElishKeys: Installed speaker media key acceleration、Applied native media repeat step 或 disabled/hash mismatch。

仅撤销加速：在LSPosed去掉本模块的Android/system framework作用域并重启，保留System UI作用域即可继续使用原生分应用滑块。也可覆盖安装已验证的3.0.2 APK并重启。

## root 与通信

新 SystemUI 组件没有 su 或 shell 调用。Magisk 开机启动后台，界面通过抽象 UNIX 本地套接字通信。后台启动时通过 PackageManager 查询用户0的 com.android.systemui UID，同时检查内核 SO_PEERCRED 的 UID 与该值一致、进程名精确为 com.android.systemui；身份解析失败则不启动 IPC，仅接受 HELLO/UI/SET 命令，限制用户、包名、数值、长度和连接数，不开放任意命令执行。

设备日志确认 SystemUI UID 为10140，实际域为 platform_app。上版假定 UID1000/system_app 不适用于此 ROM。本版不写死10140，SELinux 规则改为限定 platform_app 到 magisk 域的 UNIX socket connectto；实际 enforcing 下连通性仍须设备验证。连接失败时显示诊断并停用应用滑块，不回退成控制系统总音量。

旧 APK 若仍运行，仍可能发起旧的 su 请求。LSPosed/Magisk 本身的授权或管理通知不属于新面板的通信流程。

## 固件匹配保护

仅接受 SystemUI APK SHA-256：
7b2774f3a6e7047e7048cc2bfa8b77bd61f7efc0d80751d9bb47d85a656da6cf

按键加速另校验 /system/framework/services.jar SHA256：0a8bef13f4e649da84c64bb459dd7d1abb12c41d2a2b7cbb65f9be485376f6e6。更新ROM或模块替换相应文件时，对应Hook跳过并记录哈希不匹配，需要重新适配。启动时还核对滑块接口、状态流、工厂与可见性入口。可捕获的注入/回调异常会停用本进程新增UI并尝试还原原始列表；并不代表所有原生渲染异常已在设备验证。

## 首次验收

1. 全局音量保持较低正常值，Spotify 单独播放。单按音量键确认没有旧浮窗；点更多确认新增滑块在系统面板内。
2. 应用滑块从100%调至30%，确认该应用变小而全局数值不变。
3. 两个能保持同时播放的应用分别设30%和70%，检查独立性。如果第二应用抢占音频焦点令第一应用暂停，应先解决同时播放条件。
4. 同一应用在30%与100%之间多次拖动，重点检查到100%时有无短暂突增再回落；另一应用保持固定值。关闭面板等5秒再展开，检查声音持续与规则保存。暂停应用后应从列表消失，恢复播放后应重现并使用保存值。
5. 再测试暂停/恢复、返回桌面、息屏及重启。

此前关闭浮窗后无声的根因没有设备证据确认。本版移除旧浮窗路径，但不能预先宣称修复所有 ROM 音效/会话问题；音频后台沿用此前已经能独立调节的 session DynamicsProcessing 路径。

## 排查

- 核对旧无障碍已关闭、两个新文件都安装、SystemUI与Android作用域已勾选、安装后已重启。
- 导出包含 ElishNative: 的 LSPosed 日志。Installed native slider hooks 表示入口安装；Attached native slider list 表示数据接入。它们都不证明实际听音效果。
- Magisk 点击本模块 Action，复制状态与 Native IPC 日志。正常监听应有 Native IPC listening 和解析出的 UID，面板握手成功后应出现 Native IPC HELLO accepted；身份不匹配会记录 rejected peer。权限拒绝、类/方法缺失、哈希不匹配请保留原文。
- 以前主动禁用衰减留下的 disabled 状态会保留。需要恢复时在 root 终端执行：sh /data/adb/modules/elish_volume_steps/app-volume.sh enable，再展开面板。
- 日志含配置包名/UID，不记录歌曲标题，不联网上传。

## 回退

在 LSPosed 禁用「Elish 原生应用音量」并重启，移除 UI 注入；后台和已保存规则仍生效。

撤销应用衰减可在 root 终端执行：sh /data/adb/modules/elish_volume_steps/app-volume.sh disable。后台恢复它持有的应用音效，不改变系统总音量。

也可在 Magisk 禁用本模块并重启，同时在 LSPosed 禁用注入组件。数据目录中的规则保留以便恢复。
