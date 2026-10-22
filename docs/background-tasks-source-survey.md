# 本地后台任务源码调研

调研日期：2026-10-22。以下结论基于实际读取的上游源码；提交号固定本次阅读版本，不代表已验证的稳定发行版。RikkaHub 目前仍是同步的 `workspace_shell`，本文记录可移植的现成机制。

## 源码版本

| 项目 | 分支与提交 | 作用 |
| --- | --- | --- |
| [OpenCode](https://github.com/anomalyco/opencode/tree/c93aaae37157d785f76a8e9a9243a6560e286468) | `dev` / `c93aaae37157d785f76a8e9a9243a6560e286468` | 完整的 Shell、Job、会话完成通知和重启恢复 |
| [DeepSeek Harness / dsh](https://github.com/deepseek-ai/deepseek-harness/tree/687f282e8625c6b8d11ff58211a90524be2e4317) | `master` / `687f282e8625c6b8d11ff58211a90524be2e4317` | 通用后台 job 注册表及模型控制工具 |
| [Pi](https://github.com/earendil-works/pi/tree/58c0b02ce552adab76014e7ed712ab7981359aa8) | `main` / `58c0b02ce552adab76014e7ed712ab7981359aa8` | 内置 bash 的同步执行基线 |
| [pi-bg-bash](https://github.com/gvanderclay/pi-bg-bash/tree/349484c209f78fa3040deffe3842ef912a950d8b) | `main` / `349484c209f78fa3040deffe3842ef912a950d8b` | Pi 的后台 bash 扩展，包含进程组、日志游标、完成通知 |
| [pi-patty-bg-tasks](https://github.com/patty-io/pi-patty-bg-tasks/tree/697010eaa88ca1b861fce554a149a5f019a7439a) | `main` / `697010eaa88ca1b861fce554a149a5f019a7439a` | 较完整的 Pi 后台任务扩展，另有监控和子 Agent 功能 |

本次读取的各仓库根目录许可证均为 MIT；实际移植代码时应随复制部分保留对应版权与许可声明。

## OpenCode 的实现

### 前台和后台共享同一个执行过程

核心入口是 [Shell 工具](https://github.com/anomalyco/opencode/blob/c93aaae37157d785f76a8e9a9243a6560e286468/packages/core/src/tool/plugin/shell.ts#L199-L272)：

1. `shell.create()` 启动进程，返回 shell ID 和输出文件路径。
2. `jobs.start()` 注册以该 shell ID 为标识的 Job；实际工作是等待 shell 结束并生成结果。
3. 普通调用通过 `jobs.block()` 等待；显式 `background: true` 则调用 `jobs.background()` 并立即返回运行中的 shell ID。
4. 运行中的前台任务也可以收到转后台信号。工具解除等待、清除原 shell 超时、返回相同 shell ID；进程不会重启，命令也不会重跑。

[Job.block](https://github.com/anomalyco/opencode/blob/c93aaae37157d785f76a8e9a9243a6560e286468/packages/core/src/job.ts#L329-L385) 竞速等待两个 Deferred：任务结束、转后台。`Job.background()` 更新状态并唤醒转后台 Deferred。这里的后台是执行归属和等待方式的改变，不是向命令补上 `&`。

工具默认前台超时是 120 秒，后台未指定时不设超时；显式超时到期会终止命令。本次阅读版本的前台超时不会自动转后台，不能把 dsh 的超时提升行为套用到它身上。

### 三层分工

| 层 | 已存在的职责 | 源码 |
| --- | --- | --- |
| Shell 服务 | 启动和持有进程、输出写文件、按游标读输出、超时、删除命令 | [shell.ts](https://github.com/anomalyco/opencode/blob/c93aaae37157d785f76a8e9a9243a6560e286468/packages/core/src/shell.ts) |
| Job 服务 | 状态、等待、转后台、取消、待投递完成通知记录 | [job.ts](https://github.com/anomalyco/opencode/blob/c93aaae37157d785f76a8e9a9243a6560e286468/packages/core/src/job.ts) |
| Shell 工具与会话 | 权限检查、把进程注册为 Job、返回工具结果、任务完成后投递合成消息 | [tool/plugin/shell.ts](https://github.com/anomalyco/opencode/blob/c93aaae37157d785f76a8e9a9243a6560e286468/packages/core/src/tool/plugin/shell.ts) |

Shell 按 location 管理，Job 是全局服务。Shell 的管理执行保持进程作用域打开，直至命令完成或被移除，生命周期因此可以超过单次工具等待。

### 输出与完成通知

[Shell 输出](https://github.com/anomalyco/opencode/blob/c93aaae37157d785f76a8e9a9243a6560e286468/packages/core/src/shell.ts#L208-L261) 合并 stdout/stderr 写入 `.out` 文件；接口支持 `cursor` 与 `limit`，默认单次读取 64 KiB，结束结果返回受限的尾部摘要和完整文件路径。进程结束后先等输出采集完成，再公布最终状态，避免读取到不完整的最终日志。

后台观察者等待 Job 完成，然后通过 `sessions.synthetic()` 投递结果；[会话接收逻辑](https://github.com/anomalyco/opencode/blob/c93aaae37157d785f76a8e9a9243a6560e286468/packages/core/src/session/session.ts#L283-L322) 将其送入收件队列并按条件唤醒会话。工具提示明确要求模型等待自动通知，避免反复 sleep 和轮询。

Shell 与 Job 的已消费/已结束内存历史各有 25 条的相关上限；Shell 输出还另有七天清理及每小时扫描机制。这些限制作用于不同记录，不代表所有已结束输出必定保留七天。

### 停止与重启

[进程适配层](https://github.com/anomalyco/opencode/blob/c93aaae37157d785f76a8e9a9243a6560e286468/packages/util/src/cross-spawn-spawner.ts#L344-L397) 在 POSIX 上优先向进程组发信号，失败时回退单个进程。通常先 SIGTERM，配置的宽限期后升级 SIGKILL；Shell 启动处设为三秒。Windows 走 `taskkill /T /F`。不能将进程组信号等同于对任意脱离进程组的后代都能绝对清理。

[重启恢复](https://github.com/anomalyco/opencode/blob/c93aaae37157d785f76a8e9a9243a6560e286468/packages/core/src/session/execution/restart.ts#L101-L136) 使用持久化通知记录：原先仍在运行的 shell 标记为取消并投递重启原因，不恢复原 OS 进程；已完成但尚未投递的结果可以补送。后台子 Agent 则可从已保存的会话继续执行，它与 shell 进程恢复是两种机制。

## dsh 和 Pi 的对应机制

| 项目 | 启动/等待 | 输出与结束 | 生命周期 |
| --- | --- | --- | --- |
| dsh | 命令从开始就注册为 job；显式后台立即返回；配置允许前台超时后提升后台 | `job_output`、`job_list`、`job_kill`；有界输出环与模型读取游标，完成事件可唤醒 owner | owner 或 harness 销毁时取消；保留生命周期事件，但不恢复活进程和内存输出环 |
| Pi 内置 bash | 当前 schema 只有 command 和 timeout，调用等待命令结果 | 输出截断，必要时写临时文件；取消/超时结束执行 | 内置工具不是后台任务注册表 |
| pi-bg-bash 扩展 | detached shell 包装器；可显式后台或把同一次前台执行交给后台注册表 | 文件日志、读取位置、一次完成通知；提供查询和停止工具 | reload 保留；其他会话结束清理进程组；另有 watcher 在 Pi 消失后清理 |

可直接定位的源码：

- dsh：[通用任务契约](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/jobs/jobs/src/types.ts)、[任务注册表](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/jobs/jobs-local/src/index.ts)、[有界输出环](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/jobs/jobs-local/src/ring.ts)、[模型控制工具](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/jobs/tool-jobs/src/index.ts)、[bash 接入](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/packages/shell/tool-bash/src/index.ts)。
- Pi：[内置 bash](https://github.com/earendil-works/pi/blob/58c0b02ce552adab76014e7ed712ab7981359aa8/packages/coding-agent/src/core/tools/bash.ts)。
- pi-bg-bash：[注册表](https://github.com/gvanderclay/pi-bg-bash/blob/349484c209f78fa3040deffe3842ef912a950d8b/src/registry.ts)、[启动与 watcher](https://github.com/gvanderclay/pi-bg-bash/blob/349484c209f78fa3040deffe3842ef912a950d8b/src/launch.ts)、[进程组停止](https://github.com/gvanderclay/pi-bg-bash/blob/349484c209f78fa3040deffe3842ef912a950d8b/src/kill.ts)、[完成通知](https://github.com/gvanderclay/pi-bg-bash/blob/349484c209f78fa3040deffe3842ef912a950d8b/src/notify.ts)。

## RikkaHub 的复用判断

以下为基于源码的移植建议，不是上游对 Android 的兼容性承诺。

- 优先对照 OpenCode 移植 Shell / Job / 会话通知的职责、状态转换和测试用例，避免重新设计前后台转换协议。关键是开始执行时就归管理器所有，工具层只选择等待或返回 ID。
- 上游是 TypeScript + Effect，并依赖其 Environment、KV、插件和会话服务；不能作为现成 Kotlin 库直接引用。若保留原样运行就需要另设 JS 运行时与桥接；按当前原生架构，对照移植并保留来源更直接。
- 进程适配继续用 RikkaHub 已有 PRoot 启动链路。管理器保持 PRoot 主命令运行，工具提前返回时不销毁它；命令结束后仍可保留 `--kill-on-exit` 清理行为。
- 模型查询/停止工具可参考 dsh 现成的 job 控制面；如果选 OpenCode 的完成即投递模式，应同时移植通知确认和防重复机制，不能只保留 taskId。
- Android 前台服务、应用被终止后的状态对账、删除工作区前停止任务，需要接现有应用生命周期。上游桌面进程管理代码不能证明 Android/PRoot 下的子进程回收效果。
- 优先移植上游已有用例：运行中转后台不重跑、取消触发清理、超时升级强杀、静默/快速退出在转后台前完成、非零退出码保留、输出采集完成后才投递最终结果、重启后补送或标记取消。

本轮只调研并记录源码，未修改运行逻辑，未运行上游测试。
