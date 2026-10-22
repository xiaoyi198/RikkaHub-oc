# 上下文管理审计：源码核实版

- 核实日期：2026-10-21。
- 源码快照：`HEAD = origin/master = 61cd5833`；本地 `upstream/master = 815e9c20`。未刷新远端引用。
- 功能范围：`a62a2ee5`（上下文压缩与历史召回）、`61cd5833`（工具结果裁剪与按调用回查）。上游合并事项另列，不作为上下文功能缺陷。
- 方法：对照当前源码、现有测试源码及本地 Git 引用进行静态核实。**未编译、未运行测试、未执行 Spotless、未做设备或 provider 集成验证。**
- 本文保留旧稿条目编号，便于对照；替代旧稿的结论、优先级与修复建议。源码链接的行号对应上述快照。

## 结论与处理顺序

值得优先处理的是摘要投影时间不稳定、手动压缩退出条件失效、摘要提交结果语义不一致，以及恢复失败覆盖原始异常。裁剪回查和索引事务也有明确的改进空间。

不将全部条目统一定为 P1：源码能够确认控制流，不代表已经复现用户影响、并发时序或实际缓存损失。本文分别标注“源码确认”“条件性后果”“待验证”和“设计取舍”。

| 旧条目 | 核实结论 | 建议 |
| --- | --- | --- |
| C1 | 摘要时间每次重新生成；开启时间提醒时会影响请求文本与时间判断 | 优先修复 |
| B1 | “没有可压缩历史”分支不可达；特定手动压缩场景会再次摘要已有摘要 | 优先修复退出条件，保留合理的摘要合并能力 |
| B3 | 来源失效有返回 false、抛异常、保存后再返回 false 三种路径 | 优先统一提交结果与失效处理 |
| A3 | 准备阶段不在恢复窗口内；恢复回调抛错会覆盖原始生成错误 | 优先保留异常因果，另行明确准备阶段恢复策略 |
| D1 | 空或重复调用 ID 会使标记中的精确回查失败；整条消息仍可回查 | 补防护与可执行的回查提示 |
| 索引事务 | 变更文档的解析在数据库写事务内进行 | 缩短事务，性能收益待测量 |
| A1、A2 | 行为变化成立；“没有替代机制”及“必须自动截断”的结论不成立 | 先明确产品策略，避免直接恢复旧截断 |
| B2、B4 | 存在值得验证的状态与并发边界 | 补可控复现，暂不声称会话永久卡死或必然重复付费 |
| E | 上游渠道、自更新和 fork 策略的整合事项 | 单独保留 rebase 核对清单 |

## 1. 可确认且值得优先处理的问题

### C1：摘要投影时间不稳定，影响时间提醒

