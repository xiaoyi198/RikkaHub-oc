# RikkaHub 自用包编译便签

已完成包含 Prune 的签名 release 构建、29 项上下文 JVM 测试和 release 必要 Lint 检查。三个 APK 的包名、版本号和签名均已验证，尚未在手机上验证覆盖安装。

## 安装身份

`app/build.gradle.kts` 当前使用：

```kotlin
applicationId = "me.rerere.rikkahub.oc"
namespace = "me.rerere.rikkahub"
versionCode = 195
versionName = "3.1.0-oc.2"
```

覆盖安装要求应用 ID 与已安装包一致，签名与旧包兼容，版本号不能低于已安装版本。日常升级请递增 `versionCode`；当前版本 195 可升级此前分发的 194，但仍需以手机实际安装版本为准。`versionName` 不决定能否覆盖安装。

必须构建 `release`。本项目 `debug` 有 `.debug` 后缀，使用调试签名，不能覆盖这个自用包。保留 `namespace`，不批量改 Kotlin 包名；Manifest 中的文件和文档 Provider authority 已使用 `${applicationId}`，会跟随应用 ID。

## 原签名文件

已取回旧版实际使用的 `rikkahub-oc.jks`，复制到 `app/rikkahub-oc.jks` 并配置本地签名，证书指纹已核对一致。不得重新生成同名文件替代原密钥。当前仍没有旧 APK，尚未实测核对旧包信息；证书指纹或 APK 中的公钥证书无法代替签名私钥。

预期别名：`rikkahub-oc`。预期证书 SHA-256：

```text
2D:89:34:2B:AB:D7:D5:2D:39:AF:CF:B9:CA:15:45:DC:EB:63:34:01:2A:2C:6C:D2:3F:19:88:19:46:98:87:6B
```

把原 JKS 放到 `app/rikkahub-oc.jks`，在项目根目录的 `local.properties` 中配置：

```properties
sdk.dir=<本机 Android SDK 绝对路径>
storeFile=rikkahub-oc.jks
storePassword=<原 keystore 密码>
keyAlias=rikkahub-oc
keyPassword=<原私钥密码>
```

`storeFile` 按 `app/` 目录解析，也可以直接填原 JKS 的绝对路径。不要提交 JKS 或密码；`local.properties`、`*.jks`、`*.keystore` 已在 Git 忽略规则中。找回 JKS 后，可先用 `keytool -list -v -keystore app/rikkahub-oc.jks -alias rikkahub-oc` 核对指纹，密码在终端提示时输入。

## 更新与 Firebase

已移除官方应用更新请求、抽屉更新卡片、APK 下载逻辑和通知设置中的更新提醒入口。自用包通过手动分发升级；以后若接自己的 fork，需要显式加入自己的更新源。

已移除应用模块的 Google Services / Firebase Crashlytics 插件、Firebase Analytics / Crashlytics 依赖和相关调用，不再需要 `app/google-services.json` 或 CI 的 `GOOGLE_SERVICES_JSON` secret。本地崩溃记录功能仍由 `CrashHandler` 提供。

## 构建环境与命令（以后执行）

与仓库 CI 保持一致可使用 JDK 21；本项目的 Java/Kotlin 字节码目标是 17，这与运行 Gradle 的 JDK 版本是两件事。使用仓库 Gradle Wrapper，按 Gradle 配置准备 Android SDK 37（minor API 2）、Build Tools，以及原生模块需要的 NDK/CMake。

构建还会通过 `web:preBuild` 执行 `pnpm run build`，因此需要 Node.js、pnpm 和已安装的前端依赖。当前 CI 使用 Node 22 / pnpm 11。首次准备前端依赖：

```bash
cd web-ui
pnpm install --frozen-lockfile
cd ..
```

克隆时还需要初始化 `material3/material-color-utilities` 子模块。工具链准备好后，在项目根目录构建：

```bash
./gradlew --no-daemon :app:assembleRelease
```

