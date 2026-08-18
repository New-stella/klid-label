import { useCallback, useRef } from 'react';
import { useMutation } from '@tanstack/react-query';

import { useIsBusyKind } from '@/stores/useLabelStore';

import { aiWaitBusyMaxMs, aiWaitBusyRenewMs } from '../aiBudget';
import {
  SAM2_TRACK_MAX_FRAMES_PER_REQUEST,
  sam2TrackAllChunks,
  Sam2TrackChunkError,
  type Sam2TrackRequest,
  type Sam2TrackRunResult,
  type Sam2TrackedItem,
} from '../api';

import { useBusyTask } from './useBusyTask';

export interface UseSam2TrackOptions {
  /**
   * 추적 성공(전체/부분) 시 성공분(tracked)을 전달한다. 호출측이 작업본 병합을 수행한다(HIGH #7/#10).
   * **보호 구간(busy) 안에서** 호출되므로 여기서 병합까지 끝내야 한다.
   * @param tracked 성공 확정된 추적 결과(부분 실패 시 partial)
   * @param partial true 면 부분 성공(일부 청크 실패), false 면 전체 성공
   */
  onTracked?: (tracked: Sam2TrackedItem[], partial: boolean) => void;
  /**
   * mock(모델 미로드) 프레임이 BE 에서 제외됐을 때 안내를 전달한다 — SAM2 분할/오토라벨과 동일 규약.
   * 자동 적용 차단 자체는 BE 가 그 프레임을 결과에서 빼는 것으로 이미 성립하며, 여기서는 사용자가
   * "왜 결과가 비었는지" 알 수 있게 경고를 표시한다(C-ISSUE-81).
   *
   * ★ 여기 오는 문구는 **mock 안내뿐이다** — 서버가 절단(예산 소진)을 문구로도 알리던 동작은
   *   폐기됐고, 남은 몫은 {@link onIncomplete} 가 실행 전체 기준의 정확한 수로 말한다.
   *   ⚠ 실행 주체(`sam2TrackAllChunks`)는 이 문구를 **절단 여부로 거르지 않는다** — 절단이면서
   *     동시에 mock 이기도 한 응답에서 거르면 그 mock 안내가 사라지고, 그러면 결과에서 빠진
   *     프레임을 사용자가 알 방법이 없다.
   */
  onMockWarning?: (message: string) => void;
  onError?: (err: unknown) => void;
  /**
   * 진행률 — (**처리한** 프레임 수, 전체 대상 수). 응답 하나마다 호출되며, 서버가 잘라 보내
   * 이어 보내는 중에도 갱신된다(멈춘 것처럼 보이지 않게).
   *
   * ⚠ 결과 개수가 아니라 처리 수다 — 처리했지만 결과에서 빠지는 프레임이 있어, 결과 개수로 세면
   *   진행이 멈춘 것처럼 보인다.
   */
  onProgress?: (done: number, total: number) => void;
  /**
   * 요청 단위 예산이 다해 **끝까지 가지 못했을 때** 남은 프레임 수를 전달한다(실패가 아니다).
   *
   * ★ 정상 경로에서는 남은 프레임을 자동으로 이어 보내 끝까지 간다. 여기까지 오는 것은 이어 보내도
   *   진행이 없어 멈춘 경우뿐이며, **감추면 라벨만 사라진 것처럼 보인다**.
   */
  onIncomplete?: (remaining: number) => void;
}

/**
 * AI 추적 mutation hook (BE 미저장).
 *
 * - srcSn 미지정 시 즉시 reject.
 * - nextSrcSns 를 50개 이하 청크로 분할해 순차 호출(폴리곤 전파 체인) — BE `@Size(max=50)` 정합.
 * - **자동 저장/refetch 없음**: BE 가 추적 결과를 persist 하지 않으므로 invalidateQueries 를
 *   호출하지 않는다. 성공분(tracked)은 onTracked 로 넘겨 호출측이 작업본에 병합한다.
 * - 진행 상태·중복 차단·stale 폐기는 store busy 토큰 하나로 처리한다(useBusyTask). 취소·프레임
 *   전환 뒤 도착한 결과는 병합하지 않고 mutation 결과도 null 이 되며, **그 실패(예외)도 폐기**되어
 *   onError('추적 실패')가 뜨지 않는다. 다른 작업 진행 중이라 거부된 경우에만 안내 토스트가 뜬다.
 */
