import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AgentStudioService } from '../../core/services/agent-studio.service';
import { DocumentService } from '../../core/services/document.service';
import {
  AgentNodeName,
  CitationItem,
  CreateTaskRequest,
  NodeExecutionState,
  TaskProgressEvent,
  TaskSummary,
  TaskType
} from '../../core/models/agent.models';
import { DocumentItem } from '../../core/models/document.models';

interface QuickPromptChip {
  label: string;
  type: TaskType;
  prompt: string;
}

@Component({
  selector: 'app-agent-studio',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="space-y-6">
      <!-- Header & Workspace Context -->
      <div class="flex flex-col md:flex-row md:items-center md:justify-between gap-4 pb-6 border-b border-slate-800">
        <div>
          <div class="flex items-center gap-2 mb-1">
            <span class="text-xs font-mono text-brand-400 uppercase tracking-widest">Multi-Agent Workflow Studio</span>
            <span class="inline-flex items-center rounded-md bg-emerald-950 px-2 py-0.5 text-xs font-medium text-emerald-400 ring-1 ring-inset ring-emerald-800/30">
              SSE Telemetry Live
            </span>
            <span class="inline-flex items-center rounded-md bg-slate-800 px-2 py-0.5 text-xs font-mono text-slate-300 border border-slate-700">
              LangGraph 4-Node Loop
            </span>
          </div>
          <h1 class="text-2xl sm:text-3xl font-bold tracking-tight text-white">
            Agent Studio & Real-Time Telemetry Viewer
          </h1>
          <p class="text-sm text-slate-400 mt-1">
            Autonomous multi-agent orchestration for tenant:
            <span class="text-slate-200 font-semibold">{{ tenant()?.name }}</span>
            <span class="mx-2 text-slate-600">•</span>
            Connected to Redis Redlock & Kafka Stream Bridge
          </p>
        </div>

        <div class="flex items-center gap-3">
          <button
            type="button"
            (click)="resetForm()"
            class="inline-flex items-center gap-2 rounded-lg bg-slate-800 hover:bg-slate-700 px-3.5 py-2 text-sm font-semibold text-slate-200 border border-slate-700 transition-colors"
          >
            <svg class="w-4 h-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
            </svg>
            New Task
          </button>
          <a
            routerLink="/knowledge-base"
            class="inline-flex items-center gap-2 rounded-lg bg-slate-800 hover:bg-slate-700 px-3.5 py-2 text-sm font-semibold text-slate-200 border border-slate-700 transition-colors"
          >
            <svg class="w-4 h-4 text-brand-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
            </svg>
            Grounding Documents
          </a>
        </div>
      </div>

      <!-- Main Two-Column Layout -->
      <div class="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        
        <!-- LEFT COLUMN: Command & Context Composer (5 Cols on LG, 4 on XL) -->
        <div class="lg:col-span-5 xl:col-span-4 space-y-6">
          
          <!-- Command Composer Card -->
          <div class="bg-slate-900/70 border border-slate-800 rounded-2xl p-5 backdrop-blur-sm shadow-xl">
            <h2 class="text-base font-semibold text-white mb-4 flex items-center gap-2">
              <svg class="w-5 h-5 text-brand-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
              Command & Context Composer
            </h2>

            <!-- Mode Selector Pills -->
            <div class="mb-5">
              <label class="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                Execution Mode
              </label>
              <div class="grid grid-cols-2 gap-2 p-1 bg-slate-950/80 rounded-xl border border-slate-800">
                <button
                  type="button"
                  (click)="setTaskType('DEEP_RESEARCH')"
                  [class]="getModeBtnClass('DEEP_RESEARCH')"
                >
                  <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                  </svg>
                  Deep Research
                </button>
                <button
                  type="button"
                  (click)="setTaskType('DOCUMENT_ANALYSIS')"
                  [class]="getModeBtnClass('DOCUMENT_ANALYSIS')"
                >
                  <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                  </svg>
                  Doc Grounding
                </button>
              </div>
              <p class="text-[11px] text-slate-400 mt-1.5 px-1">
                @if (taskType() === 'DEEP_RESEARCH') {
                  Multi-node web & internal synthesis with recursive critic validation.
                } @else {
                  Constrained RAG analysis grounded strictly on selected knowledge base files.
                }
              </p>
            </div>

            <!-- Quick-Fill Chips -->
            <div class="mb-4">
              <label class="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                Quick-Fill Enterprise Templates
              </label>
              <div class="flex flex-wrap gap-1.5">
                @for (chip of quickPromptChips; track chip.label) {
                  <button
                    type="button"
                    (click)="applyPromptChip(chip)"
                    class="text-[11px] font-medium px-2.5 py-1 rounded-full bg-slate-800 hover:bg-slate-700 text-slate-300 hover:text-white border border-slate-700/80 transition-colors"
                  >
                    {{ chip.label }}
                  </button>
                }
              </div>
            </div>

            <!-- Prompt Textarea -->
            <div class="mb-4">
              <div class="flex items-center justify-between mb-1.5">
                <label for="promptInput" class="block text-xs font-semibold uppercase tracking-wider text-slate-300">
                  Instruction / Prompt
                </label>
                <span class="text-[11px] font-mono text-slate-500">
                  {{ promptText().length }} chars
                </span>
              </div>
              <textarea
                id="promptInput"
                [ngModel]="promptText()"
                (ngModelChange)="promptText.set($event)"
                rows="4"
                placeholder="Describe your research inquiry or analytical objective in detail..."
                class="w-full rounded-xl bg-slate-950/80 border border-slate-700/80 px-3.5 py-2.5 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-brand-500 transition-all resize-y"
              ></textarea>
            </div>

            <!-- Knowledge Base Grounding Selection -->
            <div class="mb-5 border-t border-slate-800 pt-4">
              <div class="flex items-center justify-between mb-2">
                <label class="block text-xs font-semibold uppercase tracking-wider text-slate-300">
                  Knowledge Base Grounding ({{ selectedDocIds().length }} selected)
                </label>
                <button
                  type="button"
                  (click)="toggleAllDocuments()"
                  class="text-[11px] text-brand-400 hover:text-brand-300 underline"
                >
                  {{ selectedDocIds().length === availableDocuments().length && availableDocuments().length > 0 ? 'Clear all' : 'Select all' }}
                </button>
              </div>

              @if (availableDocuments().length === 0) {
                <div class="bg-slate-950/50 rounded-xl p-3 border border-slate-800/80 text-center">
                  <p class="text-xs text-slate-400">No documents found in knowledge base.</p>
                  <a routerLink="/knowledge-base" class="text-xs text-brand-400 hover:underline mt-1 inline-block">
                    + Upload documents to ground agents
                  </a>
                </div>
              } @else {
                <div class="max-h-36 overflow-y-auto space-y-1.5 pr-1 custom-scrollbar">
                  @for (doc of availableDocuments(); track doc.documentId || doc.id) {
                    <div
                      (click)="toggleDocument(doc.documentId || doc.id)"
                      [class]="getDocCardClass(doc.documentId || doc.id)"
                    >
                      <div class="flex items-center gap-2 min-w-0">
                        <input
                          type="checkbox"
                          [checked]="isDocSelected(doc.documentId || doc.id)"
                          class="rounded bg-slate-900 border-slate-700 text-brand-600 focus:ring-brand-500 h-3.5 w-3.5"
                        />
                        <span class="truncate font-medium text-slate-200">
                          {{ doc.fileName || doc.filename }}
                        </span>
                      </div>
                      <span class="text-[10px] font-mono text-slate-400 shrink-0">
                        {{ doc.chunksCreated || doc.chunksCount || 0 }} chunks
                      </span>
                    </div>
                  }
                </div>
              }
            </div>

            <!-- Active MCP Tools Card -->
            <div class="mb-5 border-t border-slate-800 pt-4">
              <div class="flex items-center justify-between mb-2">
                <span class="text-xs font-semibold uppercase tracking-wider text-slate-400">
                  Active MCP Tools ({{ mcpTools().length }})
                </span>
                <span class="inline-flex items-center gap-1 text-[11px] text-emerald-400 font-mono">
                  <span class="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span>
                  Registry Active
                </span>
              </div>
              <div class="grid grid-cols-2 gap-1.5">
                @for (tool of mcpTools(); track tool.name) {
                  <div class="bg-slate-950/60 border border-slate-800/80 rounded-lg p-2 text-left">
                    <div class="text-[11px] font-mono font-medium text-brand-300 truncate" [title]="tool.name">
                      {{ tool.name }}
                    </div>
                    <div class="text-[10px] text-slate-400 truncate mt-0.5" [title]="tool.description">
                      {{ tool.description }}
                    </div>
                  </div>
                }
              </div>
            </div>

            <!-- Submit Action Button -->
            <button
              type="button"
              (click)="dispatchTask()"
              [disabled]="!isFormValid() || isStreaming()"
              class="w-full inline-flex items-center justify-center gap-2 rounded-xl bg-brand-600 hover:bg-brand-500 disabled:opacity-50 disabled:cursor-not-allowed px-4 py-3 text-sm font-semibold text-white shadow-lg shadow-brand-600/30 transition-all"
            >
              @if (isStreaming()) {
                <svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
                  <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                  <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                </svg>
                Autonomous Graph In Flight...
              } @else {
                <svg class="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14 5l7 7m0 0l-7 7m7-7H3" />
                </svg>
                Launch Multi-Agent Task
              }
            </button>

            @if (agentService.error()) {
              <div class="mt-3 p-2.5 rounded-lg bg-red-950/50 border border-red-800/60 text-xs text-red-300">
                {{ agentService.error() }}
              </div>
            }
          </div>

          <!-- Recent Executions History Card -->
          <div class="bg-slate-900/70 border border-slate-800 rounded-2xl p-5 backdrop-blur-sm shadow-xl">
            <div class="flex items-center justify-between mb-3">
              <h3 class="text-sm font-semibold text-white flex items-center gap-2">
                <svg class="w-4 h-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                Recent Executions
              </h3>
              <button
                type="button"
                (click)="loadRecentTasks()"
                class="text-[11px] text-slate-400 hover:text-slate-200"
                title="Refresh tasks"
              >
                Refresh
              </button>
            </div>

            @if (recentTasks().length === 0) {
              <p class="text-xs text-slate-500 py-3 text-center">No recent tasks recorded for tenant.</p>
            } @else {
              <div class="space-y-2 max-h-56 overflow-y-auto pr-1 custom-scrollbar">
                @for (task of recentTasks(); track task.taskId) {
                  <div
                    (click)="selectHistoricalTask(task.taskId)"
                    [class]="getRecentTaskCardClass(task.taskId)"
                  >
                    <div class="flex items-center justify-between gap-2 mb-1">
                      <span class="font-mono text-[10px] text-slate-400">
                        {{ task.taskId.substring(0, 8) }}...
                      </span>
                      <span [class]="getTaskBadgeClass(task.status)">
                        {{ task.status }}
                      </span>
                    </div>
                    <p class="text-slate-200 line-clamp-2 text-[11px]">
                      {{ task.prompt }}
                    </p>
                    <div class="mt-1 text-[10px] font-mono text-slate-500 flex items-center justify-between">
                      <span>{{ formatTimestamp(task.createdAt) }}</span>
                      <span>{{ task.taskType }}</span>
                    </div>
                  </div>
                }
              </div>
            }
          </div>

        </div>

        <!-- RIGHT COLUMN: Live Multi-Agent Pipeline & Executive Report (7 Cols on LG, 8 on XL) -->
        <div class="lg:col-span-7 xl:col-span-8 space-y-6">

          <!-- Active Execution Status Bar -->
          <div class="bg-slate-900/70 border border-slate-800 rounded-2xl p-5 backdrop-blur-sm shadow-xl flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-xl bg-brand-600/10 border border-brand-500/20 text-brand-400 flex items-center justify-center shrink-0">
                <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
                </svg>
              </div>
              <div>
                <div class="text-xs text-slate-400 flex items-center gap-2">
                  <span>Active Execution Pipeline</span>
                  @if (activeTaskId()) {
                    <span class="font-mono text-brand-300">#{{ activeTaskId() }}</span>
                    <button
                      type="button"
                      (click)="copyTaskId()"
                      class="text-slate-500 hover:text-slate-300"
                      title="Copy Task ID"
                    >
                      <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                      </svg>
                    </button>
                  }
                </div>
                <div class="text-sm font-semibold text-white mt-0.5">
                  @if (isStreaming()) {
                    Streaming Telemetry from Kafka KRaft & Redis Redlock
                  } @else if (streamStatus() === 'COMPLETED') {
                    Synthesis Finalized & Stored in Redis Cache
                  } @else if (streamStatus() === 'CONNECTING') {
                    Establishing SSE Emitter Channel...
                  } @else {
                    Agent Studio Ready for Inquiries
                  }
                </div>
              </div>
            </div>

            <!-- Status Pill & Timer -->
            <div class="flex items-center gap-3">
              @if (getFormattedDuration()) {
                <span class="text-xs font-mono text-slate-300 bg-slate-950 px-2.5 py-1 rounded-md border border-slate-800">
                  {{ getFormattedDuration() }}
                </span>
              }
              <span [class]="getStreamStatusBadgeClass(streamStatus())">
                @if (streamStatus() === 'STREAMING') {
                  <span class="w-2 h-2 rounded-full bg-brand-400 animate-ping"></span>
                } @else if (streamStatus() === 'COMPLETED') {
                  <span class="w-2 h-2 rounded-full bg-emerald-400"></span>
                }
                {{ streamStatus() }}
              </span>
            </div>
          </div>

          <!-- 4-Stage Visual Node Stepper -->
          <div class="bg-slate-900/70 border border-slate-800 rounded-2xl p-5 backdrop-blur-sm shadow-xl">
            <div class="flex items-center justify-between mb-4">
              <h3 class="text-xs font-semibold uppercase tracking-wider text-slate-400">
                LangGraph State Transitions (4-Agent Ring)
              </h3>
              <span class="text-[11px] font-mono text-slate-500">
                Idempotent Lock under Redis Redlock
              </span>
            </div>

            <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
              @for (node of nodeStates(); track node.nodeName) {
                <div [class]="getNodeCardClass(node)">
                  <div class="flex items-center justify-between mb-2">
                    <span class="text-xs font-semibold text-white">{{ node.displayName }}</span>
                    @if (node.status === 'running') {
                      <span class="flex h-2.5 w-2.5 relative">
                        <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-brand-400 opacity-75"></span>
                        <span class="relative inline-flex rounded-full h-2.5 w-2.5 bg-brand-500"></span>
                      </span>
                    } @else if (node.status === 'completed') {
                      <svg class="w-4 h-4 text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                      </svg>
                    } @else {
                      <span class="w-2 h-2 rounded-full bg-slate-700"></span>
                    }
                  </div>

                  <div class="text-[11px] text-slate-400">
                    @if (node.nodeName === 'PLANNER') {
                      Decomposes goal & formulates execution steps.
                    } @else if (node.nodeName === 'RESEARCHER') {
                      Executes hybrid search & MCP tool queries.
                    } @else if (node.nodeName === 'SYNTHESIZER') {
                      Distills cross-node findings into draft report.
                    } @else if (node.nodeName === 'CRITIC') {
                      Hallucination checks & verified executive synthesis.
                    }
                  </div>

                  <div class="mt-3 flex items-center justify-between text-[10px] font-mono border-t border-slate-800/80 pt-2 text-slate-500">
                    <span>
                      @if (node.status === 'running') {
                        <span class="text-brand-300 animate-pulse">Running node...</span>
                      } @else if (node.status === 'completed') {
                        <span class="text-emerald-400 font-semibold">Done</span>
                      } @else {
                        <span>Standby</span>
                      }
                    </span>
                    @if (node.durationMs > 0) {
                      <span class="text-slate-400">{{ node.durationMs }}ms</span>
                    }
                  </div>
                </div>
              }
            </div>
          </div>

          <!-- Live Thought-Stream Terminal / Log Feed -->
          <div class="bg-slate-950 border border-slate-800 rounded-2xl overflow-hidden shadow-2xl">
            <!-- Terminal Header Bar -->
            <div class="bg-slate-900/90 px-4 py-2.5 border-b border-slate-800 flex items-center justify-between">
              <div class="flex items-center gap-2">
                <span class="w-3 h-3 rounded-full bg-red-500/80 inline-block"></span>
                <span class="w-3 h-3 rounded-full bg-yellow-500/80 inline-block"></span>
                <span class="w-3 h-3 rounded-full bg-emerald-500/80 inline-block"></span>
                <span class="ml-2 text-xs font-mono text-slate-400">
                  SSE Live Telemetry & Node Thought-Stream
                </span>
              </div>
              <div class="flex items-center gap-2">
                <span class="text-[11px] font-mono text-slate-500">
                  {{ timelineEvents().length }} events received
                </span>
              </div>
            </div>

            <!-- Terminal Feed Content -->
            <div class="p-4 max-h-64 overflow-y-auto font-mono text-xs space-y-2 custom-scrollbar bg-slate-950/90">
              @if (timelineEvents().length === 0) {
                <div class="py-6 text-center text-slate-600">
                  <p class="text-xs">&gt; Awaiting agent invocation or telemetry events...</p>
                  <p class="text-[11px] text-slate-700 mt-1">Submit an instruction to observe real-time LangGraph node execution.</p>
                </div>
              } @else {
                @for (evt of timelineEvents(); track evt.timestamp + evt.nodeName) {
                  <div class="flex items-start gap-2 text-slate-300 hover:bg-slate-900/50 p-1 rounded transition-colors">
                    <span class="text-slate-500 select-none shrink-0 text-[11px]">
                      {{ formatTimeOnly(evt.timestamp) }}
                    </span>
                    <span [class]="getLogNodeBadgeClass(evt.nodeName)">
                      [{{ evt.nodeName }}]
                    </span>
                    <span class="text-slate-200 flex-1 break-words">
                      {{ evt.message }}
                    </span>
                    @if (evt.durationMs > 0 && evt.durationMs < 1000000) {
                      <span class="text-[10px] text-slate-500 shrink-0">
                        +{{ evt.durationMs }}ms
                      </span>
                    }
                  </div>
                }
                @if (isStreaming()) {
                  <div class="flex items-center gap-2 text-brand-400 text-xs animate-pulse pt-1">
                    <span>&gt;</span>
                    <span class="w-2 h-4 bg-brand-400 inline-block animate-pulse"></span>
                    <span class="text-[11px] font-mono text-slate-400">Stream active... receiving Kafka telemetry</span>
                  </div>
                }
              }
            </div>
          </div>

          <!-- Executive Intelligence Report Viewer -->
          <div class="bg-slate-900/70 border border-slate-800 rounded-2xl p-6 backdrop-blur-sm shadow-xl">
            <div class="flex flex-col sm:flex-row sm:items-center justify-between pb-4 border-b border-slate-800 gap-3">
              <div>
                <h3 class="text-lg font-bold text-white flex items-center gap-2">
                  <svg class="w-5 h-5 text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                  </svg>
                  Executive Synthesis Report
                </h3>
                <p class="text-xs text-slate-400 mt-0.5">
                  Distilled by Synthesizer Agent & verified by Critic against hallucination thresholds.
                </p>
              </div>

              @if (finalReport()) {
                <div class="flex items-center gap-2">
                  <button
                    type="button"
                    (click)="copyReport()"
                    class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-xs font-semibold text-slate-200 border border-slate-700 transition-colors"
                  >
                    @if (reportCopied()) {
                      <svg class="w-4 h-4 text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                      </svg>
                      Copied!
                    } @else {
                      <svg class="w-4 h-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                      </svg>
                      Copy Markdown
                    }
                  </button>
                </div>
              }
            </div>

            <!-- Report Body -->
            <div class="mt-4">
              @if (!finalReport()) {
                <div class="py-12 text-center">
                  <div class="w-12 h-12 rounded-xl bg-slate-800/80 text-slate-500 flex items-center justify-center mx-auto mb-3">
                    <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 20H5a2 2 0 01-2-2V6a2 2 0 012-2h10a2 2 0 012 2v1m2 13a2 2 0 01-2-2V7m2 13a2 2 0 002-2V9a2 2 0 00-2-2h-2m-4-3H9M7 16h6M7 8h6v4H7V8z" />
                    </svg>
                  </div>
                  <h4 class="text-sm font-semibold text-slate-300">No report generated yet</h4>
                  <p class="text-xs text-slate-500 max-w-sm mx-auto mt-1">
                    Select a quick-fill prompt or compose a research query on the left, then launch the agent task.
                  </p>
                </div>
              } @else {
                <!-- Formatted Report Render -->
                <div class="prose prose-invert max-w-none text-slate-200 text-sm leading-relaxed space-y-4 font-sans">
                  <div [innerHTML]="renderMarkdown(finalReport()!)"></div>
                </div>

                <!-- Grounding Citations Pills -->
                @if (citations().length > 0) {
                  <div class="mt-6 pt-4 border-t border-slate-800">
                    <h4 class="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                      Verified Citations & Sources ({{ citations().length }})
                    </h4>
                    <div class="flex flex-wrap gap-2">
                      @for (cit of citations(); track $index) {
                        <div class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-300">
                          <svg class="w-3.5 h-3.5 text-brand-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
                          </svg>
                          <span class="font-medium text-slate-200">
                            {{ getCitationLabel(cit) }}
                          </span>
                          @if (getCitationScore(cit)) {
                            <span class="font-mono text-[10px] text-emerald-400">
                              {{ getCitationScore(cit) }}
                            </span>
                          }
                        </div>
                      }
                    </div>
                  </div>
                }
              }
            </div>
          </div>

        </div>

      </div>
    </div>
  `,
  styles: [`
    .custom-scrollbar::-webkit-scrollbar {
      width: 6px;
    }
    .custom-scrollbar::-webkit-scrollbar-track {
      background: rgba(15, 23, 42, 0.6);
      border-radius: 4px;
    }
    .custom-scrollbar::-webkit-scrollbar-thumb {
      background: rgba(71, 85, 105, 0.6);
      border-radius: 4px;
    }
    .custom-scrollbar::-webkit-scrollbar-thumb:hover {
      background: rgba(100, 116, 139, 0.8);
    }
  `]
})
export class AgentStudioComponent implements OnInit, OnDestroy {
  private readonly authService = inject(AuthService);
  readonly agentService = inject(AgentStudioService);
  readonly documentService = inject(DocumentService);

  readonly tenant = this.authService.currentTenant;

  // Local Component State
  readonly taskType = signal<TaskType>('DEEP_RESEARCH');
  readonly promptText = signal<string>('Investigate financial risks and cross-border regulatory implications for Q3 report');
  readonly selectedDocIds = signal<string[]>([]);
  readonly reportCopied = signal<boolean>(false);
  readonly taskStartTime = signal<number | null>(null);

  // Mapped Signals from Services
  readonly activeTaskId = this.agentService.activeTaskId;
  readonly streamStatus = this.agentService.streamStatus;
  readonly timelineEvents = this.agentService.timelineEvents;
  readonly nodeStates = this.agentService.nodeStates;
  readonly finalReport = this.agentService.finalReport;
  readonly citations = this.agentService.citations;
  readonly totalDurationMs = this.agentService.totalDurationMs;
  readonly recentTasks = this.agentService.recentTasks;
  readonly mcpTools = this.agentService.mcpTools;
  readonly availableDocuments = this.documentService.documents;

  readonly isStreaming = computed(() => {
    const s = this.streamStatus();
    return s === 'CONNECTING' || s === 'STREAMING';
  });

  readonly isFormValid = computed(() => {
    return this.promptText().trim().length >= 3;
  });

  readonly quickPromptChips: QuickPromptChip[] = [
    {
      label: 'Financial Risks & Compliance',
      type: 'DEEP_RESEARCH',
      prompt: 'Synthesize cross-border regulatory compliance risks and AML/KYC enforcement precedents for multi-tenant banking platforms in Q3 2026.'
    },
    {
      label: 'Hybrid Search Benchmark',
      type: 'DEEP_RESEARCH',
      prompt: 'Evaluate dense vector semantic search against BM25 sparse lexical retrieval and cross-encoder reranking latency in Qdrant clusters.'
    },
    {
      label: 'Enterprise Knowledge Grounding',
      type: 'DOCUMENT_ANALYSIS',
      prompt: 'Extract all executive security policies, data residency requirements, and cryptographic key rotation cycles from the uploaded documentation.'
    },
    {
      label: 'LangGraph State Architecture',
      type: 'DEEP_RESEARCH',
      prompt: 'Analyze state persistence with Redis Redlock distributed locks, checkpointer replay, and idempotent task consumer patterns.'
    }
  ];

  ngOnInit(): void {
    // Preload tenant documents, MCP tools registry, and recent execution history
    this.documentService.loadDocuments().subscribe();
    this.agentService.loadMcpTools().subscribe();
    this.agentService.loadRecentTasks().subscribe();

    // If there is an existing task, select the first one to show historic synthesis
    const existing = this.recentTasks();
    if (existing.length > 0 && !this.activeTaskId()) {
      this.agentService.selectHistoricalTask(existing[0].taskId).subscribe();
    }
  }

  ngOnDestroy(): void {
    this.agentService.closeStream();
  }

  setTaskType(type: TaskType): void {
    this.taskType.set(type);
  }

  getModeBtnClass(type: TaskType): string {
    const base = 'flex items-center justify-center gap-2 py-2 px-3 rounded-lg text-xs font-semibold transition-all ';
    return this.taskType() === type
      ? base + 'bg-brand-600 text-white shadow-md'
      : base + 'text-slate-400 hover:text-slate-200';
  }

  getDocCardClass(docId?: string): string {
    const base = 'flex items-center justify-between p-2 rounded-lg border text-xs cursor-pointer transition-colors ';
    return this.isDocSelected(docId)
      ? base + 'bg-brand-950 border-brand-700'
      : base + 'bg-slate-950 border-slate-800 hover:border-slate-700';
  }

  getRecentTaskCardClass(taskId: string): string {
    const base = 'p-2.5 rounded-xl border text-xs cursor-pointer transition-colors ';
    return this.activeTaskId() === taskId
      ? base + 'border-brand-500 bg-brand-950'
      : base + 'border-slate-800 bg-slate-950 hover:border-slate-700';
  }

  getTaskBadgeClass(status: string): string {
    const base = 'inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-mono ring-1 ring-inset ';
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

  getStreamStatusBadgeClass(status: string): string {
    const base = 'inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-mono ring-1 ring-inset ';
    switch (status) {
      case 'COMPLETED':
        return base + 'bg-emerald-950 text-emerald-400 ring-emerald-800';
      case 'STREAMING':
        return base + 'bg-brand-950 text-brand-300 ring-brand-800';
      case 'CONNECTING':
        return base + 'bg-yellow-950 text-yellow-400 ring-yellow-800';
      case 'FAILED':
      case 'ERROR':
        return base + 'bg-red-950 text-red-400 ring-red-800';
      default:
        return base + 'bg-slate-800 text-slate-400 ring-slate-700';
    }
  }

  getNodeCardClass(node: NodeExecutionState): string {
    const base = 'rounded-xl p-3 border transition-all relative overflow-hidden ';
    if (node.status === 'running') {
      return base + 'border-brand-500 bg-brand-950 shadow-brand-900 shadow-lg';
    } else if (node.status === 'completed') {
      return base + 'border-emerald-700 bg-emerald-950';
    } else {
      return base + 'border-slate-800 bg-slate-950';
    }
  }

  getLogNodeBadgeClass(nodeName: string): string {
    const base = 'font-semibold shrink-0 text-[11px] ';
    switch (nodeName) {
      case 'PLANNER':
        return base + 'text-brand-400';
      case 'RESEARCHER':
        return base + 'text-amber-400';
      case 'SYNTHESIZER':
        return base + 'text-cyan-400';
      case 'CRITIC':
        return base + 'text-emerald-400';
      default:
        return base + 'text-slate-300';
    }
  }

  applyPromptChip(chip: QuickPromptChip): void {
    this.taskType.set(chip.type);
    this.promptText.set(chip.prompt);
  }

  isDocSelected(docId?: string): boolean {
    if (!docId) return false;
    return this.selectedDocIds().includes(docId);
  }

  toggleDocument(docId?: string): void {
    if (!docId) return;
    this.selectedDocIds.update(current => {
      if (current.includes(docId)) {
        return current.filter(id => id !== docId);
      } else {
        return [...current, docId];
      }
    });
  }

  toggleAllDocuments(): void {
    const all = this.availableDocuments().map(d => d.documentId || d.id);
    if (this.selectedDocIds().length === all.length) {
      this.selectedDocIds.set([]);
    } else {
      this.selectedDocIds.set(all);
    }
  }

  dispatchTask(): void {
    if (!this.isFormValid() || this.isStreaming()) return;

    this.taskStartTime.set(Date.now());
    const request: CreateTaskRequest = {
      taskType: this.taskType(),
      prompt: this.promptText().trim(),
      documentIds: this.selectedDocIds().length > 0 ? this.selectedDocIds() : undefined
    };

    this.agentService.submitAndStreamTask(request).subscribe();
  }

  selectHistoricalTask(taskId: string): void {
    this.taskStartTime.set(null);
    this.agentService.selectHistoricalTask(taskId).subscribe();
  }

  loadRecentTasks(): void {
    this.agentService.loadRecentTasks().subscribe();
  }

  resetForm(): void {
    this.taskStartTime.set(null);
    this.agentService.resetActiveTask();
    this.promptText.set('');
    this.selectedDocIds.set([]);
  }

  getFormattedDuration(): string | null {
    const total = this.totalDurationMs();
    if (total != null && total > 0 && total < 1000000) {
      return (total / 1000).toFixed(2) + 's total';
    }
    const start = this.taskStartTime();
    if (start) {
      const elapsed = Date.now() - start;
      return (elapsed / 1000).toFixed(2) + 's total';
    }
    return null;
  }

  getCitationLabel(cit: any): string {
    if (!cit) return 'Reference';
    if (typeof cit === 'string') return cit;
    return cit.title || cit.source || cit.chunkId || 'Reference';
  }

  getCitationScore(cit: any): string | null {
    if (!cit || typeof cit === 'string' || cit.score == null) return null;
    return (cit.score * 100).toFixed(1) + '%';
  }

  copyTaskId(): void {
    const id = this.activeTaskId();
    if (id && typeof navigator !== 'undefined') {
      navigator.clipboard.writeText(id);
    }
  }

  copyReport(): void {
    const report = this.finalReport();
    if (report && typeof navigator !== 'undefined') {
      navigator.clipboard.writeText(report).then(() => {
        this.reportCopied.set(true);
        setTimeout(() => this.reportCopied.set(false), 2000);
      });
    }
  }

  formatTimestamp(isoString?: string): string {
    if (!isoString) return '';
    try {
      const d = new Date(isoString);
      return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch {
      return isoString;
    }
  }

  formatTimeOnly(isoString?: string): string {
    if (!isoString) return '';
    try {
      const d = new Date(isoString);
      return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch {
      return isoString;
    }
  }

  /**
   * Safe lightweight markdown formatter for executive synthesis viewer.
   */
  renderMarkdown(content: string): string {
    if (!content) return '';

    // Sanitize basic tags to prevent XSS
    let escaped = content
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');

    // Code blocks: ```...```
    escaped = escaped.replace(/```([a-zA-Z]*)\n([\s\S]*?)```/g, (_m, lang, code) => {
      return `<pre class="bg-slate-950 p-4 rounded-xl border border-slate-800 text-xs font-mono text-slate-300 overflow-x-auto my-3"><code class="language-${lang}">${code.trim()}</code></pre>`;
    });

    // Inline code: `...`
    escaped = escaped.replace(/`([^`]+)`/g, '<code class="bg-slate-800 text-brand-300 px-1.5 py-0.5 rounded text-xs font-mono">$1</code>');

    // Headers
    escaped = escaped.replace(/^### (.*$)/gim, '<h3 class="text-base font-bold text-white mt-4 mb-2 pb-1 border-b border-slate-800">$1</h3>');
    escaped = escaped.replace(/^## (.*$)/gim, '<h2 class="text-lg font-bold text-brand-300 mt-5 mb-2 pb-1 border-b border-slate-800">$1</h2>');
    escaped = escaped.replace(/^# (.*$)/gim, '<h1 class="text-xl font-bold text-white mt-6 mb-3 pb-2 border-b border-slate-700">$1</h1>');

    // Bold & Italic
    escaped = escaped.replace(/\*\*([^*]+)\*\*/g, '<strong class="font-semibold text-white">$1</strong>');
    escaped = escaped.replace(/\*([^*]+)\*/g, '<em class="italic text-slate-300">$1</em>');

    // Bullet points
    escaped = escaped.replace(/^\s*-\s+(.*$)/gim, '<li class="ml-4 list-disc text-slate-300 my-1">$1</li>');
    escaped = escaped.replace(/(<li.*<\/li>)/gims, '<ul class="my-2 space-y-1">$1</ul>');

    // Paragraph line breaks
    escaped = escaped.replace(/\n\n+/g, '<br/><br/>');

    return escaped;
  }
}