本机也已有 `.devtools/build.sh`，可用 `.devtools/build.sh --no-daemon :app:assembleRelease` 激活工作区工具链；无参数时它默认构建 debug，请明确传入 release 任务。

常用产物：

- `app/build/outputs/apk/release/app-arm64-v8a-release.apk`：64 位 ARM 手机。
- `app/build/outputs/apk/release/app-x86_64-release.apk`：x86_64 设备/模拟器。
- `app/build/outputs/apk/release/app-universal-release.apk`：包含上述两个 ABI。

## 交付与覆盖安装自检（以后执行）

`aapt2` 和 `apksigner` 位于 Android SDK 的 `build-tools/<版本>/` 中；`adb` 位于 `platform-tools/` 中。把相应目录加入 PATH 或使用绝对路径。

```bash
aapt2 dump badging app/build/outputs/apk/release/app-arm64-v8a-release.apk | head -n 2
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk
adb shell dumpsys package me.rerere.rikkahub.oc
```

确认新 APK 的包名为 `me.rerere.rikkahub.oc`，`versionCode` 不低于手机实际版本，证书 SHA-256 等于上面的指纹，`apksigner` 退出成功且输出 `Verifies`。`--verbose` 用于显示明确的验证结果。恢复到原电脑或拿到旧 APK 后，也应对旧包执行同样的包名、版本号和证书检查。

先在手机内导出一份应用备份，再覆盖安装：

```bash
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` 优先核对已安装包与新包的签名兼容性；应用 ID 不同通常会安装成另一个应用。版本回退还可能出现 `INSTALL_FAILED_VERSION_DOWNGRADE`。不要为了绕过这些错误先卸载，也不要清除应用数据。

覆盖安装成功还需要验证首次启动和数据迁移。本次上下文管理将数据库从 32 升到 33，使用新增摘要表的迁移，不清空原会话。旧自用包若改过数据库结构或使用更高数据库版本，仍需单独核对迁移；包名、签名和版本号正确不能证明数据库兼容。关闭增强上下文开关只恢复请求逻辑，数据库版本仍是 33，不能据此直接降级安装旧 APK。

首次验证应检查旧会话、助手、模型设置、附件和工作区能否正常读取，再分别验证增强上下文关闭和开启时的聊天。当前这些设备检查尚未执行。

## 构建验证记录

2026-10-21 构建成功：应用 ID `me.rerere.rikkahub.oc`，版本 `3.1.0-oc.1` / `194`。ARM64、x86_64 和 universal APK 均通过 `aapt2` 安装信息检查及 `apksigner verify --verbose --print-certs` 验证，证书指纹与上文一致。各 APK 的文件 SHA-256、大小和签名验证结果保存在产物目录的 `verification.json` 中。

相关测试全部通过：`AgentLoopTest` 22 项、`ContextProjectionTest` 7 项、`ManagedContextTest` 7 项。Room 生成的 schema 33 与 schema 32 对比只增加 `context_summary`，已有表未变，迁移 SQL 已用 SQLite 验证。

2026-10-21 Prune 试用版构建成功：版本 `3.1.0-oc.2` / `195`，沿用相同应用 ID 和签名，Prune 不新增数据库迁移。ARM64、x86_64 和 universal APK 均通过包名、版本号与签名检查，release 必要 Lint 检查通过。上下文 JVM 测试 29 项全部通过：`ContextProjectionTest` 7 项、`ContextPruningTest` 11 项、`ManagedContextTest` 11 项。

便于试用的重命名 APK、`verification.json`、`SHA256SUMS.txt` 和试用说明位于 `app/build/outputs/distribution/3.1.0-oc.2/`。ARM64 文件名为 `RikkaHub-OC-3.1.0-oc.2-prune-arm64-v8a.apk`，SHA-256：`46f07a2f25b1e60c3043ccf9fb5c367133971504000ecfe2cc5316176cd40641`。
