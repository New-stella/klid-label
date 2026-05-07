// tus-js-client 래퍼 (V1.5)
//
// 보안:
// - Bearer 토큰 자동 주입 (Authorization 헤더)
// - upload-metadata filename은 sanitize (path traversal 차단)
// - endpoint는 환경변수 기반 BE만 (Open Redirect 방어)
// - chunkSize 8MB (메모리 폭발 방지)
//
// IDOR: TUS fileId(=URL) 형식 `{userId}~{uuid}`은 BE에서 발급 — FE는 BE 응답 그대로 사용.

import { Upload } from 'tus-js-client';

import { useAuthStore } from '@/stores/useAuthStore';

import type { TusUploadProgress } from '../types';

import { sanitizeFilename } from './validateUpload';

const TUS_ENDPOINT = '/api/v1/portal/uploads';
const TUS_CHUNK_SIZE = 8 * 1024 * 1024; // 8MB
const TUS_RETRY_DELAYS = [0, 3000, 5000, 10000]; // ms

export interface TusUploaderOptions {
  file: File;
  onProgress?: (p: TusUploadProgress) => void;
  onSuccess?: () => void;
  onError?: (err: Error) => void;
}

/**
 * tus-js-client 래퍼 — 5GB 영상 재개 가능 업로드.
 *
 * pause/resume/cancel 메서드로 동일 인스턴스 재사용.
 */
export class TusUploader {
  private upload: Upload;
  private aborted = false;

  constructor(opts: TusUploaderOptions) {
    const { file, onProgress, onSuccess, onError } = opts;

    const token = useAuthStore.getState().token;
    const headers: Record<string, string> = {};
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }

    this.upload = new Upload(file, {
      endpoint: TUS_ENDPOINT,
      chunkSize: TUS_CHUNK_SIZE,
      retryDelays: TUS_RETRY_DELAYS,
      headers,
      metadata: {
        filename: sanitizeFilename(file.name),
        filetype: file.type,
      },
      onProgress: (bytesUploaded, bytesTotal) => {
        const percent = bytesTotal > 0 ? Math.round((bytesUploaded / bytesTotal) * 100) : 0;
        onProgress?.({ bytesUploaded, bytesTotal, percent });
      },
      onSuccess: () => {
        onSuccess?.();
      },
      onError: (err) => {
        onError?.(err);
      },
    });
  }

  start(): void {
    this.aborted = false;
    this.upload.start();
  }

  /** 일시정지 — 서버 측 업로드 데이터 유지 (terminate=false) */
  async pause(): Promise<void> {
    await this.upload.abort(false);
  }

  /** 재개 — start()를 다시 호출하면 tus 프로토콜이 offset부터 이어받음 */
  resume(): void {
    this.upload.start();
  }

  /** 취소 — 서버 측 업로드 데이터까지 삭제 (terminate=true) */
  async cancel(): Promise<void> {
    this.aborted = true;
    await this.upload.abort(true);
  }

  get isAborted(): boolean {
    return this.aborted;
  }

  get url(): string | null {
    return this.upload.url;
  }
}
