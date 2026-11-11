import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-agent-studio',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="space-y-6">
      <div class="flex items-center justify-between pb-6 border-b border-slate-800">
        <div>
          <div class="flex items-center gap-2 mb-1">
            <span class="text-xs font-mono text-brand-400 uppercase tracking-widest">Multi-Agent Workflow Studio</span>
            <span class="inline-flex items-center rounded-md bg-brand-950 px-2 py-0.5 text-xs font-medium text-brand-300 ring-1 ring-inset ring-brand-800/40">
              Roadmap Day 13
            </span>
          </div>
          <h1 class="text-2xl sm:text-3xl font-bold tracking-tight text-white">
            Agent Studio & SSE Stream Viewer
          </h1>
          <p class="text-sm text-slate-400 mt-1">
            Autonomous multi-agent orchestration for tenant: <span class="text-slate-200 font-semibold">{{ tenant()?.name }}</span>
          </p>
        </div>
      </div>

      <!-- Placeholder Feature Card -->
      <div class="bg-slate-900/60 border border-slate-800 rounded-2xl p-12 text-center backdrop-blur-sm">
        <div class="w-16 h-16 rounded-2xl bg-brand-600/10 border border-brand-500/20 text-brand-400 flex items-center justify-center mx-auto mb-4">
          <svg class="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
        </div>
        <h3 class="text-lg font-semibold text-white">Real-Time Multi-Agent Studio</h3>
        <p class="text-sm text-slate-400 max-w-md mx-auto mt-2">
          LangGraph node visualization, real-time Server-Sent Events (SSE) telemetry streaming, and critique report generation scheduled for Phase 5, Day 13.
        </p>
        <div class="mt-6 inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-slate-800/90 text-xs font-mono text-slate-300 border border-slate-700">
          <span class="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
          Backend Stream: GET /api/v1/tasks/{{ '{taskId}' }}/stream (Ready & Tested)
        </div>
      </div>
    </div>
  `
})
export class AgentStudioComponent {
  private readonly authService = inject(AuthService);
  readonly tenant = this.authService.currentTenant;
}

