import { useCallback } from 'react';

import { useIsBusyKind } from '@/stores/useLabelStore';

import {
  requestSam2Segment,
  type Sam2SegmentRequest,
  type Sam2SegmentResponse,
} from '../api';

import { useBusyTask } from './useBusyTask';

export interface UseSam2SegmentResult {
  /**
   * 요청 진행 중 여부. store busy 파생값 — 로컬 진행 플래그를 두지 않는다.
   * ⚠ 공개 계약 유지: OverlayLayer 의 확정 큐(pendingConfirm)가 이 값이 false 로 풀리는 순간
   * 큐잉된 확정을 재요청·커밋한다. 의미(=분할 요청 in-flight)를 바꾸지 말 것.
   */
  isSegmenting: boolean;
  /**
   * 클릭/박스 프롬프트로 분할 요청.
   * - 다른 장시간 작업이 진행 중이면 요청하지 않고 null(배타 실행) + **거부 안내 토스트**.
   * - 취소·리셋·프레임 전환 뒤 도착한 응답은 폐기되어 null 을 반환한다.
   * 반환: 적용 가능한 응답 또는 거부/폐기 시 null.
   */
  segment: (payload: Omit<Sam2SegmentRequest, 'srcSn'>) => Promise<Sam2SegmentResponse | null>;
}

/**
 * AI 분할(클릭/박스 프롬프트) mutation hook.
 *
 * 진행 상태·중복 차단·stale 폐기는 store busy 토큰 하나로 처리한다(useBusyTask).
 *
 * ⚠ 결과 적용(프리뷰/커밋)은 예외적으로 호출측(OverlayLayer)에 남긴다 — 즉시 그리기 모드의
 * 확정 큐가 "in-flight 해제 → 최신 누적점으로 재요청" 순서에 의존하고, 적용 로직이 캔버스 로컬
 * 세대(gen)/누적점 상태에 묶여 있기 때문이다. 그 경로는 자체 세대 가드로 유령 커밋을 막는다.
 */
/**
 * @param srcSn  현재 프레임.
 * @param portal 포털 채널이면 포털 전용 분할 창구·취소 창구를 쓴다(화면의 portalMode 에서 파생).
 */
export function useSam2Segment(srcSn: number | undefined, portal = false): UseSam2SegmentResult {
  const { runExclusiveOrNotify } = useBusyTask({ srcSn, portal });
  const isSegmenting = useIsBusyKind('AI_SEGMENT', srcSn);

  const segment = useCallback(
    async (payload: Omit<Sam2SegmentRequest, 'srcSn'>): Promise<Sam2SegmentResponse | null> => {
      if (srcSn === undefined) return null;
      return runExclusiveOrNotify('AI_SEGMENT', { srcSn }, (_isAlive, signal, requestId) =>
        requestSam2Segment(srcSn, payload, signal, requestId, portal),
      );
    },
    [srcSn, portal, runExclusiveOrNotify],
  );

  return { isSegmenting, segment };
}
