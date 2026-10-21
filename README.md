# RikkaHub OC

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的 Android 自用定制版，主要改进长对话和多轮工具调用时的上下文管理。

本页只介绍这个 fork 的改动。原版的完整功能、使用说明和官方下载入口，请查看 [上游仓库](https://github.com/rikkahub/rikkahub#readme) 或 [原版简体中文文档](https://github.com/rikkahub/rikkahub/blob/master/README_ZH_CN.md)。

## 本 fork 的修改

### 增强上下文管理

- **按 token 预算管理上下文**：在发送请求和多轮工具调用之间检查容量，预留回复空间和安全余量，压缩较早的内容，保留近期原文。
- **工具结果裁剪（Prune）**：参考 OpenCode，先批量裁剪较早的工具文本结果，预算仍不足时再摘要；保留原始结果与调用参数，模型可按工具调用 ID 回查。无需额外调用压缩模型。
- **保留完整历史**：摘要独立保存，不覆盖原始消息、附件和工具结果；编辑消息或切换分支后，会校验摘要是否仍适用。
- **支持历史回查**：通过 `history_search` 和 `history_read` 查找当前对话选中分支的原文，包含工具结果和提取的文档文本，并支持分页读取。
- **保留工具调用边界**：工具调用与结果保持配对，支持压缩超长的已完成文本结果，并保留尚未交给模型处理的新媒体内容。
- **处理上下文溢出**：启用自动压缩时，遇到明确的上下文超限错误，可压缩后重试一次，不重复执行已完成的工具。
- **提供摘要查看入口**：可以查看已生效的摘要及其来源范围，检查压缩后保留了哪些信息。

这是基于范围摘要和原文回查的实现。摘要仍可能遗漏细节，保留原文和回查工具可以帮助模型找回信息，但不能保证每次都会主动找回所有细节。

### 独立设置与回退开关

在 **设置 → 上下文管理** 中配置：

| 配置 | 作用 |
| --- | --- |
| 启用增强上下文管理 | 总开关，默认关闭；关闭后恢复原有消息条数限制和手动压缩逻辑 |
| 工具结果裁剪（Prune） | 独立开关，默认关闭；保护最近两轮、新返回结果、技能、用户回答及媒体，长工具循环支持裁剪已读旧结果 |
| 工具结果保护预算与最少释放量 | 支持按窗口自动分配；达到单批净节省阈值才裁剪 |
| 额外保护的工具名称 | 指定始终不参与 Prune 的工具 |
| 自动压缩 | 控制是否按预算自动压缩；关闭后仍可手动压缩 |
| 压缩触发比例 | 设置压缩时机，同时受回复预留空间和安全余量约束 |
| 最近原文保留预算 | 控制保留多少近期原文，支持自动分配 |
| 摘要长度预算 | 控制摘要长度，支持自动分配 |
| 摘要模型 | 使用当前聊天模型，或选择独立的摘要模型 |
| 历史回查与返回预算 | 控制是否提供回查工具，以及单次返回的文本预算 |

使用前请在模型设置中填写准确的上下文容量。摘要压缩会额外调用模型并产生用量；Prune 本身不调用模型，需要启用历史回查并使用支持工具调用的聊天模型。关闭自动摘要后仍可单独使用 Prune。设置调整从下一轮生成生效。

详细设计、存储方式和验证记录见 [上下文管理说明](docs/context-management.md)。设计参考了 [OpenCode 的 Prune](https://github.com/anomalyco/opencode/blob/v1.19.3/packages/opencode/src/session/compaction.ts)、[Pi 的压缩流程](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/compaction.md) 和 [LCM 的历史保留与回查思路](https://github.com/Martian-Engineering/lossless-claw/blob/main/docs/architecture.md)。

### 自用包调整

- 应用 ID 改为 `me.rerere.rikkahub.oc`，保留原有 namespace。
- 移除官方更新检查、更新提醒和 APK 下载逻辑，后续通过手动分发升级。
- 移除 Firebase Analytics / Crashlytics 及相关构建插件，不再需要 `google-services.json`。
- 签名文件和密码配置不纳入 Git，构建及覆盖安装方法见 [自用包编译便签](docs/private-build.md)。

## 构建与验证

准备 Android 工具链、前端依赖和自己的签名配置后，构建 release：

```bash
./gradlew --no-daemon :app:assembleRelease
```

环境准备、APK 选择和签名核对步骤见 [编译便签](docs/private-build.md)。不同构建者需要使用自己的签名文件；覆盖安装现有自用包时，必须使用与旧包兼容的签名。

增强上下文管理初版已通过签名 release 构建、release 必要 Lint 检查和 36 项相关 JVM 测试。本次 Prune 增强已通过签名 release 构建、release 必要 Lint 检查与 29 项上下文 JVM 测试，版本为 `3.1.0-oc.2` / `195`；三个架构 APK 的包名、版本号和原签名均已核验。数据库从 32 升到 33，新增独立摘要表；Prune 不新增数据库迁移。安装及迁移说明见编译便签。

## 上游与许可

原项目由 RikkaHub 上游作者和贡献者开发，本 fork 的修改建立在其工作之上。原版内容请以 [官方仓库](https://github.com/rikkahub/rikkahub) 为准；本 fork 的问题请在 [本仓库 Issues](https://github.com/xiaoyi198/RikkaHub-oc/issues) 反馈。

本项目沿用 [AGPL-3.0 许可证](LICENSE)。
