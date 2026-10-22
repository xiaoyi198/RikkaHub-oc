# Enhanced context management

The Android settings page has a separate **上下文管理** entry. The global switch is off by
default. Settings are captured at the start of each generation run, so a toggle does not
rewrite an in-flight request.

The collapsible **高级设置** section contains compression timing, target occupancy, chunk
size, recent-original and summary budgets, Prune budgets/protected names, and recall limits.
Token budgets set to zero adapt to the model (the recall limit remains an explicit value).
The page previews effective budgets for the current assistant's model; conversations with
a different model use their own window. Existing settings deserialize with the new defaults.

Automatic compression starts at 80% of the window and continues toward 65%, reserving output
and safety space as before. The target is at least five percentage points below the trigger.
Automatic chunk size is one third of available input; recent originals use one fifth, and the
summary output budget uses one twelfth, limited by the summary model's own output ceiling.
Prune protects two fifths of available input and requires savings of one fifth before applying
a batch. These automatic budgets scale with each conversation's model window and have no fixed
token caps. Manual chunk size can use up to the full available input budget; other manual
budgets remain bounded by their policy limits. Complete messages/tool pairs can exceed a chunk.
If protected recent content or fixed prompts prevent reaching the target, compression can
finish safely below the trigger instead of repeatedly rewriting a lone summary.

## Legacy behavior

When disabled, requests use the existing `limitContext(contextMessageLimit)` path, existing
manual checkpoints and tool-output handling. Enhanced summaries are not messages and are
never inserted as `isContextCheckpoint`; turning the feature off stops using them and stops
registering the history tools.

## Enhanced behavior

- Check the transformed request and tool schemas before each model step, including steps
  after tools finish. Reserve output tokens and a safety margin. Estimates are conservative
  for mixed-language text and media, and are calibrated using returned prompt usage within
  a run. Underestimation raises the correction immediately; overestimation is corrected
  gradually, with a lower bound. The automatic output reservation is also applied to the
  request's output limit.
- Optionally prune older plain-text tool results before checking whether a summary is needed.
  This is an independent, default-off switch; it also works with automatic summaries off.
- Keep recent original content and replace older contiguous ranges with summaries.
  Completed tool parts contain both invocation and output and are never split. An enormous
  completed text tool result can itself be summarized; newly returned media is retained.
- Generate independent range summaries. Oversized summary inputs are processed in chunks.
  New raw ranges are summarized before existing summaries are merged. Summary projections
  use stable source timestamps, preserving earlier summary prefixes across requests.
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

## Tool result pruning (Prune)

The request path is **valid range summaries → Prune → transform and check token budget →
summarize originals if still needed**. Prune itself does not call a model. It requires the
enhanced context switch, its own switch, and an available `history_read` tool. Disabling
history recall or selecting a model without tool support bypasses pruning.

Pruning batches are calculated against canonical active history, including original ranges
already represented by summaries, and only applied to visible raw ranges. This prevents a
partial summary from reducing an approved batch below the minimum and restoring cleared
outputs. It protects the latest two real user turns,
then an additional suffix of eligible tool outputs. During a long single-turn tool loop,
older outputs in the active assistant message become eligible only after a subsequent
successful model step has consumed them. All outputs from the latest batch stay complete.
Only completed, plain-text results are candidates. Calls, arguments, IDs, metadata, and
approval states are retained; pending calls, user decisions, skills, and media or structured
outputs are protected. `skill`, `use_skill`, and `ask_user` are always excluded, and settings
can add exact tool names. These protections concern Prune; the existing summary policy
continues to manage the rest of the request.

Automatic protection is 40% of the available input budget. Automatic minimum batch savings is
20% of available input. Manual budgets are clamped to half the available input. The protection boundary keeps a complete result, so it can
exceed its token target for a large result. Savings subtract placeholder text overhead;
tiny or unprofitable batches do not modify the request prefix. The newest eligible result
is always retained. These model-relative defaults also adapt to small windows.

Pruned outputs become short markers with their original message ID and tool call ID.
`history_read(id, tool_call_id, offset, max_tokens)` retrieves just that original call and
its full output, paginated within the recall budget. Search and recall continue to read
the canonical branch. If a summary is subsequently required, it reads the original
observations, not pruning placeholders. Source fingerprints still refer to originals.

Pruning is a deterministic projection recomputed from the active branch; no pruning marker
is saved into the transcript or database. Unchanged history and settings reproduce the
same selection for older turns across runs. Eligibility within the active tool run also
depends on the last successful model step; after resuming a run, unverifiably consumed
outputs are conservatively retained until a later successful step. Editing, branching,
or changing policy recomputes the selection.
Enabling it on an existing conversation can change the request prefix and affect provider
prompt-cache reuse. Batch thresholds reduce small changes but do not guarantee cache hits.

Reference: [OpenCode v1.19.3 pruning](https://github.com/anomalyco/opencode/blob/v1.19.3/packages/opencode/src/session/compaction.ts).

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

The initial enhanced-context release and debug Kotlin compilation passed. Its targeted JVM run passed all 36 tests:
22 in `AgentLoopTest`, 7 in `ContextProjectionTest`, and 7 in `ManagedContextTest`.
The generated Room schema 33 preserves every table from schema 32 and adds only
`context_summary`; the actual migration SQL also passed a SQLite check. Static checks
exercised the conversation-scoped FTS query and deletion cleanup with SQLite's built-in
tokenizer substituted for Android's simple tokenizer.

The Prune addition passed debug Kotlin compilation and all 29 context JVM tests:
7 in `ContextProjectionTest`, 11 in `ContextPruningTest`, and 11 in `ManagedContextTest`.
Coverage includes default-off/backward-compatible settings, net batch savings, small-window
budgets, protected results, branch edits, successful-step consumption boundaries, targeted
original recall, pruning without summary calls, and summary fallback using original outputs.
Changed Kotlin files were formatted with ktlint 1.8.0 and the project's rule overrides.
The signed release build containing Prune passed, including required release lint checks:
version `3.1.0-oc.2` / `195`. ARM64, x86_64, and universal APKs passed package/version
inspection and signature verification against the original OC certificate. Prune adds no
database migration. On-device UI, provider integration, and upgrade checks have not been run.
