// 프레임 이미지 blob URL 발급 훅.
//
// BE 의 `/v1/frames/{srcSn}/image` 는 인증 헤더가 필요하다(헤더 이름·모양은 채널마다 다르며
// 판정은 `features/auth/tokenHandoff.buildAuthHeader` 한 곳이 소유한다).
// 그러나 <img src="/api/v1/frames/.../image"> 직접 호출은 axios interceptor 를 거치지 않아
// 토큰 헤더가 누락되어 401 응답을 받는다.
//
// 해결: axios 로 fetch (인터셉터가 인증 헤더 자동 첨부) → blob 응답 → URL.createObjectURL 로 임시 URL 생성
//      → konva Image 가 해당 blob URL 을 로드.
//
// 라이프사이클:
//  - srcSn 변경 시 새 blob 생성. 이전 blob URL 은 **새 URL 이 도착한 직후** 해제한다
//    (즉시 revoke 하면 새 blob 도착 전 빈 캔버스 깜빡임 + Konva 가 revoke 된 blob 접근).
//  - 컴포넌트 unmount 시에만 마지막 URL 을 즉시 해제 (메모리 누수 방지)
//  - axios 자동 재시도 정책은 client.ts 에 위임

import { useEffect, useRef, useState } from 'react';

import { apiClient } from '@/lib/api/client';
import { ApiError } from '@/lib/api/errors';

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
  /**
   * R16 — 포털 모드. true 면 내부 전용 /frames/{id}/image(403) 대신 포털 전용
   * /portal/frames/{id}/image 로 비식별 프레임 이미지를 요청한다.
   */
  portalMode?: boolean;
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
  const portalMode = opts?.portalMode === true;

  // 현재 화면에 노출 중인 blob URL — 새 URL 이 도착할 때까지 revoke 를 지연시키기 위해
  // ref 로 보관한다. effect cleanup 에서 즉시 revoke 하지 않는다.
  const liveUrlRef = useRef<string | null>(null);

  useEffect(() => {
    if (srcSn === undefined || !Number.isFinite(srcSn)) {
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

    // raw=true 일 때만 쿼리 파라미터 첨부. 미지정/false 는 axios config 에 params 자체를 넣지 않아
    // 기존 동작(쿼리 없음)을 그대로 유지 — 테스트의 'raw 미지정' 케이스 보장.
    const config =
      raw && !portalMode
        ? { responseType: 'blob' as const, params: { raw: true } }
        : { responseType: 'blob' as const };
    // R16 — 포털 모드는 포털 전용 이미지 엔드포인트 (내부 /frames/{id}/image 는 PORTAL 채널 403)
    const path = portalMode ? `/portal/frames/${srcSn}/image` : `/frames/${srcSn}/image`;
    // 해상도/증강 파생 프레임은 원본 픽셀(SRC_FILE_PATH_NM)이 실재하지 않아 위 경로가 404 를
    // 낸다 — 비식별 전용 경로로 폴백한다. PORTAL_USER 는 이 경로에 접근 권한이 없어(403)
    // 대상에서 제외한다(§B8#3, 구 버그: deid-image 가 코드 전체에서 미사용이라 파생
    // 프레임을 여는 순간 캔버스가 백지였다).
    const deidFallbackPath = `/frames/${srcSn}/deid-image`;

    const applyBlob = (blob: Blob) => {
      const newUrl = URL.createObjectURL(blob);
      if (cancelled) {
        // 이 effect 가 이미 폐기됨(srcSn 전환/unmount) — 새 URL 은 사용되지 않으므로 즉시 해제.
        URL.revokeObjectURL(newUrl);
        return;
      }
      // 새 URL 이 도착했으니 이전 live URL 을 이제서야 해제한다 (깜빡임 방지).
      const prev = liveUrlRef.current;
      liveUrlRef.current = newUrl;
      setUrl(newUrl);
      setLoading(false);
      if (prev && prev !== newUrl) {
        URL.revokeObjectURL(prev);
      }
    };

    const applyError = (e: unknown) => {
      if (cancelled) return;
      setError(e instanceof Error ? e : new Error(String(e)));
      setLoading(false);
    };

    apiClient
      .get<Blob>(path, config)
      .then((res) => applyBlob(res.data as unknown as Blob))
      .catch((e: unknown) => {
        if (cancelled) return;
        const is404 = e instanceof ApiError && e.status === 404;
        if (is404 && !portalMode) {
          apiClient
            .get<Blob>(deidFallbackPath, { responseType: 'blob' as const })
            .then((res) => applyBlob(res.data as unknown as Blob))
            .catch(applyError);
          return;
        }
        applyError(e);
      });

    // cleanup: 이번 요청만 취소한다. live URL 은 다음 URL 도착 시점에 해제하므로 여기서
    // revoke 하지 않는다 (전환 중 빈 캔버스/Konva stale 접근 방지).
    return () => {
      cancelled = true;
    };
  }, [srcSn, raw, portalMode]);

  // 컴포넌트 완전 unmount 시점에만 마지막 live URL 을 즉시 해제 (메모리 누수 방지).
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
