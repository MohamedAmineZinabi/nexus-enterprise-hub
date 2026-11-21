import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpContext, HttpEvent, HttpEventType } from '@angular/common/http';
import { Observable, tap, catchError, map, finalize } from 'rxjs';
import { SILENT_HTTP_ERRORS } from '../interceptors/error.interceptor';
import {
  DocumentItem,
  DocumentUploadResponse,
  HybridSearchRequest,
  DocumentChunkResult,
  HybridSearchResponse
} from '../models/document.models';
import { ApiResponse } from '../models/auth.models';

@Injectable({
  providedIn: 'root'
})
export class DocumentService {
  private readonly http = inject(HttpClient);

  // Reactive State via Angular 18+ Signals
  readonly documents = signal<DocumentItem[]>([]);
  readonly isLoading = signal<boolean>(false);
  readonly isUploading = signal<boolean>(false);
  readonly uploadProgress = signal<number>(0);
  readonly searchResults = signal<DocumentChunkResult[]>([]);
  readonly isSearching = signal<boolean>(false);
  readonly lastSearchQuery = signal<string>('');

  /**
   * Fetches all tenant documents from backend and updates the `documents` signal.
   * `background` refreshes (status polling) skip the loading spinner and global error toasts.
   */
  loadDocuments(background: boolean = false): Observable<DocumentItem[]> {
    if (!background) {
      this.isLoading.set(true);
    }
    const context = new HttpContext().set(SILENT_HTTP_ERRORS, background);
    return this.http.get<ApiResponse<DocumentItem[]>>('/api/v1/documents', { context }).pipe(
      map(res => (res.data || []).map(doc => ({
        ...doc,
        documentId: doc.documentId || doc.id,
        fileName: doc.fileName || doc.filename,
        fileSizeBytes: doc.fileSizeBytes || doc.fileSize,
        chunksCreated: doc.chunksCreated ?? doc.chunksCount
      }))),
      tap(items => {
        this.documents.set(items);
      }),
      finalize(() => {
        if (!background) {
          this.isLoading.set(false);
        }
      })
    );
  }

  /**
   * Performs multipart file upload with chunking configuration and progress tracking.
   */
  uploadDocument(
    file: File,
    chunkSize: number = 500,
    chunkOverlap: number = 50,
    vectorCollection: string = 'enterprise_docs'
  ): Observable<DocumentUploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('chunkSize', chunkSize.toString());
    formData.append('chunkOverlap', chunkOverlap.toString());
    formData.append('vectorCollection', vectorCollection);

    this.isUploading.set(true);
    this.uploadProgress.set(0);

    return new Observable<DocumentUploadResponse>(observer => {
      this.http.post<ApiResponse<DocumentUploadResponse>>('/api/v1/documents/upload', formData, {
        reportProgress: true,
        observe: 'events'
      }).subscribe({
        next: (event: HttpEvent<ApiResponse<DocumentUploadResponse>>) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            const progress = Math.round((100 * event.loaded) / event.total);
            this.uploadProgress.set(progress);
          } else if (event.type === HttpEventType.Response) {
            const body = event.body;
            this.isUploading.set(false);
            this.uploadProgress.set(100);
            if (body && body.data) {
              // 202 Accepted: indexing continues asynchronously. Show the document
              // right away (typically INDEXING); the knowledge base polls for completion.
              this.upsertFromUpload(body.data, file);
              this.loadDocuments(true).subscribe({ error: () => undefined });
              observer.next(body.data);
              observer.complete();
            } else {
              observer.error(new Error('Invalid upload response payload'));
            }
          }
        },
        error: err => {
          this.isUploading.set(false);
          this.uploadProgress.set(0);
          observer.error(err);
        }
      });
    });
  }

  private upsertFromUpload(res: DocumentUploadResponse, file: File): void {
    const item: DocumentItem = {
      id: res.documentId,
      documentId: res.documentId,
      filename: res.fileName || file.name,
      fileName: res.fileName || file.name,
      fileSize: file.size,
      fileSizeBytes: file.size,
      vectorCollection: res.vectorCollection,
      status: res.status || 'INDEXING',
      chunksCount: res.chunksCreated ?? 0,
      chunksCreated: res.chunksCreated ?? 0,
      createdAt: res.createdAt || new Date().toISOString()
    };
    this.documents.update(list => [item, ...list.filter(d => d.id !== item.id)]);
  }

  /**
   * Deletes a document optimistically from state, then executes server-side purge.
   */
  deleteDocument(documentId: string): Observable<boolean> {
    const previous = this.documents();
    // Optimistic UI update
    this.documents.set(previous.filter(doc => doc.id !== documentId));

    return this.http.delete<ApiResponse<void>>(`/api/v1/documents/${documentId}`).pipe(
      map(() => true),
      catchError(err => {
        // Rollback on server failure
        this.documents.set(previous);
        throw err;
      })
    );
  }

  /**
   * Executes a hybrid search against the tenant's indexed vector space.
   */
  search(request: HybridSearchRequest): Observable<HybridSearchResponse> {
    this.isSearching.set(true);
    this.lastSearchQuery.set(request.query);

    return this.http.post<ApiResponse<HybridSearchResponse>>('/api/v1/documents/search', request).pipe(
      map(res => res.data),
      tap(data => {
        this.searchResults.set(data?.results || []);
      }),
      finalize(() => {
        this.isSearching.set(false);
      })
    );
  }

  /**
   * Clears current search results.
   */
  clearSearch(): void {
    this.searchResults.set([]);
    this.lastSearchQuery.set('');
  }
}

