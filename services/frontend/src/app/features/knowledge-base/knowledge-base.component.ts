import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { DocumentService } from '../../core/services/document.service';
import { AuthService } from '../../core/services/auth.service';
import { DocumentItem, HybridSearchRequest } from '../../core/models/document.models';

interface ToastNotification {
  id: string;
  type: 'success' | 'error' | 'info';
  title: string;
  message: string;
}

@Component({
  selector: 'app-knowledge-base',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule],
  template: `
    <div class="space-y-6">
      <!-- Toast Notifications Container -->
      <div class="fixed top-20 right-6 z-50 space-y-2 max-w-md w-full pointer-events-none">
        @for (toast of toasts(); track toast.id) {
          <div
            class="pointer-events-auto flex items-start gap-3 p-4 rounded-xl border shadow-xl backdrop-blur-md transition-all duration-300 animate-slideIn"
            [ngClass]="{
              'bg-emerald-950/90 border-emerald-800/80 text-emerald-200': toast.type === 'success',
              'bg-red-950/90 border-red-800/80 text-red-200': toast.type === 'error',
              'bg-slate-900/90 border-slate-700/80 text-slate-200': toast.type === 'info'
            }"
          >
            @if (toast.type === 'success') {
              <svg class="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
              </svg>
            } @else if (toast.type === 'error') {
              <svg class="w-5 h-5 text-red-400 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
            } @else {
              <svg class="w-5 h-5 text-brand-400 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            }
            <div class="flex-1">
              <h4 class="text-xs font-bold tracking-wide uppercase">{{ toast.title }}</h4>
              <p class="text-xs mt-0.5 opacity-90">{{ toast.message }}</p>
            </div>
            <button
              type="button"
              (click)="removeToast(toast.id)"
              class="text-slate-400 hover:text-white"
            >
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        }
      </div>

      <!-- Delete Confirmation Modal -->
      @if (documentToDelete()) {
        <div class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fadeIn">
          <div class="bg-slate-900 border border-slate-800 rounded-2xl max-w-md w-full p-6 shadow-2xl space-y-4">
            <div class="flex items-center gap-3 text-red-400">
              <div class="w-10 h-10 rounded-xl bg-red-950/80 border border-red-800/60 flex items-center justify-center">
                <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
              </div>
              <h3 class="text-lg font-bold text-white">Purge Document</h3>
            </div>
            <p class="text-sm text-slate-300">
              Are you sure you want to permanently delete
              <span class="font-semibold text-white">"{{ documentToDelete()?.filename }}"</span>?
              This will remove the file from storage and purge all associated vector embeddings from Qdrant.
            </p>
            <div class="flex justify-end gap-3 pt-2">
              <button
                type="button"
                (click)="cancelDelete()"
                class="px-4 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-sm font-semibold text-slate-200 transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                (click)="confirmDelete()"
                class="px-4 py-2 rounded-lg bg-red-600 hover:bg-red-500 text-sm font-semibold text-white shadow-md shadow-red-600/30 transition-colors"
              >
                Delete & Purge
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Header & Quick Metrics -->
      <div class="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4 pb-6 border-b border-slate-800">
        <div>
          <div class="flex items-center gap-2 mb-1">
            <span class="text-xs font-mono text-brand-400 uppercase tracking-widest">Enterprise RAG Subsystem</span>
            <span class="inline-flex items-center rounded-md bg-emerald-950 px-2 py-0.5 text-xs font-medium text-emerald-400 ring-1 ring-inset ring-emerald-800/30">
              Qdrant + FastEmbed + Cross-Encoder
            </span>
          </div>
          <h1 class="text-2xl sm:text-3xl font-bold tracking-tight text-white">
            Knowledge Base & Ingestion Engine
          </h1>
          <p class="text-sm text-slate-400 mt-1">
            Isolated tenant repository for: <span class="text-slate-200 font-semibold">{{ tenant()?.name }}</span>
          </p>
        </div>

        <!-- Metric Badges -->
        <div class="flex flex-wrap items-center gap-3">
          <div class="bg-slate-900/80 border border-slate-800 rounded-lg px-3.5 py-1.5 flex items-center gap-2.5">
            <div class="w-2 h-2 rounded-full bg-brand-400"></div>
            <span class="text-xs text-slate-400 font-medium">Total Docs:</span>
            <span class="text-xs font-bold text-white font-mono">{{ totalDocuments() }}</span>
          </div>
          <div class="bg-slate-900/80 border border-slate-800 rounded-lg px-3.5 py-1.5 flex items-center gap-2.5">
            <div class="w-2 h-2 rounded-full bg-emerald-400"></div>
            <span class="text-xs text-slate-400 font-medium">Indexed Chunks:</span>
            <span class="text-xs font-bold text-white font-mono">{{ totalChunks() }}</span>
          </div>
          <button
            type="button"
            (click)="refreshDocuments()"
            [disabled]="documentService.isLoading()"
            title="Refresh documents list"
            class="p-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 hover:text-white border border-slate-700/80 transition-colors focus:outline-none"
          >
            <svg
              class="w-4 h-4"
              [class.animate-spin]="documentService.isLoading()"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
          </button>
        </div>
      </div>

      <!-- Main Navigation Tabs -->
      <div class="flex border-b border-slate-800">
        <button
          type="button"
          (click)="activeTab.set('repository')"
          class="px-5 py-3 text-sm font-semibold border-b-2 transition-all flex items-center gap-2"
          [ngClass]="activeTab() === 'repository'
            ? 'border-brand-500 text-brand-300 bg-brand-500/10'
            : 'border-transparent text-slate-400 hover:text-slate-200 hover:border-slate-700'"
        >
          <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7v8a2 2 0 002 2h6M8 7V5a2 2 0 012-2h4.586a1 1 0 01.707.293l4.414 4.414a1 1 0 01.293.707V15a2 2 0 01-2 2h-2M8 7H6a2 2 0 00-2 2v10a2 2 0 002 2h8a2 2 0 002-2v-2" />
          </svg>
          Documents & Ingestion
          <span class="text-[10px] font-mono px-1.5 py-0.2 rounded-full bg-slate-800 text-slate-300">
            {{ totalDocuments() }}
          </span>
        </button>

        <button
          type="button"
          (click)="activeTab.set('search')"
          class="px-5 py-3 text-sm font-semibold border-b-2 transition-all flex items-center gap-2"
          [ngClass]="activeTab() === 'search'
            ? 'border-brand-500 text-brand-300 bg-brand-500/10'
            : 'border-transparent text-slate-400 hover:text-slate-200 hover:border-slate-700'"
        >
          <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
          Hybrid Search Playground
          @if (documentService.searchResults().length > 0) {
            <span class="text-[10px] font-mono px-1.5 py-0.2 rounded-full bg-brand-900 text-brand-200">
              {{ documentService.searchResults().length }} results
            </span>
          }
        </button>
      </div>

      <!-- TAB 1: REPOSITORY & INGESTION -->
      @if (activeTab() === 'repository') {
        <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">

          <!-- Section A: Drag & Drop Dropzone (1 Column) -->
          <div class="lg:col-span-1 space-y-4">
            <div class="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 backdrop-blur-sm">
              <div class="flex items-center justify-between mb-3">
                <h3 class="text-sm font-bold tracking-wide uppercase text-slate-200 flex items-center gap-2">
                  <svg class="w-4 h-4 text-brand-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
                  </svg>
                  Document Ingestion
                </h3>
                <span class="text-[10px] font-mono text-slate-400">Max 25MB</span>
              </div>

              <!-- Drag and Drop Dropzone -->
              <div
                (dragover)="onDragOver($event)"
                (dragleave)="onDragLeave($event)"
                (drop)="onDrop($event)"
                (click)="fileInput.click()"
                class="border-2 border-dashed rounded-xl p-6 text-center cursor-pointer transition-all duration-200 relative group"
                [ngClass]="isDragging()
                  ? 'border-brand-400 bg-brand-500/15 ring-2 ring-brand-400/30'
                  : 'border-slate-700/80 hover:border-brand-500/60 bg-slate-950/50 hover:bg-slate-950/80'"
              >
                <input
                  #fileInput
                  type="file"
                  (change)="onFileSelected($event)"
                  accept=".pdf,.txt,.md,.docx"
                  class="hidden"
                />

                <div class="w-12 h-12 rounded-xl bg-slate-800/80 border border-slate-700/80 flex items-center justify-center mx-auto mb-3 group-hover:scale-105 transition-transform">
                  <svg class="w-6 h-6 text-brand-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                  </svg>
                </div>

                @if (selectedFile()) {
                  <div class="space-y-1">
                    <p class="text-xs font-semibold text-brand-300 truncate max-w-xs mx-auto">
                      {{ selectedFile()?.name }}
                    </p>
                    <p class="text-[11px] font-mono text-slate-400">
                      {{ formatFileSize(selectedFile()?.size || 0) }}
                    </p>
                  </div>
                } @else {
                  <p class="text-xs font-medium text-slate-300">
                    <span class="text-brand-400 font-semibold underline underline-offset-2">Click to browse</span>
                    or drop files here
                  </p>
                  <p class="text-[10px] text-slate-400 mt-1">Supports PDF, Markdown, TXT, DOCX</p>
                }
              </div>

              <!-- Advanced Chunking Settings Accordion -->
              <div class="mt-4 pt-4 border-t border-slate-800">
                <button
                  type="button"
                  (click)="toggleAdvancedSettings()"
                  class="flex items-center justify-between w-full text-xs font-medium text-slate-400 hover:text-slate-200"
                >
                  <span class="flex items-center gap-1.5">
                    <svg class="w-3.5 h-3.5 text-brand-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                    Advanced Chunking Parameters
                  </span>
                  <svg
                    class="w-3.5 h-3.5 transition-transform"
                    [class.rotate-180]="showAdvancedSettings()"
                    fill="none" viewBox="0 0 24 24" stroke="currentColor"
                  >
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
                  </svg>
                </button>

                @if (showAdvancedSettings()) {
                  <div class="mt-3 space-y-3 animate-fadeIn">
                    <div>
                      <div class="flex items-center justify-between text-xs text-slate-300 mb-1">
                        <span>Chunk Size (chars)</span>
                        <span class="font-mono text-brand-300 font-semibold">{{ uploadChunkSize() }}</span>
                      </div>
                      <input
                        type="range"
                        min="100"
                        max="2000"
                        step="50"
                        [ngModel]="uploadChunkSize()"
                        (ngModelChange)="setChunkSize($event)"
                        class="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-brand-500"
                      />
                    </div>

                    <div>
                      <div class="flex items-center justify-between text-xs text-slate-300 mb-1">
                        <span>Chunk Overlap (chars)</span>
                        <span class="font-mono text-brand-300 font-semibold">{{ uploadChunkOverlap() }}</span>
                      </div>
                      <input
                        type="range"
                        min="0"
                        max="300"
                        step="10"
                        [ngModel]="uploadChunkOverlap()"
                        (ngModelChange)="setChunkOverlap($event)"
                        class="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-brand-500"
                      />
                    </div>

                    <div>
                      <label class="block text-xs text-slate-300 mb-1">Target Vector Collection</label>
                      <input
                        type="text"
                        [(ngModel)]="uploadCollection"
                        class="w-full rounded bg-slate-950 border border-slate-700/80 px-2.5 py-1.5 text-xs text-slate-200 font-mono focus:outline-none focus:ring-1 focus:ring-brand-500"
                      />
                    </div>
                  </div>
                }
              </div>

              <!-- Upload Progress Indicator -->
              @if (documentService.isUploading()) {
                <div class="mt-4 space-y-1.5 animate-fadeIn">
                  <div class="flex items-center justify-between text-xs">
                    <span class="text-slate-300 font-medium flex items-center gap-2">
                      <svg class="w-3.5 h-3.5 text-brand-400 animate-spin" fill="none" viewBox="0 0 24 24">
                        <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                        <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                      </svg>
                      Ingesting & Embedding...
                    </span>
                    <span class="font-mono text-brand-400 font-semibold">{{ documentService.uploadProgress() }}%</span>
                  </div>
                  <div class="w-full bg-slate-800 rounded-full h-2 overflow-hidden">
                    <div
                      class="bg-gradient-to-r from-brand-600 to-indigo-500 h-2 rounded-full transition-all duration-300"
                      [style.width.%]="documentService.uploadProgress()"
                    ></div>
                  </div>
                </div>
              }

              <!-- Upload Submit Button -->
              <div class="mt-4">
                <button
                  type="button"
                  (click)="startUpload()"
                  [disabled]="!selectedFile() || documentService.isUploading()"
                  class="w-full py-2.5 px-4 rounded-xl text-xs font-semibold text-white bg-gradient-to-r from-brand-600 to-indigo-600 hover:from-brand-500 hover:to-indigo-500 shadow-md shadow-brand-600/30 disabled:opacity-50 disabled:cursor-not-allowed transition-all flex items-center justify-center gap-2"
                >
                  <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                  </svg>
                  Ingest & Index Document
                </button>
              </div>
            </div>
          </div>

          <!-- Section B: Enterprise Document Repository Table (2 Columns) -->
          <div class="lg:col-span-2">
            <div class="bg-slate-900/80 border border-slate-800 rounded-2xl overflow-hidden backdrop-blur-sm">
              <div class="px-5 py-4 border-b border-slate-800 flex items-center justify-between">
                <div>
                  <h3 class="text-sm font-bold tracking-wide uppercase text-slate-200">
                    Tenant Document Repository
                  </h3>
                  <p class="text-xs text-slate-400 mt-0.5">Vector embeddings partitioned by tenant context</p>
                </div>
                <span class="text-xs font-mono text-slate-400">
                  {{ documentService.documents().length }} files
                </span>
              </div>

              <!-- Table or Empty State -->
              @if (documentService.documents().length === 0) {
                <div class="p-12 text-center space-y-3">
                  <div class="w-14 h-14 rounded-2xl bg-slate-800/80 border border-slate-700/80 flex items-center justify-center mx-auto text-slate-400">
                    <svg class="w-7 h-7" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                    </svg>
                  </div>
                  <h4 class="text-sm font-semibold text-slate-200">No documents ingested yet</h4>
                  <p class="text-xs text-slate-400 max-w-sm mx-auto">
                    Upload PDF, Markdown, or text documentation to populate the RAG vector index.
                  </p>
                </div>
              } @else {
                <div class="overflow-x-auto">
                  <table class="w-full text-left text-xs">
                    <thead class="bg-slate-950/60 text-slate-400 uppercase tracking-wider font-mono text-[10px] border-b border-slate-800">
                      <tr>
                        <th class="px-4 py-3">Document</th>
                        <th class="px-4 py-3">Size</th>
                        <th class="px-4 py-3">Status</th>
                        <th class="px-4 py-3">Chunks</th>
                        <th class="px-4 py-3">Ingested At</th>
                        <th class="px-4 py-3 text-right">Actions</th>
                      </tr>
                    </thead>
                    <tbody class="divide-y divide-slate-800/70">
                      @for (doc of documentService.documents(); track doc.id) {
                        <tr class="hover:bg-slate-800/40 transition-colors group">
                          <!-- Document Name & Icon -->
                          <td class="px-4 py-3.5 font-medium text-slate-200">
                            <div class="flex items-center gap-2.5">
                              <span class="p-1.5 rounded-lg bg-slate-800 border border-slate-700/60 text-brand-400">
                                <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                                </svg>
                              </span>
                              <div class="truncate max-w-[200px]" [title]="doc.filename">
                                <div class="font-semibold text-slate-100 truncate">{{ doc.filename }}</div>
                                <div class="text-[10px] font-mono text-slate-500 truncate">{{ doc.vectorCollection }}</div>
                              </div>
                            </div>
                          </td>

                          <!-- Size -->
                          <td class="px-4 py-3.5 font-mono text-slate-300">
                            {{ formatFileSize(doc.fileSize) }}
                          </td>

                          <!-- Status Badge -->
                          <td class="px-4 py-3.5">
                            @switch (doc.status) {
                              @case ('INDEXED') {
                                <span class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-950/80 text-emerald-300 border border-emerald-800/60">
                                  <span class="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
                                  INDEXED
                                </span>
                              }
                              @case ('UPLOADED_PENDING_INDEX') {
                                <span class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-amber-950/80 text-amber-300 border border-amber-800/60">
                                  <span class="w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse"></span>
                                  PENDING
                                </span>
                              }
                              @case ('FAILED') {
                                <span class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-red-950/80 text-red-300 border border-red-800/60">
                                  <span class="w-1.5 h-1.5 rounded-full bg-red-400"></span>
                                  FAILED
                                </span>
                              }
                              @default {
                                <span class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-blue-950/80 text-blue-300 border border-blue-800/60">
                                  {{ doc.status }}
                                </span>
                              }
                            }
                          </td>

                          <!-- Chunks -->
                          <td class="px-4 py-3.5 font-mono text-slate-300">
                            <span class="px-2 py-0.5 rounded bg-slate-950/60 border border-slate-800">
                              {{ doc.chunksCount }} chunks
                            </span>
                          </td>

                          <!-- Created At -->
                          <td class="px-4 py-3.5 text-slate-400 font-mono text-[11px]">
                            {{ formatDate(doc.createdAt) }}
                          </td>

                          <!-- Actions -->
                          <td class="px-4 py-3.5 text-right">
                            <button
                              type="button"
                              (click)="promptDelete(doc)"
                              title="Delete document and purge vectors"
                              class="p-1.5 rounded-lg text-slate-400 hover:text-red-400 hover:bg-red-950/40 transition-colors"
                            >
                              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                              </svg>
                            </button>
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </div>
          </div>
        </div>
      }

      <!-- TAB 2: REAL-TIME HYBRID SEARCH PLAYGROUND -->
      @if (activeTab() === 'search') {
        <div class="space-y-6">
          <!-- Search Control Box -->
          <div class="bg-slate-900/90 border border-slate-800 rounded-2xl p-6 backdrop-blur-sm shadow-xl space-y-5">
            <!-- Search Query Bar -->
            <div>
              <div class="relative">
                <input
                  type="text"
                  [(ngModel)]="searchQuery"
                  (keydown.enter)="executeSearch()"
                  placeholder="Enter hybrid search query (e.g. 'compliance policy violations and sanctions auditing')..."
                  class="w-full rounded-xl bg-slate-950 border border-slate-700/80 pl-11 pr-28 py-3 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-brand-500 transition-all font-medium"
                />
                <svg class="w-5 h-5 text-slate-400 absolute left-3.5 top-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>

                <!-- Search Button -->
                <button
                  type="button"
                  (click)="executeSearch()"
                  [disabled]="!searchQuery.trim() || documentService.isSearching()"
                  class="absolute right-2 top-2 px-4 py-1.5 rounded-lg text-xs font-semibold text-white bg-brand-600 hover:bg-brand-500 disabled:opacity-50 transition-colors flex items-center gap-1.5 shadow-sm shadow-brand-600/30"
                >
                  @if (documentService.isSearching()) {
                    <svg class="animate-spin h-3.5 w-3.5 text-white" fill="none" viewBox="0 0 24 24">
                      <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                      <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                    </svg>
                    Searching...
                  } @else {
                    <span>Search</span>
                    <kbd class="hidden sm:inline-block px-1.5 py-0.5 text-[9px] font-mono bg-brand-800 rounded">↵</kbd>
                  }
                </button>
              </div>
            </div>

            <!-- Toolbar: Alpha Slider, Top-K, and Cross-Encoder Toggle -->
            <div class="grid grid-cols-1 md:grid-cols-3 gap-6 pt-2 border-t border-slate-800/80">

              <!-- Alpha Hybrid Ratio Slider -->
              <div>
                <div class="flex items-center justify-between text-xs mb-1.5">
                  <span class="text-slate-300 font-medium">Hybrid Balance (&alpha;)</span>
                  <span
                    class="font-mono text-xs px-2 py-0.5 rounded font-bold"
                    [ngClass]="{
                      'bg-amber-950 text-amber-300 border border-amber-800/60': searchAlpha <= 0.3,
                      'bg-brand-950 text-brand-300 border border-brand-800/60': searchAlpha > 0.3 && searchAlpha < 0.7,
                      'bg-emerald-950 text-emerald-300 border border-emerald-800/60': searchAlpha >= 0.7
                    }"
                  >
                    &alpha; = {{ searchAlpha.toFixed(2) }}
                  </span>
                </div>
                <input
                  type="range"
                  min="0.0"
                  max="1.0"
                  step="0.05"
                  [(ngModel)]="searchAlpha"
                  class="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-brand-500"
                />
                <div class="flex justify-between text-[10px] text-slate-400 mt-1 font-mono">
                  <span>0.0 (BM25 Keyword)</span>
                  <span>0.5 (Balanced)</span>
                  <span>1.0 (Dense Semantic)</span>
                </div>
              </div>

              <!-- Top-K Selector -->
              <div>
                <label class="block text-xs text-slate-300 font-medium mb-1.5">Top-K Retrieved Chunks</label>
                <div class="flex gap-2">
                  @for (k of [3, 5, 10]; track k) {
                    <button
                      type="button"
                      (click)="searchTopK = k"
                      class="flex-1 py-1.5 rounded-lg text-xs font-mono font-semibold transition-all border"
                      [ngClass]="searchTopK === k
                        ? 'bg-brand-600 border-brand-500 text-white shadow-sm'
                        : 'bg-slate-950 border-slate-700/80 text-slate-400 hover:text-white hover:bg-slate-800'"
                    >
                      Top {{ k }}
                    </button>
                  }
                </div>
                <p class="text-[10px] text-slate-400 mt-1.5">Max chunks returned from Qdrant vector space</p>
              </div>

              <!-- Cross-Encoder Reranking Toggle -->
              <div>
                <label class="block text-xs text-slate-300 font-medium mb-1.5">Cross-Encoder Reranking</label>
                <div class="flex items-center gap-3">
                  <button
                    type="button"
                    (click)="searchRerank = !searchRerank"
                    class="relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none"
                    [ngClass]="searchRerank ? 'bg-brand-600' : 'bg-slate-800'"
                  >
                    <span
                      class="pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out"
                      [ngClass]="searchRerank ? 'translate-x-5' : 'translate-x-0'"
                    ></span>
                  </button>
                  <span class="text-xs font-semibold" [ngClass]="searchRerank ? 'text-emerald-400' : 'text-slate-400'">
                    {{ searchRerank ? 'Active (ms-marco-MiniLM)' : 'Disabled' }}
                  </span>
                </div>
                <p class="text-[10px] text-slate-400 mt-1.5">Deep cross-attention re-scoring on top candidate passages</p>
              </div>

            </div>
          </div>

          <!-- Search Results Section -->
          <div class="space-y-4">
            <div class="flex items-center justify-between">
              <h3 class="text-sm font-bold tracking-wide uppercase text-slate-200 flex items-center gap-2">
                <span>Ranked Context Passages</span>
                @if (documentService.searchResults().length > 0) {
                  <span class="text-xs font-mono font-normal text-slate-400">
                    ({{ documentService.searchResults().length }} chunks found)
                  </span>
                }
              </h3>

              @if (documentService.searchResults().length > 0) {
                <button
                  type="button"
                  (click)="documentService.clearSearch()"
                  class="text-xs text-slate-400 hover:text-slate-200 transition-colors"
                >
                  Clear Results
                </button>
              }
            </div>

            <!-- Search Loading State -->
            @if (documentService.isSearching()) {
              <div class="bg-slate-900/60 border border-slate-800 rounded-2xl p-8 text-center space-y-3">
                <svg class="animate-spin h-8 w-8 text-brand-400 mx-auto" fill="none" viewBox="0 0 24 24">
                  <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                  <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                </svg>
                <p class="text-xs text-slate-300 font-mono">Running Qdrant hybrid vector scoring & Cross-Encoder inference...</p>
              </div>
            } @else if (documentService.searchResults().length === 0 && documentService.lastSearchQuery()) {
              <!-- No Results State -->
              <div class="bg-slate-900/60 border border-slate-800 rounded-2xl p-10 text-center space-y-2">
                <p class="text-sm font-semibold text-slate-300">No matching chunks discovered</p>
                <p class="text-xs text-slate-400">
                  Try adjusting the alpha ratio or expanding query terms for prompt:
                  <span class="font-mono text-brand-300">"{{ documentService.lastSearchQuery() }}"</span>
                </p>
              </div>
            } @else if (documentService.searchResults().length === 0) {
              <!-- Initial Empty Playground State -->
              <div class="bg-slate-900/40 border border-dashed border-slate-800 rounded-2xl p-10 text-center space-y-2">
                <svg class="w-8 h-8 text-slate-600 mx-auto" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
                <p class="text-xs text-slate-400">Enter a query above to test hybrid retrieval against indexed embeddings.</p>
              </div>
            } @else {
              <!-- Result Cards List -->
              <div class="space-y-3">
                @for (chunk of documentService.searchResults(); track chunk.chunkId; let idx = $index) {
                  <div class="bg-slate-900/80 border border-slate-800 hover:border-slate-700/80 rounded-xl p-5 backdrop-blur-sm shadow-md transition-all space-y-3 group">
                    <!-- Top Bar: Rank & Scores -->
                    <div class="flex items-center justify-between">
                      <div class="flex items-center gap-2">
                        <span class="w-6 h-6 rounded-lg bg-brand-950 text-brand-300 border border-brand-800/80 flex items-center justify-center text-xs font-mono font-bold">
                          #{{ idx + 1 }}
                        </span>
                        <span class="text-xs font-mono font-medium text-slate-300">
                          {{ chunk.citation || 'Citation unavailable' }}
                        </span>
                      </div>

                      <div class="flex items-center gap-2">
                        <!-- Cross-Encoder Rerank Score -->
                        @if (chunk.rerankScore !== undefined && chunk.rerankScore !== null) {
                          <span class="text-[11px] font-mono px-2 py-0.5 rounded bg-emerald-950 text-emerald-300 border border-emerald-800/60 font-semibold flex items-center gap-1">
                            <span class="text-[9px] uppercase font-bold text-emerald-400">Rerank:</span>
                            {{ chunk.rerankScore.toFixed(4) }}
                          </span>
                        }
                        <!-- Hybrid Vector Score -->
                        <span class="text-[11px] font-mono px-2 py-0.5 rounded bg-slate-950 text-slate-300 border border-slate-800 font-semibold flex items-center gap-1">
                          <span class="text-[9px] uppercase font-bold text-slate-400">Score:</span>
                          {{ chunk.score.toFixed(4) }}
                        </span>
                      </div>
                    </div>

                    <!-- Chunk Content Preview -->
                    <div class="bg-slate-950/70 border border-slate-800/80 rounded-lg p-3 text-xs text-slate-200 leading-relaxed font-sans whitespace-pre-wrap font-normal">
                      {{ chunk.content }}
                    </div>

                    <!-- Chunk Metadata Footer -->
                    <div class="flex flex-wrap items-center justify-between text-[10px] font-mono text-slate-400 pt-1 border-t border-slate-800/50">
                      <span>Chunk ID: <span class="text-slate-300">{{ chunk.chunkId }}</span></span>
                      <span>Document ID: <span class="text-slate-300">{{ chunk.documentId }}</span></span>
                    </div>
                  </div>
                }
              </div>
            }
          </div>
        </div>
      }
    </div>
  `
})
export class KnowledgeBaseComponent implements OnInit {
  readonly documentService = inject(DocumentService);
  private readonly authService = inject(AuthService);

