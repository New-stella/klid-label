// 프레임 이미지 blob URL 발급 훅.
//
// BE 의 `/v1/frames/{srcSn}/image` 는 인증 헤더 (Authorization: Bearer ...) 가 필요하다.
// 그러나 <img src="/api/v1/frames/.../image"> 직접 호출은 axios interceptor 를 거치지 않아
// 토큰 헤더가 누락되어 401 응답을 받는다.
//
// 해결: axios 로 fetch (Bearer 자동 첨부) → blob 응답 → URL.createObjectURL 로 임시 URL 생성
//      → konva Image 가 해당 blob URL 을 로드.
//
// 라이프사이클:
//  - srcSn 변경 시 새 blob 생성, 이전 blob 은 URL.revokeObjectURL 로 해제 (메모리 누수 방지)
//  - 컴포넌트 unmount 시 cleanup 에서 동일 처리
//  - axios 자동 재시도 정책은 client.ts 에 위임

import { useEffect, useState } from 'react';

import { apiClient } from '@/lib/api/client';

export interface UseImageBlobResult {
  /** blob: URL — img.src 또는 new Image().src 에 그대로 사용 가능 */
  url: string | null;
  loading: boolean;
  error: Error | null;
}

export interface UseImageBlobOptions {
  /**
   * REVIEWER 가 원본(RAW) 프레임을 요청할 때 true.
   * BE 는 WORKER 요청 시 raw=true 를 무시하고 DEID 강제.
   * 기본 false (DEID 우선, ANONY 폴백 / PRVC·PSDO 미준비 시 404).
   */
  raw?: boolean;
}

/**
 * 프레임 이미지 바이너리를 blob URL 로 발급.
 *
 * @param srcSn 프레임 PK (LS_DATA_SRC.SRC_SN). undefined 면 fetch 안 함.
 * @param opts  raw=true 시 RAW 원본 이미지 요청 (REVIEWER 전용, WORKER 는 BE 가 무시)
 */
export function useImageBlob(
  srcSn: number | undefined,
  opts?: UseImageBlobOptions,
): UseImageBlobResult {
  const [url, setUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);
  const raw = opts?.raw === true;

  useEffect(() => {
    if (srcSn === undefined || !Number.isFinite(srcSn)) {
      setUrl(null);
      setError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    let createdUrl: string | null = null;
    setLoading(true);
    setError(null);

    // raw=true 일 때만 쿼리 파라미터 첨부. 미지정/false 는 axios config 에 params 자체를 넣지 않아
    // 기존 동작(쿼리 없음)을 그대로 유지 — 테스트의 'raw 미지정' 케이스 보장.
    const config = raw
      ? { responseType: 'blob' as const, params: { raw: true } }
      : { responseType: 'blob' as const };

    apiClient
      .get<Blob>(`/frames/${srcSn}/image`, config)
      .then((res) => {
        if (cancelled) return;
        const blob = res.data as unknown as Blob;
        createdUrl = URL.createObjectURL(blob);
        setUrl(createdUrl);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setError(e instanceof Error ? e : new Error(String(e)));
        setUrl(null);
        setLoading(false);
      });

    return () => {
      cancelled = true;
      if (createdUrl) {
        URL.revokeObjectURL(createdUrl);
      }
    };
  }, [srcSn, raw]);

  return { url, loading, error };
}
