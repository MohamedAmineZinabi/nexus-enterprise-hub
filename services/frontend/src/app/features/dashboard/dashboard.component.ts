import { Component, OnInit, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { DocumentService } from '../../core/services/document.service';
import { AgentStudioService } from '../../core/services/agent-studio.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="space-y-6">
      <!-- Welcome Header -->
      <div class="flex flex-col md:flex-row md:items-center md:justify-between gap-4 pb-6 border-b border-slate-800">
        <div>
          <div class="flex items-center gap-2 mb-1">
            <span class="text-xs font-mono text-brand-400 uppercase tracking-widest">Enterprise Command Center</span>
            <span class="inline-flex items-center rounded-md bg-emerald-950 px-2 py-0.5 text-xs font-medium text-emerald-400 ring-1 ring-inset ring-emerald-800/30">
              Live Production
            </span>
          </div>
          <h1 class="text-2xl sm:text-3xl font-bold tracking-tight text-white">
            Welcome back, {{ user()?.fullName || 'Analyst' }}
          </h1>
          <p class="text-sm text-slate-400 mt-1">
            Tenant Workspace: <span class="text-slate-200 font-semibold">{{ tenant()?.name }}</span>
            <span class="mx-2 text-slate-600">•</span>
            Role: <span class="font-mono text-brand-300 text-xs">{{ user()?.role }}</span>
          </p>
        </div>

        <!-- Quick Action Buttons -->
        <div class="flex items-center gap-3">
          <a
            routerLink="/knowledge-base"
            class="inline-flex items-center gap-2 rounded-lg bg-slate-800 hover:bg-slate-700 px-3.5 py-2 text-sm font-semibold text-slate-200 border border-slate-700 transition-colors"
          >
            <svg class="w-4 h-4 text-brand-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
            </svg>
            Knowledge Base
          </a>
          <a
            routerLink="/agent-studio"
            class="inline-flex items-center gap-2 rounded-lg bg-brand-600 hover:bg-brand-500 px-3.5 py-2 text-sm font-semibold text-white shadow-sm shadow-brand-600/30 transition-colors"
          >
            <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
            Launch Agent Task
          </a>
        </div>
      </div>

      <!-- Live Cluster Telemetry & Resource KPI Cards -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <!-- Metric 1: Knowledge Base Documents -->
        <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-5 backdrop-blur-sm">
          <div class="flex items-center justify-between text-xs text-slate-400 mb-2">
            <span>Knowledge Base Assets</span>
            <span class="text-brand-400 font-mono text-[11px]">Qdrant RAG</span>
          </div>
          <div class="text-2xl font-bold text-white tracking-tight">
            {{ totalDocuments() }} <span class="text-sm font-normal text-slate-400">Docs</span>
          </div>
          <p class="text-xs text-slate-400 mt-1">{{ totalChunks() }} vector chunks indexed</p>
          <div class="mt-3 flex items-center gap-1.5 text-xs text-emerald-400">
            <span class="w-2 h-2 rounded-full bg-emerald-500"></span>
            Hybrid search ready
          </div>
        </div>

        <!-- Metric 2: Multi-Agent Cluster -->
        <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-5 backdrop-blur-sm">
          <div class="flex items-center justify-between text-xs text-slate-400 mb-2">
            <span>Autonomous Tasks</span>
            <span class="text-brand-400 font-mono text-[11px]">LangGraph</span>
          </div>
          <div class="text-2xl font-bold text-white tracking-tight">
            {{ totalTasks() }} <span class="text-sm font-normal text-slate-400">Runs</span>
          </div>
          <p class="text-xs text-slate-400 mt-1">4 Nodes: Planner, Researcher, Synth, Critic</p>
          <div class="mt-3 flex items-center gap-1.5 text-xs text-emerald-400">
            <span class="w-2 h-2 rounded-full bg-emerald-500"></span>
            Looping & Critique active
          </div>
        </div>

        <!-- Metric 3: Active MCP Registry Tools -->
        <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-5 backdrop-blur-sm">
          <div class="flex items-center justify-between text-xs text-slate-400 mb-2">
            <span>MCP Tool Registry</span>
            <span class="text-brand-400 font-mono text-[11px]">gRPC + SSE</span>
          </div>
          <div class="text-2xl font-bold text-white tracking-tight">
            {{ totalMcpTools() }} <span class="text-sm font-normal text-slate-400">Tools</span>
          </div>
          <p class="text-xs text-slate-400 mt-1">Filesystem, Search & Summarizer</p>
          <div class="mt-3 flex items-center gap-1.5 text-xs text-emerald-400">
            <span class="w-2 h-2 rounded-full bg-emerald-500"></span>
            Dynamic tool discovery
          </div>
        </div>

        <!-- Metric 4: Real-time Telemetry -->
        <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-5 backdrop-blur-sm">
          <div class="flex items-center justify-between text-xs text-slate-400 mb-2">
            <span>Real-Time SSE</span>
            <span class="text-brand-400 font-mono text-[11px]">Kafka KRaft</span>
          </div>
          <div class="text-2xl font-bold text-white tracking-tight">Live Telemetry</div>
          <p class="text-xs text-slate-400 mt-1">Sub-second event propagation</p>
          <div class="mt-3 flex items-center gap-1.5 text-xs text-emerald-400">
            <span class="w-2 h-2 rounded-full bg-emerald-500"></span>
            Redis Redlock coordinated
          </div>
        </div>
      </div>

      <!-- Live Activities Grid -->
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <!-- Recent Tasks Feed -->
        <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-5 backdrop-blur-sm">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-sm font-semibold text-white flex items-center gap-2">
              <svg class="w-4 h-4 text-brand-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
              Recent Autonomous Agent Tasks
            </h3>
            <a routerLink="/agent-studio" class="text-xs text-brand-400 hover:underline">
              Open Studio &rarr;
            </a>
          </div>

          @if (recentTasks().length === 0) {
            <p class="text-xs text-slate-500 py-6 text-center">No tasks executed yet.</p>
          } @else {
            <div class="space-y-2">
              @for (task of recentTasks().slice(0, 4); track task.taskId) {
                <div class="p-3 rounded-lg bg-slate-950/60 border border-slate-800/80 flex items-center justify-between text-xs">
                  <div class="min-w-0 pr-2">
                    <p class="font-medium text-slate-200 truncate">{{ task.prompt }}</p>
                    <p class="text-[11px] font-mono text-slate-500 mt-0.5">
                      {{ task.taskType }} • {{ task.taskId.substring(0, 8) }}...
                    </p>
                  </div>
                  <span [class]="getTaskBadgeClass(task.status)">
                    {{ task.status }}
                  </span>
                </div>
              }
            </div>
          }
        </div>

        <!-- Recent Documents Feed -->
        <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-5 backdrop-blur-sm">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-sm font-semibold text-white flex items-center gap-2">
              <svg class="w-4 h-4 text-brand-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
              Knowledge Base Repository
            </h3>
            <a routerLink="/knowledge-base" class="text-xs text-brand-400 hover:underline">
              Manage Docs &rarr;
            </a>
          </div>

          @if (documents().length === 0) {
            <p class="text-xs text-slate-500 py-6 text-center">No documents indexed in knowledge base.</p>
          } @else {
            <div class="space-y-2">
              @for (doc of documents().slice(0, 4); track doc.documentId || doc.id) {
                <div class="p-3 rounded-lg bg-slate-950/60 border border-slate-800/80 flex items-center justify-between text-xs">
                  <div class="min-w-0 pr-2">
                    <p class="font-medium text-slate-200 truncate">{{ doc.fileName || doc.filename }}</p>
                    <p class="text-[11px] font-mono text-slate-500 mt-0.5">
                      {{ doc.chunksCreated || doc.chunksCount || 0 }} chunks • {{ doc.vectorCollection }}
                    </p>
                  </div>
                  <span class="inline-flex items-center px-2 py-0.5 rounded text-[10px] font-mono bg-emerald-950 text-emerald-400 ring-1 ring-inset ring-emerald-800/40 shrink-0">
                    {{ doc.status }}
                  </span>
                </div>
              }
            </div>
          }
        </div>
      </div>

      <!-- Active Tenant Metadata Card -->
      <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-6 backdrop-blur-sm">
        <h2 class="text-base font-semibold text-white mb-4 flex items-center gap-2">
          <svg class="w-5 h-5 text-brand-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4" />
          </svg>
          Active Tenant & Security Context
        </h2>

        <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div class="bg-slate-950/60 rounded-lg p-4 border border-slate-800/80">
            <span class="text-xs font-mono text-slate-400 uppercase">Tenant Identifier</span>
            <p class="text-sm font-mono text-brand-300 mt-1 break-all">{{ tenant()?.id || 'N/A' }}</p>
          </div>
          <div class="bg-slate-950/60 rounded-lg p-4 border border-slate-800/80">
            <span class="text-xs font-mono text-slate-400 uppercase">Tenant Name & Slug</span>
            <p class="text-sm font-semibold text-slate-200 mt-1">
              {{ tenant()?.name || 'N/A' }}
              <span class="text-xs font-mono text-slate-400">({{ tenant()?.slug || 'slug' }})</span>
            </p>
          </div>
          <div class="bg-slate-950/60 rounded-lg p-4 border border-slate-800/80">
            <span class="text-xs font-mono text-slate-400 uppercase">User Identity</span>
            <p class="text-sm font-semibold text-slate-200 mt-1">
              {{ user()?.fullName }} <span class="text-xs text-slate-400">({{ user()?.email }})</span>
            </p>
          </div>
        </div>
      </div>
    </div>
  `
})
export class DashboardComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly documentService = inject(DocumentService);
  private readonly agentStudioService = inject(AgentStudioService);

  readonly user = this.authService.currentUser;
  readonly tenant = this.authService.currentTenant;

  readonly documents = this.documentService.documents;
  readonly recentTasks = this.agentStudioService.recentTasks;

  readonly totalDocuments = computed(() => this.documents().length);
  readonly totalChunks = computed(() =>
    this.documents().reduce((acc, d) => acc + (d.chunksCreated || d.chunksCount || 0), 0)
  );
  readonly totalTasks = computed(() => this.recentTasks().length);
  readonly totalMcpTools = computed(() => this.agentStudioService.mcpTools().length);

  ngOnInit(): void {
    this.documentService.loadDocuments().subscribe();
    this.agentStudioService.loadRecentTasks().subscribe();
    this.agentStudioService.loadMcpTools().subscribe();
  }

  getTaskBadgeClass(status: string): string {
    const base = 'inline-flex items-center px-2 py-0.5 rounded text-[10px] font-mono ring-1 ring-inset shrink-0 ';
    switch (status) {
      case 'COMPLETED':
        return base + 'bg-emerald-950 text-emerald-400 ring-emerald-800';
      case 'IN_PROGRESS':
      case 'PENDING':
        return base + 'bg-yellow-950 text-yellow-400 ring-yellow-800';
      case 'FAILED':
        return base + 'bg-red-950 text-red-400 ring-red-800';
      default:
        return base + 'bg-slate-800 text-slate-400 ring-slate-700';
    }
  }
}