  readonly tenant = this.authService.currentTenant;

  // View state
  readonly activeTab = signal<'repository' | 'search'>('repository');
  readonly isDragging = signal<boolean>(false);
  readonly selectedFile = signal<File | null>(null);
  readonly showAdvancedSettings = signal<boolean>(false);
  readonly documentToDelete = signal<DocumentItem | null>(null);
  readonly toasts = signal<ToastNotification[]>([]);

  // Ingestion settings
  readonly uploadChunkSize = signal<number>(500);
  readonly uploadChunkOverlap = signal<number>(50);
  uploadCollection: string = 'enterprise_docs';

  // Hybrid search playground inputs
  searchQuery: string = '';
  searchAlpha: number = 0.7;
  searchTopK: number = 5;
  searchRerank: boolean = true;

  // Computed metrics
  readonly totalDocuments = computed(() => this.documentService.documents().length);
  readonly totalChunks = computed(() =>
    this.documentService.documents().reduce((sum, d) => sum + (d.chunksCount || 0), 0)
  );

  ngOnInit(): void {
    this.loadInitialDocuments();
  }

  loadInitialDocuments(): void {
    this.documentService.loadDocuments().subscribe({
      error: (err) => {
        this.addToast('error', 'Failed to load documents', err.message || 'Server error');
      }
    });
  }

