import { useCallback, useRef } from 'react';
import { useMutation } from '@tanstack/react-query';

import { useIsBusyKind } from '@/stores/useLabelStore';

import {
  sam2TrackAllChunks,
  Sam2TrackChunkError,
  type Sam2TrackRequest,
  type Sam2TrackResponse,
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
   */
  onMockWarning?: (message: string) => void;
  onError?: (err: unknown) => void;
  /** 청크 순차 추적 진행률 — (누적 추적 프레임 수, 전체 대상 수). 청크 완료마다 호출. */
  onProgress?: (done: number, total: number) => void;
  /**
   * Phase 9 — 포털 모드면 포털 전용 /portal/frames/{id}/sam2-track 경로로 호출(persist 없이 좌표만).
   */
  portalMode?: boolean;
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

  const mutation = useMutation<Sam2TrackResponse | null, unknown, Sam2TrackRequest>({
    mutationFn: async (payload: Sam2TrackRequest) => {
      const requestedSrcSn = requestedSrcSnRef.current;
      if (requestedSrcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      // 프레임이 이미 바뀌었으면 runExclusiveOrNotify 가 시작 전에 폐기(null)한다.
      // 다른 작업 진행 중이면 요청하지 않고 null + 거부 안내 토스트(무반응 방지).
      return runExclusiveOrNotify('AI_TRACK', { srcSn: requestedSrcSn }, async (isAlive) => {
        try {
          const data = await sam2TrackAllChunks(
            requestedSrcSn,
            payload,
            // 취소된 추적의 진행률이 계속 올라오면 재실행 진행률과 뒤섞인다 — 생존 시에만 보고.
            (done, total) => {
              if (isAlive()) options.onProgress?.(done, total);
            },
            options.portalMode ?? false,
          );
          // 병합은 보호 구간 안에서. 취소/프레임 전환 뒤면 반영하지 않는다.
          if (isAlive()) {
            options.onTracked?.(data.tracked, false);
            // mock 제외 안내(BE ApiResponse.message)는 병합 뒤에 표시 — 남은 결과가 있으면 부분 안내.
            if (data.message) options.onMockWarning?.(data.message);
          }
          return data;
        } catch (err) {
          // 부분 실패(이미 성공한 청크 존재)면 성공분만 병합한다. 폐기 상태면 병합 skip.
          if (isAlive() && err instanceof Sam2TrackChunkError && err.partial.length > 0) {
            options.onTracked?.(err.partial, true);
          }
          throw err;
        }
      });
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