export function useSam2Track(srcSn: number | undefined, options: UseSam2TrackOptions = {}) {
  const { runExclusiveOrNotify } = useBusyTask({ srcSn });
  const isTracking = useIsBusyKind('AI_TRACK', srcSn);
  // 실행(mutate) 시점의 프레임. TanStack 은 mutationFn 을 **실행 시점의 최신 옵션**으로 호출하므로
  // 클로저의 srcSn 은 이미 다음 프레임일 수 있다. 추적은 "누른 그 프레임" 기준으로만 성립한다.
  const requestedSrcSnRef = useRef<number | undefined>(undefined);

  const mutation = useMutation<Sam2TrackRunResult | null, unknown, Sam2TrackRequest>({
    mutationFn: async (payload: Sam2TrackRequest) => {
      const requestedSrcSn = requestedSrcSnRef.current;
      if (requestedSrcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      // 프레임이 이미 바뀌었으면 runExclusiveOrNotify 가 시작 전에 폐기(null)한다.
      // 다른 작업 진행 중이면 요청하지 않고 null + 거부 안내 토스트(무반응 방지).
      return runExclusiveOrNotify(
        'AI_TRACK',
        { srcSn: requestedSrcSn },
        async (isAlive, signal, requestId, renewDeadline) => {
        try {
          const data = await sam2TrackAllChunks(
            requestedSrcSn,
            payload,
            // 취소된 추적의 진행률이 계속 올라오면 재실행 진행률과 뒤섞인다 — 생존 시에만 보고.
            (done, total) => {
              // ★ 진행이 관측됐다 — 잠금 상한을 그 시점부터 다시 센다. 서버가 예산을 다 써
              //   잘라 보내면 화면이 이어 보내는데, 그 이어 보내기는 처음 잡은 상한 밖에서
              //   일어나 연장하지 않으면 «서버는 잘 돌고 있는데 결과가 버려지는» 상태가 된다.
              renewDeadline(aiWaitBusyRenewMs('AI_TRACK', SAM2_TRACK_MAX_FRAMES_PER_REQUEST));
              if (isAlive()) options.onProgress?.(done, total);
            },
            // 취소 신호를 **모든 조각**에 흘려보낸다 — 한 조각만 실으면 취소 뒤에도 나머지가 돈다.
            signal,
            requestId,
          );
          // 병합은 보호 구간 안에서. 취소/프레임 전환 뒤면 반영하지 않는다.
          if (isAlive()) {
            options.onTracked?.(data.tracked, false);
            // mock 제외 안내(BE ApiResponse.message)는 병합 뒤에 표시 — 남은 결과가 있으면 부분 안내.
            if (data.message) options.onMockWarning?.(data.message);
            // 끝까지 가지 못했으면 그 사실을 알린다 — 감추면 «왜 뒤쪽 프레임엔 없지» 로 남는다.
            if (data.unprocessed > 0) options.onIncomplete?.(data.unprocessed);
          }
          return data;
        } catch (err) {
          // 부분 실패(이미 성공한 청크 존재)면 성공분만 병합한다. 폐기 상태면 병합 skip.
          if (isAlive() && err instanceof Sam2TrackChunkError && err.partial.length > 0) {
            options.onTracked?.(err.partial, true);
          }
          throw err;
        }
        },
        {
          // ★ 잠금 구간은 요청 하나가 아니라 **조각 묶음 전체**다. 종류 기본값(요청 1건 기준)으로
          //   두면 두 번째 조각에서 잠금이 먼저 풀려, 추적이 계속 성공하는데도 결과가 조용히
          //   폐기된다(사용자는 성공도 실패도 못 본다).
          maxDurationMs: aiWaitBusyMaxMs(
            'AI_TRACK',
            payload.nextSrcSns.length,
            SAM2_TRACK_MAX_FRAMES_PER_REQUEST,
          ),
        },
      );
    },
    onError: options.onError,
  });

  const { mutate: rawMutate, mutateAsync: rawMutateAsync } = mutation;
  const mutate = useCallback<typeof rawMutate>(
    (variables, opts) => {
      requestedSrcSnRef.current = srcSn;
      return rawMutate(variables, opts);
    },
    [rawMutate, srcSn],
  );
  const mutateAsync = useCallback<typeof rawMutateAsync>(
    (variables, opts) => {
      requestedSrcSnRef.current = srcSn;
      return rawMutateAsync(variables, opts);
    },
    [rawMutateAsync, srcSn],
  );

  // 진행 표시는 store busy 단일 진실원에서 파생 — 취소 시 즉시 풀린다(유령 잠금 방지).
  return { ...mutation, mutate, mutateAsync, isPending: isTracking };
}
