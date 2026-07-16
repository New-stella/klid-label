import { useCallback, useRef, useState } from 'react';

import {
  requestSam2Segment,
  type Sam2SegmentRequest,
  type Sam2SegmentResponse,
} from '../api';

export interface UseSam2SegmentResult {
  /** 요청 진행 중 여부 (진행 중 신규 요청 무시 가드). */
  isSegmenting: boolean;
  /**
   * 클릭/박스 프롬프트로 분할 요청.
   * - 진행 중이면 무시(중복 방지).
   * - 응답 도착 시 요청 시점 srcSn 과 현재 srcSn 이 다르면 폐기(프레임 전환 stale 가드).
   * 반환: 적용 가능한 응답 또는 폐기/무시 시 null.
   */
  segment: (payload: Omit<Sam2SegmentRequest, 'srcSn'>) => Promise<Sam2SegmentResponse | null>;
}

/**
 * SAM2 클릭/박스 분할 mutation hook (Phase 4).
 * - srcSn 미지정 시 즉시 null.
 * - 진행 중 재요청 무시 (isSegmenting 플래그).
 * - 프레임 전환 후 도착한 응답 폐기 (요청 시점 srcSn vs 현재 srcSn).
 */
export function useSam2Segment(
  srcSn: number | undefined,
  // Phase 9 — 포털 모드면 포털 전용 /portal/frames/{id}/sam2-segment 경로로 요청(내부 경로 미호출).
  portalMode = false,
): UseSam2SegmentResult {
  const [isSegmenting, setIsSegmenting] = useState(false);
  // 최신 srcSn 을 ref 로 추적 — 비동기 응답 도착 시점에 stale 비교.
  const currentSrcSnRef = useRef<number | undefined>(srcSn);
  currentSrcSnRef.current = srcSn;
  // 진행 중 동기 가드 (state 비동기 갱신 race 방지).
  const inflightRef = useRef(false);

  const segment = useCallback(
    async (payload: Omit<Sam2SegmentRequest, 'srcSn'>): Promise<Sam2SegmentResponse | null> => {
      if (srcSn === undefined) return null;
      if (inflightRef.current) return null; // 진행 중 신규 요청 무시
      const requestedSrcSn = srcSn;
      inflightRef.current = true;
      setIsSegmenting(true);
      try {
        const res = await requestSam2Segment(requestedSrcSn, payload, portalMode);
        // 프레임 전환 후 도착한 응답이면 폐기.
        if (currentSrcSnRef.current !== requestedSrcSn) return null;
        return res;
      } finally {
        inflightRef.current = false;
        setIsSegmenting(false);
      }
    },
    [srcSn, portalMode],
  );

  return { isSegmenting, segment };
}
