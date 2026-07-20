import { useRef } from 'react';
import { useMutation } from '@tanstack/react-query';

import {
  sam2TrackAllChunks,
  Sam2TrackChunkError,
  type Sam2TrackRequest,
  type Sam2TrackResponse,
  type Sam2TrackedItem,
} from '../api';

export interface UseSam2TrackOptions {
  /**
   * 추적 성공(전체/부분) 시 성공분(tracked)을 전달한다. 호출측이 작업본 병합을 수행한다(HIGH #7/#10).
   * @param tracked 성공 확정된 추적 결과(부분 실패 시 partial)
   * @param partial true 면 부분 성공(일부 청크 실패), false 면 전체 성공
   */
  onTracked?: (tracked: Sam2TrackedItem[], partial: boolean) => void;
  onError?: (err: unknown) => void;
  /** 청크 순차 추적 진행률 — (누적 추적 프레임 수, 전체 대상 수). 청크 완료마다 호출. */
  onProgress?: (done: number, total: number) => void;
  /**
   * Phase 9 — 포털 모드면 포털 전용 /portal/frames/{id}/sam2-track 경로로 호출(persist 없이 좌표만).
   */
  portalMode?: boolean;
}

interface TrackMutationContext {
  requestedSrcSn: number | undefined;
}

/**
 * SAM2 자동 추적 mutation hook (Phase 3 전환 — BE 미저장).
 *
 * - srcSn 미지정 시 즉시 reject.
 * - nextSrcSns 를 50개 이하 청크로 분할해 순차 호출(폴리곤 전파 체인) — BE `@Size(max=50)` 정합.
 * - **자동 저장/refetch 제거**: BE 가 추적 결과를 persist 하지 않으므로 invalidateQueries 를 호출하지
 *   않는다(HIGH #2/#10). 대신 성공분(tracked)을 onTracked 로 넘겨 호출측이 작업본에 병합한다.
 * - **stale 가드(HIGH #8)**: 요청 시점 srcSn 과 응답 도착 시점의 현재 srcSn 이 다르면(프레임 전환)
 *   결과를 폐기해 엉뚱한 프레임에 병합되는 것을 막는다.
 * - 진행 중 중복 트리거 차단은 호출측이 `isPending` 으로 가드한다(Sam2TrackTool).
 */
export function useSam2Track(srcSn: number | undefined, options: UseSam2TrackOptions = {}) {
  // 응답 도착 시점의 "현재" srcSn — 매 렌더 최신값으로 갱신(stale 비교 기준).
  const currentSrcSnRef = useRef<number | undefined>(srcSn);
  currentSrcSnRef.current = srcSn;

  return useMutation<Sam2TrackResponse, unknown, Sam2TrackRequest, TrackMutationContext>({
    // 요청 시점 srcSn 을 컨텍스트로 캡처 → onSuccess/onError 에서 현재값과 비교.
    onMutate: () => ({ requestedSrcSn: srcSn }),
    mutationFn: (payload: Sam2TrackRequest) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      return sam2TrackAllChunks(srcSn, payload, options.onProgress, options.portalMode ?? false);
    },
    onSuccess: (data, _payload, context) => {
      // 프레임 전환 후 도착한 응답이면 폐기(병합/콜백 skip).
      if (context?.requestedSrcSn !== currentSrcSnRef.current) return;
      options.onTracked?.(data.tracked, false);
    },
    onError: (err, _payload, context) => {
      const stale = context?.requestedSrcSn !== currentSrcSnRef.current;
      // 부분 실패(이미 성공한 청크 존재)면 성공분만 병합하도록 전달. stale 이면 병합 skip.
      if (!stale && err instanceof Sam2TrackChunkError && err.partial.length > 0) {
        options.onTracked?.(err.partial, true);
      }
      options.onError?.(err);
    },
  });
}