**源码确认：** [ContextProjection.kt:87](../app/src/main/java/me/rerere/rikkahub/data/ai/context/ContextProjection.kt#L87) 使用 `UIMessage.user(...)` 构造摘要，只覆盖 `id` 和 `isSynthetic`。`UIMessage.createdAt` 默认取当前时间，因此相同来源和摘要在不同投影中会获得不同时间。

[TimeReminderTransformer.kt:24](../app/src/main/java/me/rerere/rikkahub/data/ai/transformers/TimeReminderTransformer.kt#L24) 仅在 `enableTimeReminder=true` 时生效；该助手设置默认关闭。启用后，转换器将所有 USER 消息都视为时间提醒候选，没有排除合成摘要，并通过 `messages[i - 1]` 选择上一条时间参考。

**条件性后果：** 摘要成为第一条 USER 时，会按投影时刻生成提醒。跨时刻请求的提醒文本可能变化，影响其后前缀的缓存复用；后续原始用户消息也可能与一个较新的合成时间比较，得到负间隔。未测量任何 provider 的实际缓存命中率，不能声称每次都会全部失配。

**修复方向：**

- 给摘要设置稳定且符合历史顺序的时间，优先考虑来源范围末条消息的时间。`summary.createdAt` 虽稳定，却是摘要生成时间，不能直接等同于历史事件时间。
- 避免合成消息触发真实用户的时间提醒；同时检查“上一条消息”的选择，防止只跳过触发条件，却仍拿合成消息作时间参考。
- 用重复投影和时间提醒组合验证最终请求文本的稳定性，而不只断言摘要 ID 相同。

### B1：手动压缩的无候选退出分支不可达

**源码确认：** [ManagedContext.kt:94](../app/src/main/java/me/rerere/rikkahub/data/ai/context/ManagedContext.kt#L94) 的外层条件为：

```kotlin
if ((!force || (firstRaw < 0 && changed)) && !due) {
    // ...
    if (force && !changed) error("没有可压缩的历史。请减少保留的近期消息数。")
}
```

当 `force=true` 时，进入外层分支必须满足 `changed=true`，因此内部的 `force && !changed` 永远不成立。在可压缩前缀已经全部由摘要组成、`tailStart > 0` 的手动压缩场景中，代码仍可能从索引 0 再次生成摘要。

[ContextProjection.kt:81](../app/src/main/java/me/rerere/rikkahub/data/ai/context/ContextProjection.kt#L81) 对相同来源数量优先选择更新的摘要。因此，对同一来源重复生成的摘要可能取代先前投影，增加用量并进一步损失摘要细节。

**边界：** 原始消息和旧摘要并未被这条路径删除。“不可回退”应具体理解为当前摘要 UI 没有选择旧版本的入口，不能等同于原文不可恢复；关闭增强模式也会停止使用增强摘要。

**修复方向：** 明确手动压缩在“无新增原文”时是提示无可压缩内容，还是允许用户显式重新摘要。自动压缩可能需要合并多个旧摘要，不能一概要求候选必须包含原始单元。对重复处理同一来源的情况，应验证实际收益及停止条件；现有 32 次上限和单次缩减检查不能替代清晰的无候选语义。

### B3：摘要提交的成功、失效和失败语义不一致

**源码确认：** [ChatService.kt:891](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt#L891) 有三处不同处理：

1. 预检查 incarnation 或来源不匹配时返回 `false`。
2. `updateMetadata` 的 transform 中来源不匹配时抛异常。
3. `saveContextSummary` 完成后再次检查来源，仍可能返回 `false`。

[ManagedContext.kt:147](../app/src/main/java/me/rerere/rikkahub/data/ai/context/ManagedContext.kt#L147) 把所有 `false` 都转换为“摘要未应用”的异常。来源在挂起点前后发生变化时，可能出现摘要行已经保存、调用方却得到“未应用”的结果。异常向上传播后会进入生成失败处理并暂停消息队列；具体竞争时序尚未复现。

**更正旧稿：** 编辑正文、删除消息或切换分支可以影响来源匹配；翻译仅更新 `translation` 字段，见 [ConversationMessages.kt:49](../app/src/main/java/me/rerere/rikkahub/data/model/ConversationMessages.kt#L49)。来源指纹只包含 role、checkpoint 标记和 parts，见 [ContextProjection.kt:37](../app/src/main/java/me/rerere/rikkahub/data/ai/context/ContextProjection.kt#L37)，不能把普通翻译更新直接列为来源失效触发器。

**修复方向：** 将“未提交”“已保存但当前分支已失效”“持久化错误”区分清楚，统一校验边界与返回语义。继续保留 incarnation 和来源匹配保护。来源变化后要明确取消本步、从最新会话重建，或提示用户重试；不能简单吞掉 `persist=false`，继续用旧历史或超预算请求生成。`updateMetadata.transform` 当前返回 `Conversation`，也不能原地改成返回布尔值。

### A3：准备阶段错误和恢复失败是两个不同问题

**源码确认：** [AgentLoop.kt:89](../ai/src/main/java/me/rerere/ai/agent/AgentLoop.kt#L89) 的 `prepareStep` 位于生成请求的 `try` 外；准备期间的预算、摘要或持久化错误不会进入 `recoverContext`。

另一个独立问题在 [AgentLoop.kt:106](../ai/src/main/java/me/rerere/ai/agent/AgentLoop.kt#L106)：provider 生成失败后，如果 `recoverContext` 自身抛异常，原始生成错误就会被替换。[ChatRequestFactory.kt:176](../app/src/main/java/me/rerere/rikkahub/data/ai/ChatRequestFactory.kt#L176) 中的恢复回调确实会再次调用可能失败的 `managed.prepare(force=true)`。

**更正旧稿：** 初次准备阶段就失败时，聊天 provider 请求尚未发出，因此不是一个真实 provider 413 被覆盖。只有先收到生成错误、随后恢复回调失败，才存在异常覆盖。当前溢出识别依赖错误文本，不能把任意 HTTP 413 都视为上下文溢出。

**修复方向：** 保留原始生成错误，并附带恢复失败原因；显式传播 `CancellationException`，不能用泛化的 `runCatching` 吞掉取消。若准备阶段也需要恢复，应先定义可恢复的错误类别。仅把 `prepareStep` 移进 `try` 不够，因为中文预算错误并不满足当前 `isContextOverflow` 的匹配规则，也不应让所有准备错误触发重复摘要。

## 2. 应补强的防护与性能边界

### D1：标记中的精确回查依赖有效且唯一的调用 ID

**源码确认：** [ContextPruning.kt:81](../app/src/main/java/me/rerere/rikkahub/data/ai/context/ContextPruning.kt#L81) 始终把 `toolCallId` 写入 marker，没有检查非空或唯一性。[ContextHistoryTools.kt:168](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/ContextHistoryTools.kt#L168) 会拒绝空 ID 或匹配数量不为 1 的请求。`ChatCompletionsAPI.kt:759` 存在空 ID 兜底，但尚未验证实际 provider 响应能否沿完整执行链进入可裁剪历史。

**后果边界：** 若异常 ID 进入符合裁剪条件的历史，标记建议的精确调用会失败。原始消息仍在；[ContextHistoryTools.kt:137](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/ContextHistoryTools.kt#L137) 允许省略 `tool_call_id`，按消息 ID 分页读取。因此应表述为“精确回查提示不可靠”，而非“原文不可恢复”。

**修复方向：** 只有 ID 非空且在完整原始消息内唯一时才生成精确回查标记，否则保留结果或提示按消息 ID 回查。唯一性检查应针对原始消息，而不是已按 Tool part 分割的单个投影单元。缺失来源、无效参数和歧义匹配可返回简短、可执行的工具错误，避免完整堆栈写入后续历史。

### 索引：文档解析位于写事务内

**源码确认：** [ContextSummaryRepository.kt:34](../app/src/main/java/me/rerere/rikkahub/data/repository/ContextSummaryRepository.kt#L34) 每次索引都进入写事务并读取当前会话的索引指纹；对新增或变更消息，事务内调用 `contextTranscript(..., readDocumentContent)`，可能解析 PDF、DOCX 等文档。指纹未变化的消息会跳过解析，不能声称每步都会重解析全部文档。

**建议：** 在事务外准备变更消息的指纹与文本，在短事务内复查会话存在性并提交差量；保留并发删除保护。分页回查也会重新构造 transcript，可评估按内容指纹缓存。数据库锁等待、解析耗时及优化收益均未测量。

### 摘要与索引生命周期

**源码确认：** 当前摘要仓库没有主动清理失效或被取代摘要的流程，读取时会加载并 decode 全部会话摘要，decode 失败的行被忽略。会话删除已有摘要外键级联与 FTS 删除触发器，不能称为完全不清理。

关闭回查后不再运行 `index`，所以之后删除或编辑消息留下的旧 FTS 内容可能继续存储，直到再次索引或删除会话。现有 `history_search` 会按当前分支及指纹过滤结果，见 [ContextHistoryTools.kt:53](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/ContextHistoryTools.kt#L53)；这属于存储保留和清理策略问题，不能直接推导为工具会返回已删除内容。

可增加派生数据清理入口或失效记录回收策略，并在长会话上测量摘要加载与投影成本。是否保留旧摘要用于将来的版本选择，需要与清理策略一起决定。

## 3. 行为变化成立，但需要先明确产品策略

### A1：增强模式绕过旧工具输出截断

[ChatToolFactory.kt:97](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt#L97) 确实在总开关打开后跳过旧输出包装。旧阈值是 `32 * 1024` 个字符，并只在可读取落盘结果的本地 Rootfs 工作区启用；没有这类工作区时，旧路径本来就可能返回完整输出。

但 `prune=false` 不等于所有替代机制不可用：`autoCompress` 默认开启，增强路径会先检查实际转换后的请求预算，并可摘要单个巨大已完成文本工具结果。现有 [ManagedContextTest.kt:160](../app/src/test/java/me/rerere/rikkahub/data/ai/context/ManagedContextTest.kt#L160) 包含这一行为的测试源码；本轮未执行该测试。

直接按 `prune && historyTools && TOOL` 决定是否恢复旧包装并不充分：Prune 自身还保护近期结果，而旧包装会在结果写入原始历史前截断正文。完整内容可能落在文件中，但 `history_read` 读到的原始消息已经是预览和文件提示。

**结论：** 保留“大结果在不同开关组合下如何处理”的问题，撤回“只开总开关就无保护地原样发送”的笼统断言。若需要兜底，应同时定义原始历史保存、请求投影预算与失败反馈，不能只恢复执行阶段的截断。

### A2：关闭自动压缩后超预算被拒绝

增强模式传 `limitMessages=false`，不叠加助手消息条数限制，这与 [SettingContextPage.kt:75](../app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingContextPage.kt#L75) 的说明一致。助手的旧条数限制默认是 `0`，也并非所有旧配置都会截断。

关闭自动摘要后，仍可使用 Prune、已有摘要和手动压缩；这些不足以将请求降到输入预算内时，拒绝发送属于可成立的产品策略。自动截掉用户历史会改变上下文完整性，应作为显式决策，而非直接称为安全修复。

**建议：** 保持开关语义明确，超预算时提供手动压缩或调整容量等可执行路径。若另增兜底截断策略，应明确其适用条件和工具调用边界保护。

## 4. 需要复现后再确定修法的边界

### B2：摘要已保存后，下一轮预算检查仍可能失败

源码中确有“保存摘要 → 重新投影 → 再次检查预算”的循环。当投影只剩一条 USER 摘要时，自动 `tailStart` 可为 0；若此时仍满足 `due`，会在 `check(tailStart > 0)` 失败。可达性和跨请求恢复尚未实测。

需要区分 `budget.trigger` 与 `budget.input`：前者是压缩触发阈值，后者才是可用输入上限。在二者之间、已经无可压缩历史时是否允许发送，应该单独定义。系统提示词、工具 schema、必要近期内容等不可再缩减部分也需计入判断。

**建议验证：** 小窗口、多工具、整段摘要后仍超过 trigger，以及追加新用户消息后再次请求的组合。原始消息保留、后续请求可能改变尾部边界、关闭增强模式可停止使用摘要，因此暂不称为“会话永久卡死”。也不把整次多轮压缩必须完全不落库作为既定约束；应先决定是否保留已完成的有效阶段性摘要。

### B4：手动压缩与生成启动之间的竞争

[ChatService.kt:815](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt#L815) 的压缩互斥锁只覆盖手动压缩，自动压缩没有取得该锁；手动摘要完成时又检查 `!isGenerating`。手动压缩期间启动生成，有可能使已经完成的手动摘要被拒绝。重复保存同一来源等具体交错尚未复现。

**建议验证：** 用可控挂起点覆盖“手动摘要开始后启动生成”“来源未变但生成已开始”“自动摘要先提交”场景。若要共用锁，需协调生成启动和提交策略；只给两处摘要调用加锁，不会自动解决 `isGenerating` 已变为 true 的问题。

### 提示注入、文档估算与媒体表示

- 摘要文本被放入 USER 消息，存在需要评估的信任边界变化；USER 不是最高优先级角色。摘要请求已有“把 transcript 当作数据”的系统指令。尚未验证攻击文本能否穿过摘要并劫持后续任务，应保留为安全验证项。
- `tailStart` 和候选选择按转换前内容估算，完整请求按转换后消息估算；大文档可能让保留范围与最终预算不一致。但完整请求仍有转换后的预算检查，不能推导为所有文档都未计入预算。
- 历史范围替换为文本摘要后，不再包含原始媒体输入；当前 `protectedMedia` 重点保护最新工具批次中的媒体。应明确用户媒体的保留策略、摘要中的省略说明与回查能力，不能把投影省略等同于原始文件删除。
- 未执行工具落入压缩候选的可达路径尚未验证。现有检查有保护调用完整性的作用，不建议直接删除。

## 5. UI、格式和测试评价的更正

### UI

- 多个数字输入框共用 `Tokens` 标签，值得检查读屏关联与区分度；但 [SettingContextPage.kt:265](../app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingContextPage.kt#L265) 已有 `isError` 和范围提示，撤回“越界草稿静默丢弃”的表述。
- 工具名称草稿依赖已保存列表作为 `remember` key，逗号输入后的规范化可能影响编辑体验；仅敲分隔符时解析列表可能并未改变，不能断言必然重写草稿或跳光标。需要 Compose 交互验证。
- 恢复默认设置会一次重置上下文配置，可考虑确认或撤销；这是交互改进，不是本轮确认的数据丢失缺陷。
- 摘要面板使用空列表作为初始状态，缺少独立加载状态，有短暂显示空态的可能，未做设备验证。
- 硬编码中文按仓库“未要求本地化时优先功能”的约定记录，不单独作为阻断项。

### 格式门禁

`AppDatabase.kt` 等文件确有未按字典序排列的新增 import。构建配置使用 [build.gradle.kts:16](../build.gradle.kts#L16) 的 `ratchetFrom("origin/master")`，而本轮核实时 `origin/master` 与 HEAD 相同、这些源码没有工作区改动。

因此不能仅凭 import 顺序推断当前 `spotlessCheck` 必红，也不能据此认定已有格式化记录虚假。应区分全量 ktlint 结果、ratchet 实际覆盖范围与真实命令输出。本轮未执行门禁，不声称其通过或失败。

### 现有测试与建议补充

测试源码并非只断言请求次数：[AgentLoopTest.kt:117](../ai/src/test/java/me/rerere/ai/agent/AgentLoopTest.kt#L117) 已断言 `params.tools` 和 `newPartsFrom`；[AgentLoopTest.kt:527](../ai/src/test/java/me/rerere/ai/agent/AgentLoopTest.kt#L527) 已断言溢出恢复不重复执行工具；后续用例还检查失败流的部分输出不会成为恢复输入，以及恢复不会无限重试。这些源码断言不能替代本轮运行结果。

建议补充以下面向行为的用例：

| 场景 | 应验证的结果 |
| --- | --- |
| 相同摘要重复投影，并开启时间提醒 | 历史对应的最终请求文本稳定，时间顺序合理 |
| 手动压缩范围只包含旧摘要 | 无候选语义明确，不无意重复调用模型 |
| 自动合并多个旧摘要 | 能保留必要合并能力，达到停止条件 |
| 来源在 flush、保存前后发生变化 | 返回结果与实际持久化状态一致，避免使用旧上下文继续生成 |
| provider 溢出后恢复回调失败或取消 | 保留原始错误因果，取消立即传播 |
| 空 ID、重复 ID 与混合 Text/Tool parts | 裁剪标记可执行，完整原文仍能分页回查，part 偏移正确 |
| 不可再缩减部分超过 trigger 或 input | 两种预算边界分别得到预期结果 |
| 手动压缩期间启动生成 | 提交、等待或放弃行为符合明确策略 |
| 大文档变更及重复分页回查 | 预算判断正确，事务与解析成本可测量 |
| prompt usage 与估算差异较大、随后投影改变 | 校准因子与重置行为符合预期 |

## 6. 上游合并核对清单（原 E）

本地上游 `48183311` 引入 distribution flavor，并保留自更新实现、按 `IS_PLAY_STORE` 控制入口；当前 fork 删除了自更新实现，仍保留上游已删除的旧 Play Store 检测文件。这是独立整合事项。

- rebase 前明确渠道与自更新各自的策略。接受 flavor 和继续移除自更新可以同时成立，不是二选一。
- 重点核对 `ChatVM.kt`、`ChatDrawer.kt`、`SettingPreferencesNotificationPage.kt` 及依赖注入中的更新相关引用；不要机械选择整侧内容。
- 核对 README、私有构建说明、工作流、`buildAll`、baseline profile 的渠道任务和产物路径。
- 未运行 rebase 或合并模拟，不给出“必然冲突”的文件和 hunk 数量；未查询合并后任务列表，也不声称 `assembleRelease` 必然不存在。需要特定渠道产物时应明确目标任务。
- 将 `Version.kt` 与上游 `UpdateChecker.kt` 中版本比较实现的同源关系纳入后续维护核对，避免仅依赖 Git 冲突提示。
- 旧 Play Store 检测代码和更新设置残留可另做引用检查与清理；不据静态零调用直接推断 lint 必然失败。

## 7. 验证范围与后续执行

本轮已完成源码和现有测试源码核对，以及本地 HEAD、origin/master、upstream/master 引用检查。本文只记录问题和建议，未修改功能实现。

后续先为 C1、B1、B3 和 A3 补充对应行为验证并修复，再处理 D1、索引事务及派生数据生命周期。B2、B4、实际缓存收益、提示注入和 UI 输入行为需要各自的复现证据。是否增加自动截断及如何保留阶段性摘要，需明确产品语义后决定。

尚未执行：编译、JVM 测试、Spotless/Lint、真实 provider 请求、设备交互、并发调度复现、性能测量和 rebase。因此本文不作“构建失败”“门禁已恢复绿色”“会话永久不可恢复”或“已证明提示注入成功”的结论。
