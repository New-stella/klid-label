// 온디맨드 자동 추적 실행 훅 (BE 미저장).
//
// 기존 추적 훅(useSam2Track)과 갈리는 지점:
//  - 시작 객체를 받지 않는다 — 현재 프레임 + 뒤따르는 프레임 구간만으로 실행된다.
//  - 화면이 스스로 쪼개지 않는다(요청마다 트래커 상태·객체 ID 가 리셋된다). 상한 초과분은 잘라
//    보내고 그 사실을 호출측이 안내한다.
//  - 다만 **서버가 시간 예산 때문에 잘라 보내면 이어 보낸다** — 안 그러면 남은 프레임의 검출이
//    조용히 사라진다. 이어 보낸 조각의 객체 번호는 앞 조각과 합치지 않는다(같은 번호라도 같은
//    객체라는 근거가 없다).
//
// 진행 상태·중복 차단·stale 폐기는 store busy 토큰 하나로 처리한다(useBusyTask). 취소·프레임
// 전환 뒤 도착한 결과는 반영하지 않으며 그 실패도 폐기된다.
//
// ★ **이어 보내다 실패해도 앞 조각 결과는 살린다** — 서버가 이미 계산해 돌려준 것을 버리면
//   사용자는 같은 일을 다시 시키고 추론이 중복된다. 기존 추적 훅(useSam2Track)의 부분 성공
//   병합과 같은 모양이다.
//
// @design API-123, SCREEN-005, UC-034

import { useCallback, useRef } from 'react';

import { useIsBusyKind } from '@/stores/useLabelStore';

import { aiWaitBusyRenewMs } from '../aiBudget';
import {
  AUTO_TRACK_MAX_NEXT_FRAMES,
  AutoTrackPartialError,
  requestAutoTrackAll,
  type AutoTrackResponse,
} from '../api/autoTrack';

import { useBusyTask } from './useBusyTask';

export interface UseAutoTrackOptions {
  /**
   * 결과 도착 — **보호 구간(busy) 안에서** 호출된다. 자동 반영이면 여기서 작업본 병합까지
   * 끝내야 한다(바깥에서 병합하면 busy 가 먼저 풀려 미완료 후처리와 재실행이 겹친다).
   */
  onResult?: (res: AutoTrackResponse) => void;
  /**
   * 실패 안내. 폐기된 요청(취소·프레임 전환)의 실패는 전달되지 않는다.
   *
   * ⚠ **부분 결과가 있으면 {@link onResult} 가 먼저 불린다** — 이어 보내다 실패한 경우다.
   *   그래서 실패 안내가 앞선 결과 안내를 **덮어쓰면 안 된다**(덮으면 살려 둔 결과가 화면에서
   *   사라져, 버리는 것과 같아진다).
   */
  onError?: (err: unknown) => void;
  /**
   * 진행률 — (**처리한** 프레임 수, 전체 시퀀스 수). 응답 하나마다 호출되며, 서버가 잘라 보내
   * 이어 보내는 중에도 갱신된다(멈춘 것처럼 보이지 않게).
   */
  onProgress?: (done: number, total: number) => void;
  /**
   * 포털 채널이면 포털 전용 자동 추적 창구·취소 창구를 쓴다(이어 보내는 조각 전부). 판정은 화면의
   * portalMode 에서 파생해 넘긴다. 기본값 false — 내부 경로 무회귀.
   */
  portal?: boolean;
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
  const portal = options.portal ?? false;
  const { runExclusiveOrNotify } = useBusyTask({ srcSn, portal });
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
          async (isAlive, signal, requestId, renewDeadline) => {
            try {
              const res = await requestAutoTrackAll(requestedSrcSn, nextSrcSns, {
                signal,
                requestId,
                portal,
                onProgress: (done, total) => {
                  // ★ 진행이 관측됐다 — 잠금 상한을 그 시점부터 다시 센다. 서버가 잘라 보내 화면이
                  //   이어 보내는 동안 잠금이 먼저 풀리면, 서버는 잘 돌고 있는데 결과가 버려진다.
                  renewDeadline(aiWaitBusyRenewMs('AI_AUTO_TRACK', AUTO_TRACK_MAX_NEXT_FRAMES));
                  if (isAlive()) optionsRef.current.onProgress?.(done, total);
                },
              });
              // 취소·프레임 전환 뒤면 반영하지 않는다.
              if (isAlive()) optionsRef.current.onResult?.(res);
              return true;
            } catch (err) {
              // ★ 부분 실패 — 이어 보내는 도중 실패해도 **앞 조각의 검출은 살린다**. 버리면 서버가
              //   이미 계산해 돌려준 결과가 사라지고 사용자는 같은 일을 다시 시킨다(중복 추론).
              //   기존 추적 경로(useSam2Track)의 부분 성공 병합과 같은 모양이다.
              //   폐기 상태(취소·프레임 전환)면 병합하지 않는다 — 지난 프레임 결과가 현재 화면에
              //   들어가는 것을 막는 규칙은 실패 경로에서도 같다.
              if (
                isAlive() &&
                err instanceof AutoTrackPartialError &&
                (err.partial.frames?.length ?? 0) > 0
              ) {
                optionsRef.current.onResult?.(err.partial);
              }
              throw err;
            }
          },
        );
        return outcome === true;
      } catch (err) {
        // 폐기된 요청의 실패는 여기까지 오지 않는다(useBusyTask 가 삼킨다) — 살아 있는 실패만 안내.
        optionsRef.current.onError?.(err);
        return false;
      }
    },
    [srcSn, portal, runExclusiveOrNotify],
  );

  const truncatedCountOf = useCallback(
    (nextSrcSns: readonly number[]) => Math.max(0, nextSrcSns.length - AUTO_TRACK_MAX_NEXT_FRAMES),
    [],
  );

  return { run, isRunning, truncatedCountOf };
}
