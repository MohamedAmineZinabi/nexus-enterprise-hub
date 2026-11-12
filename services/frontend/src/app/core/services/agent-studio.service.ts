import { Injectable, NgZone, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, catchError, map, of, finalize } from 'rxjs';
import { AuthService } from './auth.service';
import { ApiResponse } from '../models/auth.models';
import {
  AgentNodeName,
  CitationItem,
  CreateTaskRequest,
  McpToolInfo,
  McpToolsResponse,
  NodeExecutionState,
  StreamStatus,
  TaskCompletedEvent,
  TaskPageResponse,
  TaskProgressEvent,
  TaskResponse,
  TaskSummary
} from '../models/agent.models';

const INITIAL_NODES: NodeExecutionState[] = [
  { nodeName: 'PLANNER', displayName: '1. Planner Agent', status: 'idle', durationMs: 0 },
  { nodeName: 'RESEARCHER', displayName: '2. Researcher Agent', status: 'idle', durationMs: 0 },
  { nodeName: 'SYNTHESIZER', displayName: '3. Synthesizer Agent', status: 'idle', durationMs: 0 },
  { nodeName: 'CRITIC', displayName: '4. Critic & Verifier', status: 'idle', durationMs: 0 }
];

@Injectable({
  providedIn: 'root'
})
export class AgentStudioService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly ngZone = inject(NgZone);

  // Active Task and Streaming State
  readonly activeTaskId = signal<string | null>(null);
  readonly streamStatus = signal<StreamStatus>('IDLE');
  readonly timelineEvents = signal<TaskProgressEvent[]>([]);
  readonly activeNode = signal<AgentNodeName | null>(null);
  readonly finalReport = signal<string | null>(null);
  readonly citations = signal<CitationItem[]>([]);
  readonly totalDurationMs = signal<number | null>(null);
  readonly error = signal<string | null>(null);

  // 4-Node Pipeline Visual States
  readonly nodeStates = signal<NodeExecutionState[]>(JSON.parse(JSON.stringify(INITIAL_NODES)));

  // Task History and MCP Tools State
  readonly recentTasks = signal<TaskSummary[]>([]);
  readonly isLoadingRecent = signal<boolean>(false);
  readonly mcpTools = signal<McpToolInfo[]>([]);
  readonly isLoadingTools = signal<boolean>(false);

  private eventSource: EventSource | null = null;

  /**
   * Submits a new autonomous agent task to core-service and establishes
   * real-time SSE streaming for live progress telemetry and final report.
   */
  submitAndStreamTask(request: CreateTaskRequest): Observable<TaskResponse> {
    this.closeStream();
    this.resetActiveTask();
    this.streamStatus.set('CONNECTING');

    return this.http.post<ApiResponse<TaskResponse>>('/api/v1/tasks', request).pipe(
      map(res => res.data),
      tap(task => {
        this.activeTaskId.set(task.taskId);
        this.connectSse(task.taskId);
      }),
      catchError(err => {
        this.streamStatus.set('FAILED');
        const errorMsg = err.error?.message || err.message || 'Failed to submit task to autonomous agent orchestrator';
        this.error.set(errorMsg);
        throw err;
      })
    );
  }

  /**
   * Connects native browser EventSource to Spring Boot SSE bridge with JWT token.
   */
  connectSse(taskId: string): void {
    this.closeStream();

    const token = this.authService.getToken();
    const tokenQuery = token ? `?token=${encodeURIComponent(token)}` : '';
    const streamUrl = `/api/v1/tasks/${taskId}/stream${tokenQuery}`;

    this.eventSource = new EventSource(streamUrl);

    // Initial handshake event
    this.eventSource.addEventListener('HANDSHAKE', (e: MessageEvent) => {
      this.ngZone.run(() => {
        this.streamStatus.set('STREAMING');
        try {
          const data = JSON.parse(e.data);
          this.appendTimelineEvent({
            taskId,
            nodeName: 'PLANNER',
            status: 'CONNECTED',
            durationMs: 0,
            message: 'SSE telemetry stream established. Dispatched to LangGraph multi-agent cluster.',
            timestamp: data.timestamp || new Date().toISOString()
          });
          this.setNodeRunning('PLANNER');
        } catch {
          // Handshake ack
        }
      });
    });

    // Intermediate node progress events (PLANNER, RESEARCHER, SYNTHESIZER, CRITIC)
    this.eventSource.addEventListener('TASK_PROGRESS', (e: MessageEvent) => {
      this.ngZone.run(() => {
        this.streamStatus.set('STREAMING');
        try {
          const raw = JSON.parse(e.data);
          const progress: TaskProgressEvent = {
            taskId: raw.taskId || taskId,
            nodeName: (raw.nodeName || raw.nodeType || 'PLANNER').toUpperCase(),
            status: raw.status || 'IN_PROGRESS',
            durationMs: Number(raw.durationMs || 0),
            message: raw.message || `Processing node ${raw.nodeName}...`,
            payload: raw.payload,
            timestamp: raw.timestamp || new Date().toISOString()
          };

          this.appendTimelineEvent(progress);
          this.updateNodeFromProgress(progress);
        } catch (err) {
          console.warn('Failed to parse SSE TASK_PROGRESS data:', e.data, err);
        }
      });
    });

    // Final task completion event
    this.eventSource.addEventListener('TASK_COMPLETED', (e: MessageEvent) => {
      this.ngZone.run(() => {
        try {
          const raw = JSON.parse(e.data);
          const report = raw.report || raw.result || raw.resultSummary || raw.resultPayload || '';
          const citations = Array.isArray(raw.citations) ? raw.citations : [];
          const duration = Number(raw.totalDurationMs || raw.durationMs || 0);

          this.finalReport.set(typeof report === 'string' ? report : JSON.stringify(report, null, 2));
          this.citations.set(citations);
          this.totalDurationMs.set(duration > 0 ? duration : null);
          this.streamStatus.set(raw.status === 'FAILED' ? 'FAILED' : 'COMPLETED');

          // Mark all nodes as completed
          this.markAllNodesCompleted();

          this.appendTimelineEvent({
            taskId,
            nodeName: 'CRITIC',
            status: raw.status || 'COMPLETED',
            durationMs: duration,
            message: raw.status === 'FAILED'
              ? `Task execution failed: ${raw.errorDetails || 'Unknown error'}`
              : 'Multi-agent orchestration completed successfully. Executive report synthesized.',
            timestamp: new Date().toISOString()
          });

          // Refresh recent tasks list
          this.loadRecentTasks().subscribe();
        } catch (err) {
          console.error('Failed to parse SSE TASK_COMPLETED payload:', e.data, err);
          this.streamStatus.set('COMPLETED');
        } finally {
          this.closeStream();
        }
      });
    });

    // Generic message handler fallback
    this.eventSource.onmessage = (e: MessageEvent) => {
      this.ngZone.run(() => {
        try {
          const parsed = JSON.parse(e.data);
          if (parsed.status === 'COMPLETED' || parsed.report) {
            this.finalReport.set(parsed.report || parsed.resultSummary || parsed.resultPayload || '');
            this.streamStatus.set('COMPLETED');
            this.markAllNodesCompleted();
            this.closeStream();
            this.loadRecentTasks().subscribe();
          }
        } catch {
          // ignore unparsed raw frames
        }
      });
    };

    // Error handling on EventSource
    this.eventSource.onerror = (err) => {
      this.ngZone.run(() => {
        // If we already finished and received the final report, standard SSE close triggers error
        if (this.streamStatus() === 'COMPLETED' || this.finalReport()) {
          this.closeStream();
          return;
        }

        // Check if the task finished asynchronously in the backend
        this.checkTaskStatusOnSseError(taskId);
      });
    };
  }

  /**
   * Verifies if task finished when SSE connection terminates unexpectedly.
   */
  private checkTaskStatusOnSseError(taskId: string): void {
    this.http.get<ApiResponse<TaskResponse>>(`/api/v1/tasks/${taskId}`).subscribe({
      next: (res) => {
        const task = res.data;
        if (task && (task.status === 'COMPLETED' || task.status === 'FAILED')) {
          this.applyCompletedTask(task);
        } else {
          this.streamStatus.set('ERROR');
          this.error.set('SSE telemetry connection interrupted. Verify network connectivity.');
        }
        this.closeStream();
      },
      error: () => {
        this.streamStatus.set('ERROR');
        this.error.set('Lost connection to multi-agent stream emitter.');
        this.closeStream();
      }
    });
  }

  /**
   * Applies an existing completed task state to the active view.
   */
  private applyCompletedTask(task: TaskResponse): void {
    this.activeTaskId.set(task.taskId);
    let reportText = task.resultSummary || '';
    let citationsList: CitationItem[] = [];

    if (task.resultPayload) {
      try {
        const parsed = typeof task.resultPayload === 'string' ? JSON.parse(task.resultPayload) : task.resultPayload;
        if (parsed.report || parsed.draft_report || parsed.result) {
          reportText = parsed.report || parsed.draft_report || parsed.result;
        }
        if (Array.isArray(parsed.citations)) {
          citationsList = parsed.citations;
        }
      } catch {
        if (!reportText) {
          reportText = String(task.resultPayload);
        }
      }
    }

    this.finalReport.set(reportText || 'No executive summary available.');
    this.citations.set(citationsList);
    this.streamStatus.set(task.status === 'FAILED' ? 'FAILED' : 'COMPLETED');
    this.markAllNodesCompleted();
  }

  /**
   * Fetches full historical task details and displays its synthesis in the viewer.
   */
  selectHistoricalTask(taskId: string): Observable<TaskResponse> {
    this.closeStream();
    this.resetActiveTask();
    this.activeTaskId.set(taskId);
    this.streamStatus.set('CONNECTING');

    return this.http.get<ApiResponse<TaskResponse>>(`/api/v1/tasks/${taskId}`).pipe(
      map(res => res.data),
      tap(task => {
        this.applyCompletedTask(task);
        this.appendTimelineEvent({
          taskId,
          nodeName: (task.lastActiveNode || 'CRITIC').toUpperCase(),
          status: task.status,
          durationMs: 0,
          message: `Loaded historical execution from repository (Created: ${new Date(task.createdAt).toLocaleString()})`,
          timestamp: task.createdAt
        });
      }),
      catchError(err => {
        this.streamStatus.set('ERROR');
        this.error.set(`Failed to retrieve task details for ${taskId}`);
        throw err;
      })
    );
  }

  /**
   * Fetches paginated tenant tasks for historical review.
   */
  loadRecentTasks(page: number = 0, size: number = 10): Observable<TaskSummary[]> {
    this.isLoadingRecent.set(true);
    return this.http.get<ApiResponse<TaskPageResponse>>(`/api/v1/tasks?page=${page}&size=${size}&sortBy=createdAt&direction=desc`).pipe(
      map(res => res.data?.items || []),
      tap(items => {
        this.recentTasks.set(items);
        this.isLoadingRecent.set(false);
      }),
      catchError(err => {
        this.isLoadingRecent.set(false);
        throw err;
      })
    );
  }

  /**
   * Discovers active MCP tools from backend agent service.
   */
  loadMcpTools(): Observable<McpToolInfo[]> {
    this.isLoadingTools.set(true);
    return this.http.get<ApiResponse<McpToolsResponse>>('/api/v1/agents/mcp-tools').pipe(
      map(res => res.data?.tools || []),
      tap(tools => {
        this.mcpTools.set(tools);
        this.isLoadingTools.set(false);
      }),
      catchError(err => {
        this.isLoadingTools.set(false);
        // Fallback default enterprise tools if endpoint is starting up
        return of([]);
      })
    );
  }

  /**
   * Resets active task execution state.
   */
  resetActiveTask(): void {
    this.closeStream();
    this.activeTaskId.set(null);
    this.streamStatus.set('IDLE');
    this.timelineEvents.set([]);
    this.activeNode.set(null);
    this.finalReport.set(null);
    this.citations.set([]);
    this.totalDurationMs.set(null);
    this.error.set(null);
    this.nodeStates.set(JSON.parse(JSON.stringify(INITIAL_NODES)));
  }

  /**
   * Safely closes the active SSE stream connection.
   */
  closeStream(): void {
    if (this.eventSource) {
      try {
        this.eventSource.close();
      } catch {
        // no-op
      }
      this.eventSource = null;
    }
  }

  // --- Pipeline Stepper Helper Methods ---

  private setNodeRunning(nodeName: AgentNodeName): void {
    this.activeNode.set(nodeName);
    const nodes = this.nodeStates();
    const targetIdx = nodes.findIndex(n => n.nodeName === nodeName);

    const updated = nodes.map((node, idx) => {
      if (idx < targetIdx) {
        return { ...node, status: 'completed' as const };
      } else if (idx === targetIdx) {
        return { ...node, status: 'running' as const };
      } else {
        return { ...node, status: 'idle' as const };
      }
    });

    this.nodeStates.set(updated);
  }

  private updateNodeFromProgress(progress: TaskProgressEvent): void {
    const nodeName = progress.nodeName as AgentNodeName;
    const nodes = this.nodeStates();
    const targetIdx = nodes.findIndex(n => n.nodeName === nodeName);

    if (targetIdx !== -1) {
      this.activeNode.set(nodeName);
      const updated = nodes.map((node, idx) => {
        if (idx < targetIdx) {
          return { ...node, status: 'completed' as const };
        } else if (idx === targetIdx) {
          const isDone = progress.status === 'COMPLETED' || progress.status === 'FINISHED';
          const nodeStatus: 'completed' | 'running' = isDone ? 'completed' : 'running';
          return {
            ...node,
            status: nodeStatus,
            durationMs: progress.durationMs || node.durationMs,
            message: progress.message
          };
        } else {
          return { ...node, status: 'idle' as const };
        }
      });
      this.nodeStates.set(updated);
    }
  }

  private markAllNodesCompleted(): void {
    this.activeNode.set(null);
    const updated = this.nodeStates().map(n => ({
      ...n,
      status: 'completed' as const
    }));
    this.nodeStates.set(updated);
  }

  private appendTimelineEvent(event: TaskProgressEvent): void {
    this.timelineEvents.update(prev => [...prev, event]);
  }
}
