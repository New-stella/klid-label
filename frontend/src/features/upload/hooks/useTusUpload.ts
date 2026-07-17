import { useCallback, useRef, useState } from 'react';

import {
  cancelUpload,
  uploadFile,
  type TusMetadata,
  type TusUploadResult,
} from '../api/tusClient';

export type TusStatus = 'idle' | 'uploading' | 'paused' | 'completed' | 'error';

export interface UseTusUploadState {
  status: TusStatus;
  /** 진행률 0~1. */
  progress: number;
  uploadedBytes: number;
  totalBytes: number;
  uploadId: string | null;
  error: string | null;
}

/**
 * TUS 업로드 훅 — 진행률 표시 + 일시정지/재개.
 *
 * - {@link start} 새 업로드 시작. 실패/일시정지 후 {@link resume} 로 HEAD offset 동기화 후 이어서.
 * - {@link pause} 다음 청크 경계에서 안전하게 멈춤(현재 offset 보존).
 * - {@link cancel} 세션 취소(BE 임시파일 삭제).
 */
export interface UseTusUploadOptions {
  /** TUS endpoint base (기본 관제 '/uploads'). 포털은 '/portal/uploads/tus'. */
  endpointBase?: string;
}

export function useTusUpload(options: UseTusUploadOptions = {}) {
  const { endpointBase } = options;
  const [state, setState] = useState<UseTusUploadState>({
    status: 'idle',
    progress: 0,
    uploadedBytes: 0,
    totalBytes: 0,
    uploadId: null,
    error: null,
  });
  const pauseRef = useRef(false);

  const run = useCallback(
    async (file: File, metadata: TusMetadata, resumeUploadId?: string) => {
      pauseRef.current = false;
      setState((s) => ({
        ...s,
        status: 'uploading',
        error: null,
        totalBytes: file.size,
        uploadId: resumeUploadId ?? s.uploadId,
      }));
      try {
        const result: TusUploadResult = await uploadFile({
          file,
          metadata,
          resumeUploadId,
          endpointBase,
          onProgress: (uploaded, total) =>
            setState((s) => ({
              ...s,
              uploadedBytes: uploaded,
              totalBytes: total,
              progress: total > 0 ? uploaded / total : 0,
            })),
          shouldPause: () => pauseRef.current,
        });
        setState((s) => ({
          ...s,
          uploadId: result.uploadId,
          status: result.completed ? 'completed' : 'paused',
          progress:
            result.uploadOffset > 0 && file.size > 0
              ? result.uploadOffset / file.size
              : s.progress,
        }));
        return result;
      } catch (err) {
        const message = extractMessage(err, '업로드 중 오류가 발생했습니다.');
        setState((s) => ({ ...s, status: 'error', error: message }));
        throw err;
      }
    },
    [endpointBase],
  );

  const start = useCallback(
    (file: File, metadata: TusMetadata) => run(file, metadata),
    [run],
  );

  const resume = useCallback(
    (file: File, metadata: TusMetadata) => {
      if (!state.uploadId) {
        return run(file, metadata);
      }
      return run(file, metadata, state.uploadId);
    },
    [run, state.uploadId],
  );

  const pause = useCallback(() => {
    pauseRef.current = true;
  }, []);

  const cancel = useCallback(async () => {
    pauseRef.current = true;
    if (state.uploadId) {
      try {
        await cancelUpload(state.uploadId, endpointBase);
      } catch {
        // best-effort — 세션은 BE TTL 정리 잡으로도 회수됨.
      }
    }
    setState({
      status: 'idle',
      progress: 0,
      uploadedBytes: 0,
      totalBytes: 0,
      uploadId: null,
      error: null,
    });
  }, [state.uploadId, endpointBase]);

  return { ...state, start, resume, pause, cancel };
}

/** BE/네트워크 에러에서 사용자 메시지 안전 추출 (XSS 방어는 JSX 렌더에 위임). */
function extractMessage(err: unknown, fallback: string): string {
  if (typeof err === 'object' && err !== null) {
    const e = err as { userMessage?: unknown; message?: unknown; response?: { data?: unknown } };
    // ApiError (lib/api/errors.ts) — userMessage/message 우선
    if (typeof e.userMessage === 'string' && e.userMessage.trim() !== '') return e.userMessage;
    const data = e.response?.data;
    if (typeof data === 'object' && data !== null && 'message' in data) {
      const m = (data as { message?: unknown }).message;
      if (typeof m === 'string' && m.trim() !== '') return m;
    }
    if (typeof e.message === 'string' && e.message.trim() !== '' && e.message !== 'ApiError') {
      return e.message;
    }
  }
  return fallback;
}
