# 构建指南

源码保留 `work/native` 布局，输出生成到仓库根目录 `outputs/`。需要 Python 3、JDK（建议17或以上，含 javac）、Git Bash（Windows主机测试）、Android SDK API36、build-tools36、R8 8.13.25、编译用 Xposed API82。目标系统为 API37；API36编译并不代表通用兼容。

## 本地依赖

- `work/android-sdk/android-36/android.jar`，或设置 `ELISH_ANDROID_JAR`。
- `work/android-sdk/android-16/` 内放 build-tools，或通过 `ELISH_BUILD_TOOLS` 指向官方 SDK 的 `build-tools/36.0.0`。目录名只是既有布局，不表示实际工具版本。
- R8：`work/r8.jar`，或 `ELISH_R8_JAR`。下载：https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.13.25/r8-8.13.25.jar
- Xposed API：`work/native/api/xposed-api-82.jar`。下载：https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar
- 设置 `JAVA_HOME` 或 `ELISH_JAVA_HOME`；构建不依赖作者本机 JDK 路径。
- 从自己合法获得的目标 ROM 提取 `SystemUI.apk` 与 `services.jar`，设置 `ELISH_TARGET_SYSTEMUI`、`ELISH_TARGET_SERVICES`。仓库不提供 ROM 下载或重分发。

预期 SystemUI SHA256：`7b2774f3a6e7047e7048cc2bfa8b77bd61f7efc0d80751d9bb47d85a656da6cf`。

预期 services.jar SHA256：`0a8bef13f4e649da84c64bb459dd7d1abb12c41d2a2b7cbb65f9be485376f6e6`。

## 自行签名

用 JDK 的 keytool 交互式生成自己的 PKCS12 签名密钥，放在仓库外。默认构建路径为 `work/panel-signing.p12`、别名 `elish-panel`，密码从 `work/panel-signing-password.txt` 读取。也可设置：

```powershell
$env:ELISH_KEYSTORE = 'D:\private\my-key.p12'
$env:ELISH_KEYSTORE_PASSWORD_FILE = 'D:\private\my-key-password.txt'
$env:ELISH_KEY_ALIAS = 'elish-panel'
python work/native/build_native.py
```

私钥、密码、SDK和ROM都已加入忽略规则；请勿提交。Release APK使用原作者密钥，仓库不提供该密钥。自行签名 APK 无法直接覆盖不同签名的已安装 APK；保持同一密钥才能继续覆盖升级。主机测试还可通过 `ELISH_TEST_BASH` 设置 Bash 路径。

构建会编译后台与 APK、验证精确 ROM ABI、验证签名与 zipalign、运行全部主机测试，然后生成 ZIP、APK、源码包、说明与SHA256。首次构建会在忽略的 evidence/ 目录生成编译证据，不需要下载其他来源快照。

直接重打包现有 Release ZIP不等于重新编译源码。发布新版本时应先更新 module.prop、manifest、Java显示版本和 build_native.py，再完整构建并设备测试。构建签名和时间戳可能导致二进制字节不同，不承诺逐字节可复现。
