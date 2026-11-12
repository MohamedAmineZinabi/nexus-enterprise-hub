export type DocumentStatus = 'UPLOADED' | 'UPLOADED_PENDING_INDEX' | 'INDEXED' | 'FAILED';

export interface DocumentItem {
  id: string;
  documentId?: string;
  filename: string;
  fileName?: string;
  fileSize: number;
  fileSizeBytes?: number;
  mimeType?: string;
  vectorCollection: string;
  status: DocumentStatus;
  chunksCount: number;
  chunksCreated?: number;
  createdAt: string;
  updatedAt?: string;
}

export interface DocumentUploadResponse {
  documentId: string;
  fileName: string;
  status: DocumentStatus;
  chunksCreated: number;
  vectorCollection: string;
  createdAt: string;
  message: string;
}

export interface HybridSearchRequest {
  query: string;
  topK?: number;
  alpha?: number;
  rerank?: boolean;
  collectionName?: string;
  metadataFilters?: Record<string, string>;
}

export interface DocumentChunkResult {
  chunkId: string;
  documentId: string;
  content: string;
  score: number;
  rerankScore: number;
  metadata?: Record<string, string>;
  citation?: string;
}

export interface HybridSearchResponse {
  query: string;
  totalFound: number;
  results: DocumentChunkResult[];
}
