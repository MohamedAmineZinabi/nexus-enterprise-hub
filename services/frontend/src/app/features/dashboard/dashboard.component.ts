import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

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

      <!-- Cluster Telemetry Metrics Cards -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <!-- Metric 1: Multi-Agent Nodes -->
        <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-5 backdrop-blur-sm">
          <div class="flex items-center justify-between text-xs text-slate-400 mb-2">
            <span>LangGraph Nodes</span>
            <span class="text-brand-400 font-mono text-[11px]">Multi-Agent</span>
          </div>
          <div class="text-2xl font-bold text-white tracking-tight">4 Nodes</div>
          <p class="text-xs text-slate-400 mt-1">Planner, Researcher, Synthesizer, Critic</p>
          <div class="mt-3 flex items-center gap-1.5 text-xs text-emerald-400">
            <span class="w-2 h-2 rounded-full bg-emerald-500"></span>
            Looping & Critique active
          </div>
        </div>

        <!-- Metric 2: RAG Pipeline -->
        <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-5 backdrop-blur-sm">
          <div class="flex items-center justify-between text-xs text-slate-400 mb-2">
            <span>Advanced RAG</span>
            <span class="text-brand-400 font-mono text-[11px]">Qdrant + gRPC</span>
          </div>
          <div class="text-2xl font-bold text-white tracking-tight">Hybrid Search</div>
          <p class="text-xs text-slate-400 mt-1">Dense + Sparse BM25 + Cross-Encoder</p>
          <div class="mt-3 flex items-center gap-1.5 text-xs text-emerald-400">
            <span class="w-2 h-2 rounded-full bg-emerald-500"></span>
            Port 50051 / 6333 Connected
          </div>
        </div>

        <!-- Metric 3: Distributed Locks -->
        <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-5 backdrop-blur-sm">
          <div class="flex items-center justify-between text-xs text-slate-400 mb-2">
            <span>Coordination</span>
            <span class="text-brand-400 font-mono text-[11px]">Redis Redlock</span>
          </div>
          <div class="text-2xl font-bold text-white tracking-tight">100% Idempotent</div>
          <p class="text-xs text-slate-400 mt-1">Checkpoints under agent:checkpoint</p>
          <div class="mt-3 flex items-center gap-1.5 text-xs text-emerald-400">
            <span class="w-2 h-2 rounded-full bg-emerald-500"></span>
            Zero redundant executions
          </div>
        </div>

        <!-- Metric 4: Real-time Telemetry -->
        <div class="bg-slate-900/70 border border-slate-800 rounded-xl p-5 backdrop-blur-sm">
          <div class="flex items-center justify-between text-xs text-slate-400 mb-2">
            <span>Event Streaming</span>
            <span class="text-brand-400 font-mono text-[11px]">Kafka KRaft</span>
          </div>
          <div class="text-2xl font-bold text-white tracking-tight">Real-Time SSE</div>
          <p class="text-xs text-slate-400 mt-1">agent.task.events & completed</p>
          <div class="mt-3 flex items-center gap-1.5 text-xs text-emerald-400">
            <span class="w-2 h-2 rounded-full bg-emerald-500"></span>
            Spring Boot SSE Bridge
          </div>
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
export class DashboardComponent {
  private readonly authService = inject(AuthService);
  readonly user = this.authService.currentUser;
  readonly tenant = this.authService.currentTenant;
}

