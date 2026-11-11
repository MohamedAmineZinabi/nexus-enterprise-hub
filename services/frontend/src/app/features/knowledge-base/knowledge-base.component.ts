import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-knowledge-base',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="space-y-6">
      <div class="flex items-center justify-between pb-6 border-b border-slate-800">
        <div>
          <div class="flex items-center gap-2 mb-1">
            <span class="text-xs font-mono text-brand-400 uppercase tracking-widest">Document Ingestion & RAG</span>
            <span class="inline-flex items-center rounded-md bg-brand-950 px-2 py-0.5 text-xs font-medium text-brand-300 ring-1 ring-inset ring-brand-800/40">
              Roadmap Day 12
            </span>
          </div>
          <h1 class="text-2xl sm:text-3xl font-bold tracking-tight text-white">
            Knowledge Base & Document Store
          </h1>
          <p class="text-sm text-slate-400 mt-1">
            Enterprise RAG repository for tenant: <span class="text-slate-200 font-semibold">{{ tenant()?.name }}</span>
          </p>
        </div>
      </div>

      <!-- Placeholder Feature Card -->
      <div class="bg-slate-900/60 border border-slate-800 rounded-2xl p-12 text-center backdrop-blur-sm">
        <div class="w-16 h-16 rounded-2xl bg-brand-600/10 border border-brand-500/20 text-brand-400 flex items-center justify-center mx-auto mb-4">
          <svg class="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
          </svg>
        </div>
        <h3 class="text-lg font-semibold text-white">Document Ingestion & Hybrid Search</h3>
        <p class="text-sm text-slate-400 max-w-md mx-auto mt-2">
          File upload drag-and-drop, chunking parameters (chunkSize, overlap), and vector collection management scheduled for Phase 5, Day 12.
        </p>
        <div class="mt-6 inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-slate-800/90 text-xs font-mono text-slate-300 border border-slate-700">
          <span class="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
          Backend Endpoint: POST /api/v1/documents (Ready & Tested)
        </div>
      </div>
    </div>
  `
})
export class KnowledgeBaseComponent {
  private readonly authService = inject(AuthService);
  readonly tenant = this.authService.currentTenant;
}