  refreshDocuments(): void {
    this.documentService.loadDocuments().subscribe({
      next: () => {
        this.addToast('info', 'Refreshed', 'Tenant document list updated');
      },
      error: (err) => {
        this.addToast('error', 'Refresh Failed', err.message || 'Could not fetch documents');
      }
    });
  }

  toggleAdvancedSettings(): void {
    this.showAdvancedSettings.update(v => !v);
  }

  setChunkSize(val: any): void {
    this.uploadChunkSize.set(Number(val));
  }

  setChunkOverlap(val: any): void {
    this.uploadChunkOverlap.set(Number(val));
  }

  // --- Drag and Drop Handlers ---
  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(false);

    if (event.dataTransfer && event.dataTransfer.files.length > 0) {
      this.handleFile(event.dataTransfer.files[0]);
    }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.handleFile(input.files[0]);
    }
  }

  private handleFile(file: File): void {
    const allowed = ['.pdf', '.txt', '.md', '.docx'];
    const ext = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
    if (!allowed.includes(ext)) {
      this.addToast('error', 'Unsupported File Type', 'Allowed file formats: .pdf, .txt, .md, .docx');
      return;
    }

    if (file.size > 25 * 1024 * 1024) {
      this.addToast('error', 'File Too Large', 'Maximum file size allowed is 25MB.');
      return;
    }

    this.selectedFile.set(file);
  }

  startUpload(): void {
    const file = this.selectedFile();
    if (!file) return;

    this.documentService.uploadDocument(
      file,
      this.uploadChunkSize(),
      this.uploadChunkOverlap(),
      this.uploadCollection
    ).subscribe({
      next: (res) => {
        this.selectedFile.set(null);
        this.addToast(
          'success',
          'Document Ingested Successfully',
          `Created ${res.chunksCreated} chunks in vector collection '${res.vectorCollection}'.`
        );
      },
      error: (err) => {
        const msg = err.error?.message || err.message || 'Upload failed';
        this.addToast('error', 'Ingestion Failed', msg);
      }
    });
  }

  // --- Delete Handlers ---
  promptDelete(doc: DocumentItem): void {
    this.documentToDelete.set(doc);
  }

  cancelDelete(): void {
    this.documentToDelete.set(null);
  }

  confirmDelete(): void {
    const doc = this.documentToDelete();
    if (!doc) return;

    this.documentService.deleteDocument(doc.id).subscribe({
      next: () => {
        this.documentToDelete.set(null);
        this.addToast('success', 'Document Purged', `Deleted '${doc.filename}' and removed associated vectors.`);
      },
      error: (err) => {
        this.documentToDelete.set(null);
        this.addToast('error', 'Delete Failed', err.message || 'Failed to purge document');
      }
    });
  }

  // --- Search Handlers ---
  executeSearch(): void {
    const query = this.searchQuery.trim();
    if (!query) return;

    const request: HybridSearchRequest = {
      query,
      topK: this.searchTopK,
      alpha: this.searchAlpha,
      rerank: this.searchRerank,
      collectionName: this.uploadCollection
    };

    this.documentService.search(request).subscribe({
      next: (res) => {
        if (!res.results || res.results.length === 0) {
          this.addToast('info', 'No Chunks Discovered', 'Try refining your query or lowering alpha.');
        }
      },
      error: (err) => {
        const msg = err.error?.message || err.message || 'Search execution failed';
        this.addToast('error', 'Search Error', msg);
      }
    });
  }

  // --- Helpers ---
  formatFileSize(bytes: number): string {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  formatDate(dateStr: string): string {
    if (!dateStr) return 'N/A';
    try {
      const d = new Date(dateStr);
      return d.toLocaleDateString(undefined, {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      });
    } catch {
      return dateStr;
    }
  }

  addToast(type: 'success' | 'error' | 'info', title: string, message: string): void {
    const id = Math.random().toString(36).substring(2, 9);
    const newToast: ToastNotification = { id, type, title, message };
    this.toasts.update(list => [...list, newToast]);

    setTimeout(() => {
      this.removeToast(id);
    }, 5000);
  }

  removeToast(id: string): void {
    this.toasts.update(list => list.filter(t => t.id !== id));
  }
}
