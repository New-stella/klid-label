import { useMutation, useQueryClient } from '@tanstack/react-query';

import { LABEL_KEYS } from '@/lib/queryKeys';

import {
  sam2TrackAllChunks,
  Sam2TrackChunkError,
  type Sam2TrackRequest,
  type Sam2TrackResponse,
} from '../api';

export interface UseSam2TrackOptions {
  onSuccess?: (data: Sam2TrackResponse) => void;
  onError?: (err: unknown) => void;
  /** 청크 순차 추적 진행률 — (누적 추적 프레임 수, 전체 대상 수). 청크 완료마다 호출. */
  onProgress?: (done: number, total: number) => void;
  /**
   * Phase 9 — 포털 모드면 포털 전용 /portal/frames/{id}/sam2-track 경로로 호출(persist 없이 좌표만).
   * 내부 /frames/{id}/sam2-track 은 LS_DATA_LBL 에 persist + PORTAL 채널 403 이므로 포털에서 호출 금지.
   */
  portalMode?: boolean;
}

/**
 * SAM2 자동 추적 mutation hook.
 * - srcSn 미지정 시 즉시 reject.
 * - nextSrcSns 를 50개 이하 청크로 분할해 순차 호출(폴리곤 전파 체인) — BE `@Size(max=50)` 정합.
 * - 성공/부분실패(partial>0) 시 LABEL_KEYS 무효화 (이미 서버에 반영된 청크 결과를 다음 프레임 라벨에 반영).
 *   완전 실패(partial 0)면 서버 변화가 없어 무효화를 skip 한다.
 */
export function useSam2Track(srcSn: number | undefined, options: UseSam2TrackOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: Sam2TrackRequest) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      return sam2TrackAllChunks(srcSn, payload, options.onProgress, options.portalMode ?? false);
    },
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: (err) => {
      // 부분 실패(이미 서버에 반영된 청크 존재)일 때만 라벨을 재조회한다(부분 성공 유지).
      // 완전 실패(partial 0)면 서버 상태가 바뀌지 않았으므로 불필요한 리페치를 skip 한다.
      if (err instanceof Sam2TrackChunkError && err.partial.length > 0) {
        qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      }
      options.onError?.(err);
    },
  });
}
