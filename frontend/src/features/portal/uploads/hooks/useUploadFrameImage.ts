// Phase 6 — 포털 업로드 프레임 이미지 blob URL 발급 훅.
//
// BE `/v1/portal/uploads/frames/{uldFrmeSn}/image` 는 Authorization 헤더가 필요하다.
// <img src="/api/v1/portal/uploads/frames/.../image"> 직접 로드는 axios interceptor 를 거치지
// 않아 토큰이 누락되어 401 이므로, apiClient 로 blob 을 받아 ObjectURL 을 발급한다.
//
// 라이프사이클은 label/hooks/useImageBlob 패턴을 준용한다(코어 파일 수정 없이 별도 훅):
//  - uldFrmeSn 변경 시 새 blob. 이전 URL 은 새 URL 도착 직후 해제(전환 깜빡임/Konva stale 방지).
//  - unmount 시 마지막 URL 을 즉시 해제(메모리 누수 방지).

import { useEffect, useRef, useState } from 'react';

import { apiClient } from '@/lib/api/client';

export interface UseUploadFrameImageResult {
  /** blob: URL — new Image().src 에 그대로 사용 가능. 미로드 시 null. */
  url: string | null;
  loading: boolean;
  error: Error | null;
}

/**
 * 업로드 프레임 이미지 바이너리를 blob URL 로 발급.
 * @param uldFrmeSn 업로드 프레임 PK. undefined 면 fetch 안 함.
 */
export function useUploadFrameImage(uldFrmeSn: number | undefined): UseUploadFrameImageResult {
  const [url, setUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);

  const liveUrlRef = useRef<string | null>(null);

  useEffect(() => {
    if (uldFrmeSn === undefined || !Number.isFinite(uldFrmeSn)) {
      if (liveUrlRef.current) {
        URL.revokeObjectURL(liveUrlRef.current);
        liveUrlRef.current = null;
      }
      setUrl(null);
      setError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    apiClient
      .get<Blob>(`/portal/uploads/frames/${uldFrmeSn}/image`, { responseType: 'blob' })
      .then((res) => {
        const blob = res.data as unknown as Blob;
        const newUrl = URL.createObjectURL(blob);
        if (cancelled) {
          URL.revokeObjectURL(newUrl);
          return;
        }
        const prev = liveUrlRef.current;
        liveUrlRef.current = newUrl;
        setUrl(newUrl);
        setLoading(false);
        if (prev && prev !== newUrl) {
          URL.revokeObjectURL(prev);
        }
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setError(e instanceof Error ? e : new Error(String(e)));
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [uldFrmeSn]);

  useEffect(() => {
    return () => {
      if (liveUrlRef.current) {
        URL.revokeObjectURL(liveUrlRef.current);
        liveUrlRef.current = null;
      }
    };
  }, []);

  return { url, loading, error };
}
