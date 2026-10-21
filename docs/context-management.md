# Enhanced context management

The Android settings page has a separate **上下文管理** entry. The global switch is off by
default. Settings are captured at the start of each generation run, so a toggle does not
rewrite an in-flight request.

## Legacy behavior

When disabled, requests use the existing `limitContext(contextMessageLimit)` path, existing
manual checkpoints and tool-output handling. Enhanced summaries are not messages and are
never inserted as `isContextCheckpoint`; turning the feature off stops using them and stops
registering the history tools.

## Enhanced behavior

- Check the transformed request and tool schemas before each model step, including steps
  after tools finish. Reserve output tokens and a safety margin. Estimates are conservative
  for mixed-language text and media, and are calibrated using returned prompt usage within
  a run. The automatic output reservation is also applied to the request's output limit.
- Keep recent original content and replace older contiguous ranges with summaries.
  Completed tool parts contain both invocation and output and are never split. An enormous
  completed text tool result can itself be summarized; newly returned media is retained.
- Generate independent range summaries. Oversized summary inputs are processed in chunks.
  Empty, tool-calling, output-limited and ineffective summaries are rejected. Original
  messages are never overwritten by this process.
- Commit through the active `ConversationSession` write lock with incarnation, source
  content and ordering checks. Editing or changing branches makes incompatible summaries
  ineligible. Deleting a conversation cascades its summaries and deletes its recall index.
- Optionally recover from an explicit provider context-overflow error once per model step.
  This retries generation from the pre-attempt assistant state and does not rerun completed
  tools. Disabling automatic compaction also disables automatic overflow compaction.

`history_search` and `history_read` are registered only when enabled and the selected model
advertises tool support. They expose the current run's selected branch, not other chats or
alternative responses. Search indexes full body text, extracted documents and tool results.
Read supports a bounded text page and an optional original image. Recall budgets count text;
image size is estimated separately by the next request's budget check.

## Storage and lifecycle

Database migration **32 → 33** adds `context_summary`, with a conversation foreign key.
The payload records original message IDs, part ranges and content fingerprints. The
rebuildable `context_history_fts` index is created on open, uses the existing Android simple
tokenizer and is updated incrementally only when recall is enabled.

Database snapshots include summaries and the index. Restore validation uses the same
database factory and migration chain. Conversation-only import/export remains about the
original transcript; summaries can be rebuilt after import.

This is the first implementation: range summaries and source expansion, rather than a
separate multilevel summary DAG. Summary text is lossy; original sources remain available
for targeted recall. A model must choose the appropriate recall queries to recover details.

Design references: [Pi compaction](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/compaction.md)
and [LCM architecture](https://github.com/Martian-Engineering/lossless-claw/blob/main/docs/architecture.md).

## Validation

Tests cover source invalidation, completed-tool boundaries, mixed-language pagination,
small-window budgets, compaction failure, oversized tool results and one-attempt recovery
without duplicate tool execution. They require the normal Gradle test environment.

Release and debug Kotlin compilation passed. The targeted JVM run passed all 36 tests:
22 in `AgentLoopTest`, 7 in `ContextProjectionTest`, and 7 in `ManagedContextTest`.
The generated Room schema 33 preserves every table from schema 32 and adds only
`context_summary`; the actual migration SQL also passed a SQLite check. Static checks
exercised the conversation-scoped FTS query and deletion cleanup with SQLite's built-in
tokenizer substituted for Android's simple tokenizer. On-device UI, provider integration,
and upgrade checks have not been run.
