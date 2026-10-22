import { useEffect, useState } from "react";
import { LoaderCircle, Terminal, Square } from "lucide-react";
import { toast } from "sonner";
import api, { sse } from "~/services/api";
import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogTitle, DialogDescription } from "~/components/ui/dialog";

// Matches workspaceTaskJson in WorkspaceTaskTools.kt.
interface WorkspaceTask {
  taskId: string;
  status: string;
  active: boolean;
  background: boolean;
  command: string;
  cwd: string;
  stdout: string;
  truncated: boolean;
  startedAt: number;
  finishedAt?: number;
  exitCode?: number;
}
interface OutputPage {
  output: string;
  cursor: number;
  size: number;
  truncated: boolean;
}
const labels: Record<string, string> = {
  starting: "启动中",
  running: "运行中",
  stopping: "停止中",
  completed: "已完成",
  failed: "失败",
  cancelled: "已停止",
  timed_out: "已超时",
  interrupted: "已中断",
};

export function WorkspaceTasks({ conversationId }: { conversationId?: string }) {
  // A keyed child drops old conversation state and pending UI operations together.
  return conversationId ? <TaskPanel key={conversationId} owner={conversationId} /> : null;
}

function TaskPanel({ owner }: { owner: string }) {
  const [tasks, setTasks] = useState<WorkspaceTask[]>([]);
  const [expanded, setExpanded] = useState(false);
  const [selected, setSelected] = useState<string>();
  const [busy, setBusy] = useState<string>();
  const [page, setPage] = useState<OutputPage>();
  const [error, setError] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    let reconnect: ReturnType<typeof setTimeout> | undefined;
    void sse<WorkspaceTask[]>(
      `conversations/${owner}/tasks/stream`,
      {
        onMessage: ({ data }) => {
          if (!controller.signal.aborted) {
            setTasks(data);
            setError(false);
          }
        },
        onClose: () => {
          if (!controller.signal.aborted) setError(true);
        },
        onError: () => {
          if (!controller.signal.aborted) setError(true);
        },
      },
      { signal: controller.signal, searchParams: selected ? { logTaskId: selected } : undefined },
    ).finally(() => {
      if (!controller.signal.aborted)
        reconnect = setTimeout(() => setRetry((value) => value + 1), 3000);
    });
    return () => {
      controller.abort();
      clearTimeout(reconnect);
    };
  }, [owner, retry, selected]);
  const active = tasks.filter((task) => task.active);
  const visible = expanded ? tasks : active;
  const current = tasks.find((task) => task.taskId === selected);
  async function action(task: WorkspaceTask, operation: "stop" | "background") {
    setBusy(task.taskId);
    try {
      await api.post(`conversations/${owner}/tasks/${task.taskId}/${operation}`);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : "任务操作失败");
    } finally {
      setBusy(undefined);
    }
  }
  async function readPage() {
    if (!current) return;
    try {
      setPage(
        await api.get<OutputPage>(`conversations/${owner}/tasks/${current.taskId}/output`, {
          searchParams: { cursor: page && page.cursor < page.size ? page.cursor : 0 },
        }),
      );
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : "读取日志失败");
    }
  }
  if (!tasks.length) return null;
  return (
    <div className="mb-2 space-y-2">
      <div className="flex items-center justify-between">
        <Button variant="ghost" size="sm" onClick={() => setExpanded(!expanded)}>
          <Terminal className="size-4" />
          工作区任务 · {active.length ? `${active.length} 个运行中` : `历史记录 (${tasks.length})`}
        </Button>
        {active.length > 1 && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              void api
                .post(`conversations/${owner}/tasks/stop-all`)
                .catch((cause) =>
                  toast.error(cause instanceof Error ? cause.message : "停止任务失败"),
                );
            }}
          >
            全部停止
          </Button>
        )}
        {error && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setError(false);
              setRetry(retry + 1);
            }}
          >
            连接中断，重连
          </Button>
        )}
      </div>
      <div className="max-h-52 space-y-2 overflow-y-auto">
        {visible.map((task) => (
          <div key={task.taskId} className="rounded-2xl bg-muted px-3 py-2">
            <div className="flex items-center gap-2 text-xs font-medium">
              {task.active ? (
                <LoaderCircle className="size-3.5 animate-spin" />
              ) : (
                <Terminal className="size-3.5" />
              )}
              <span>
                {labels[task.status] ?? task.status}
                {task.active && task.background ? " · 后台" : ""}
              </span>
              {task.exitCode !== undefined && (
                <span className="text-muted-foreground">exit {task.exitCode}</span>
              )}
            </div>
            <pre className="my-1 line-clamp-2 whitespace-pre-wrap break-all text-xs">
              {task.command}
            </pre>
            <div className="flex justify-end gap-1">
              <Button
                size="sm"
                variant="ghost"
                onClick={() => {
                  setPage(undefined);
                  setSelected(task.taskId);
                }}
              >
                日志
              </Button>
              {task.active && !task.background && (
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={busy === task.taskId || task.status === "stopping"}
                  onClick={() => void action(task, "background")}
                >
                  转后台
                </Button>
              )}
              {task.active && (
                <Button
                  size="sm"
                  variant="ghost"
                  className="text-destructive"
                  disabled={busy === task.taskId || task.status === "stopping"}
                  onClick={() => void action(task, "stop")}
                >
                  <Square className="size-3" />
                  {task.status === "stopping" ? "停止中…" : "停止"}
                </Button>
              )}
            </div>
          </div>
        ))}
      </div>
      <Dialog
        open={!!current}
        onOpenChange={(open) => {
          if (!open) setSelected(undefined);
        }}
      >
        <DialogContent className="max-h-[85vh] overflow-y-auto rounded-3xl sm:max-w-2xl">
          <DialogTitle>任务日志 · {current ? labels[current.status] : ""}</DialogTitle>
          <DialogDescription className="break-all whitespace-pre-wrap font-mono">
            {current?.command}
            {"\n"}/workspace/{current?.cwd}
          </DialogDescription>
          {current?.truncated && (
            <p className="text-xs text-muted-foreground">日志已达 20 MiB，最新输出仍持续更新。</p>
          )}
          <div className="flex gap-2">
            <Button variant="secondary" size="sm" onClick={() => setPage(undefined)}>
              最新输出
            </Button>
            <Button variant="secondary" size="sm" onClick={() => void readPage()}>
              {page && page.cursor < page.size ? "下一页" : "从头读取"}
            </Button>
            {page && (
              <span className="self-center text-xs">
                {page.cursor} / {page.size} bytes
              </span>
            )}
          </div>
          <pre className="whitespace-pre-wrap break-all rounded-2xl bg-muted p-3 text-xs">
            {page?.output ?? (current?.stdout || "等待输出…")}
          </pre>
        </DialogContent>
      </Dialog>
    </div>
  );
}
