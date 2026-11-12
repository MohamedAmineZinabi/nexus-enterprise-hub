export type TaskType = 'DEEP_RESEARCH' | 'DOCUMENT_ANALYSIS';

export type AgentNodeName = 'PLANNER' | 'RESEARCHER' | 'SYNTHESIZER' | 'CRITIC';

export type TaskStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';

export type StreamStatus = 'IDLE' | 'CONNECTING' | 'STREAMING' | 'COMPLETED' | 'FAILED' | 'ERROR';

export interface CreateTaskRequest {
  taskType: TaskType;
  prompt: string;
  documentIds?: string[];
  metadata?: Record<string, any>;
}

export interface TaskResponse {
  taskId: string;
  tenantId?: string;
  userId?: string;
  taskType: TaskType;
  status: string;
  prompt: string;
  resultSummary?: string;
  lastActiveNode?: string;
  resultPayload?: string | any;
  completedAt?: string;
  totalTokensUsed?: number;
  traceId?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface TaskSummary {
  taskId: string;
  taskType: TaskType;
  prompt: string;
  status: string;
  lastActiveNode?: string;
  resultPayload?: any;
  resultSummary?: string;
  totalTokensUsed?: number;
  createdAt: string;
  completedAt?: string;
}

export interface TaskPageResponse {
  items: TaskResponse[];
  currentPage: number;
  totalPages: number;
  totalItems: number;
  pageSize: number;
}

export interface TaskProgressEvent {
  taskId: string;
  tenantId?: string;
  nodeName: AgentNodeName | string;
  status: string;
  durationMs: number;
  message: string;
  payload?: Record<string, any>;
  traceId?: string;
  timestamp: string;
}

export interface CitationItem {
  id?: string;
  title?: string;
  chunkId?: string;
  documentId?: string;
  source?: string;
  score?: number;
  snippet?: string;
}

export interface TaskCompletedEvent {
  taskId: string;
  tenantId?: string;
  status: 'COMPLETED' | 'FAILED' | string;
  report: string;
  citations: CitationItem[] | any[];
  totalDurationMs: number;
  errorDetails?: string;
  traceId?: string;
  timestamp?: string;
}

export interface McpToolInfo {
  name: string;
  description: string;
  inputSchemaJson?: string;
  serverOrigin: string;
}

export interface McpToolsResponse {
  totalTools: number;
  tools: McpToolInfo[];
}

export interface NodeExecutionState {
  nodeName: AgentNodeName;
  displayName: string;
  status: 'idle' | 'running' | 'completed' | 'failed';
  durationMs: number;
  message?: string;
}
