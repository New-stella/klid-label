import { useCallback, useRef, useState } from 'react';

import {
  cancelUpload,
  uploadFile,
  type InternalUploadCreatePayload,
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
  /**
   * 인입 대기 상태(BE `X-Ingest-Status`) — 내부 업로드에서만 내려온다.
   * 업로드 완료 != 적재이므로 화면은 이 값으로 "인입 대기"를 알린다.
   */
  ingestStatus: string | null;
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
    ingestStatus: null,
  });
  const pauseRef = useRef(false);

  const run = useCallback(
    async (
      file: File,
      metadata: TusMetadata,
      resumeUploadId?: string,
      createPayload?: InternalUploadCreatePayload,
    ) => {
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
          createPayload,
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
          ingestStatus: result.ingestStatus ?? s.ingestStatus,
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
    (file: File, metadata: TusMetadata, createPayload?: InternalUploadCreatePayload) =>
      run(file, metadata, undefined, createPayload),
    [run],
  );

  const resume = useCallback(
    (file: File, metadata: TusMetadata, createPayload?: InternalUploadCreatePayload) => {
      if (!state.uploadId) {
        // 재개할 세션이 없다 = 새 세션을 만들어야 한다. 내부 업로드는 세션 생성 POST 가 인입 메타
        // JSON 바디를 요구하므로, 바디 없이 POST 하면 415/400 이 난다 — 지금 화면에서는 도달하지
        // 않지만 이 훅을 다른 화면이 재사용하면 열린다. 원인이 드러나는 에러로 먼저 막는다.
        if (!createPayload && !endpointBase) {
          const message = '재개할 업로드 세션이 없습니다. 업로드를 다시 시작하세요.';
          setState((s) => ({ ...s, status: 'error', error: message }));
          return Promise.reject(new Error(message));
        }
        return run(file, metadata, undefined, createPayload);
      }
      // 재개는 기존 세션을 이어받으므로 세션 생성 바디를 다시 보내지 않는다(인입 행은 이미 있다).
      return run(file, metadata, state.uploadId);
    },
    [run, state.uploadId, endpointBase],
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
      ingestStatus: null,
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
