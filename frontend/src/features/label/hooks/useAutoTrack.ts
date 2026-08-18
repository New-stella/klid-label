// 온디맨드 자동 추적 실행 훅 (BE 미저장).
//
// 기존 추적 훅(useSam2Track)과 갈리는 지점:
//  - 시작 객체를 받지 않는다 — 현재 프레임 + 뒤따르는 프레임 구간만으로 실행된다.
//  - 청크로 쪼개 이어붙이지 않는다(요청마다 트래커 상태·객체 ID 가 리셋되므로 이어붙이면 서로
//    다른 객체가 같은 ID 로 합쳐진다). 상한 초과분은 잘라 보내고 그 사실을 호출측이 안내한다.
//
// 진행 상태·중복 차단·stale 폐기는 store busy 토큰 하나로 처리한다(useBusyTask). 취소·프레임
// 전환 뒤 도착한 결과는 반영하지 않으며 그 실패도 폐기된다.
//
// @design API-123, SCREEN-005, UC-034

import { useCallback, useRef } from 'react';

import { useIsBusyKind } from '@/stores/useLabelStore';

import {
  AUTO_TRACK_MAX_NEXT_FRAMES,
  requestAutoTrack,
  type AutoTrackResponse,
} from '../api/autoTrack';

import { useBusyTask } from './useBusyTask';

export interface UseAutoTrackOptions {
  /**
   * 결과 도착 — **보호 구간(busy) 안에서** 호출된다. 자동 반영이면 여기서 작업본 병합까지
   * 끝내야 한다(바깥에서 병합하면 busy 가 먼저 풀려 미완료 후처리와 재실행이 겹친다).
   */
  onResult?: (res: AutoTrackResponse) => void;
  /** 실패 안내. 폐기된 요청(취소·프레임 전환)의 실패는 전달되지 않는다. */
  onError?: (err: unknown) => void;
}

export interface UseAutoTrackResult {
  /**
   * 실행. 반환값이 false 면 **시작하지 않았다**(프레임 미상 / 뒤따르는 프레임 없음 / 다른 작업
   * 진행 중). 거부 안내는 useBusyTask 가 띄운다.
   */
  run: (nextSrcSns: readonly number[]) => Promise<boolean>;
  isRunning: boolean;
  /** 상한 초과로 잘려 나갈 프레임 수(0 이면 절단 없음). 안내 문구 판정에 쓴다. */
  truncatedCountOf: (nextSrcSns: readonly number[]) => number;
}

export function useAutoTrack(
  srcSn: number | undefined,
  options: UseAutoTrackOptions = {},
): UseAutoTrackResult {
  const { runExclusiveOrNotify } = useBusyTask({ srcSn });
  const isRunning = useIsBusyKind('AI_AUTO_TRACK', srcSn);
  // 실행 시점의 프레임 — 이 실행은 "누른 그 프레임" 기준으로만 성립한다.
  const requestedSrcSnRef = useRef<number | undefined>(undefined);
  // 콜백을 ref 로 들고 run 의 의존성에서 뺀다 — 부모가 매 렌더 새 함수를 넘겨도 run 이 흔들리지
  // 않게(흔들리면 그것을 의존성으로 쓰는 상위 useCallback 이 연쇄로 재생성된다).
  const optionsRef = useRef(options);
  optionsRef.current = options;

  const run = useCallback(
    async (nextSrcSns: readonly number[]): Promise<boolean> => {
      if (srcSn === undefined) return false;
      // BE 는 뒤따르는 프레임을 필수로 요구한다 — 없으면 요청하지 않는다(400 왕복 제거).
      if (nextSrcSns.length === 0) return false;
      requestedSrcSnRef.current = srcSn;
      const requestedSrcSn = srcSn;
      try {
        const outcome = await runExclusiveOrNotify(
          'AI_AUTO_TRACK',
          { srcSn: requestedSrcSn },
          async (isAlive) => {
            const res = await requestAutoTrack(requestedSrcSn, nextSrcSns);
            // 취소·프레임 전환 뒤면 반영하지 않는다.
            if (isAlive()) optionsRef.current.onResult?.(res);
            return true;
          },
        );
        return outcome === true;
      } catch (err) {
        // 폐기된 요청의 실패는 여기까지 오지 않는다(useBusyTask 가 삼킨다) — 살아 있는 실패만 안내.
        optionsRef.current.onError?.(err);
        return false;
      }
    },
    [srcSn, runExclusiveOrNotify],
  );

  const truncatedCountOf = useCallback(
    (nextSrcSns: readonly number[]) => Math.max(0, nextSrcSns.length - AUTO_TRACK_MAX_NEXT_FRAMES),
    [],
  );

  return { run, isRunning, truncatedCountOf };
}
