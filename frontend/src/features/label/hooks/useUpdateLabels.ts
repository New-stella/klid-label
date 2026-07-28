import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  ASSIGNMENT_KEYS,
  LABEL_KEYS,
  REVIEW_KEYS,
  VERSION_KEYS,
  VIDEO_KEYS,
} from '@/lib/queryKeys';

import { putLabels } from '../api';
import type { Label, LabelsResponse } from '../types';

export interface UseUpdateLabelsOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
  /**
   * 저장 요청에 실어 보낼 라벨셋 버전 (C-ISSUE-21) — <b>폴백 값</b>이다. 실제 전송값은 호출 시점의
   * 캐시(LabelsResponse.labelVersion)를 우선 사용하며(DEV_FIX H12), 캐시가 비었을 때만 이 값을 쓴다.
   * 둘 다 없으면 버전을 보내지 않아 BE 하위호환 경로(검사 skip)로 동작한다 — 이 경우 동시 저장 시 남의
   * 라벨이 조용히 삭제될 수 있으므로, 내부 라벨링 화면은 캐시가 채워진 상태에서만 저장한다.
   */
  labelVersion?: number | null;
}

/**
 * 라벨 일괄 PUT mutation. 성공 시 다음 캐시를 무효화:
 *  - LABEL_KEYS: 저장한 프레임의 **내부(internal) 라벨 키만** 무효화. 이 PUT 은 내부 전용
 *      /frames/{id}/labels 경로(putLabels)라 포털 user-label 데이터에 영향을 주지 않는다.
 *      따라서 LABEL_KEYS.all 광역 무효화로 포털 캐시(`...byFrame, 'portal'`)까지 churn 하지
 *      않고, 저장한 프레임의 internal 키(`...byFrame(srcSn,0), 'internal'`)만 무효화한다.
 *      (srcSn 미상이면 안전하게 LABEL_KEYS.all 로 폴백 — 거의 발생하지 않는 경로)
 *  - VIDEO_KEYS: 영상 목록/상세 진행률 갱신
 *  - ASSIGNMENT_KEYS: 작업 배정 진행률 갱신
 *  - REVIEW_KEYS: 검수 진행률 갱신
 *  - VERSION_KEYS: 버전 이력 패널 캐시 무효화 (저장 자체는 버전을 만들지 않지만, 검수 승인으로
 *      쌓인 버전 목록이 화면 상태와 어긋나지 않도록 보수적으로 무효화한다. 버전 스냅샷은
 *      검수 승인 시점에 BE가 생성한다 — SFR-08).
 *
 * 라벨 저장 후 프레임을 왕복하거나 작업 목록으로 빠져나갈 때 저장 전 캐시가 그대로 노출되는
 * 회귀(증상: "프레임 넘어가면 초기화") 방지를 위해 invalidate 한다.
 */
export function useUpdateLabels(srcSn: number | undefined, options: UseUpdateLabelsOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (labels: Label[]) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      // DEV_FIX H12 — 저장 토큰은 <b>호출 시점의 캐시 값</b>을 우선 사용한다. options.labelVersion 은
      //   렌더 시점 클로저라, 직전 저장이 갱신한 버전이 리렌더로 전달되기 전에 2회차 저장이 나가면
      //   낡은 값을 보내 자기 자신과 409 가 났다. onSuccess 의 setQueryData 가 캐시를 즉시 최신화하므로
      //   캐시를 읽으면 렌더 타이밍과 무관하게 항상 최신이다. 캐시가 비면 기존 옵션 값으로 폴백한다.
      const cached = qc.getQueryData<LabelsResponse>([...LABEL_KEYS.byFrame(srcSn, 0), 'internal']);
      const version = cached?.labelVersion ?? options.labelVersion;
      return putLabels(srcSn, labels, version);
    },
    onSuccess: (saved) => {
      // 저장한 프레임의 internal 라벨 키만 무효화 (포털 캐시 churn 방지).
      if (srcSn !== undefined) {
        // DEV_FIX H12 — 저장 응답의 새 labelVersion 을 <b>동기적으로</b> 캐시에 반영한다.
        //   기존에는 invalidate → refetch(비동기) 로만 갱신돼, refetch 완료 전에 2회차 저장을 누르면
        //   낡은 버전을 보내 409 가 났다. 그러면 "다른 사용자가 먼저 저장했습니다" 다이얼로그가 뜨고,
        //   사용자가 '최신 라벨 불러오기'를 고르면 clearDirty() 로 <b>본인의 미저장 작업이 소실</b>됐다.
        //   같은 사용자의 연속 저장이 자기 자신과 충돌하지 않도록 setQueryData 로 즉시 최신화한다.
        qc.setQueryData<LabelsResponse>(
          [...LABEL_KEYS.byFrame(srcSn, 0), 'internal'],
          (prev) => (prev === undefined ? saved : { ...prev, ...saved }),
        );
        qc.invalidateQueries({ queryKey: [...LABEL_KEYS.byFrame(srcSn, 0), 'internal'] });
        // 저장 시 BE 가 LS_DATA_LBL_HSTRY 에 ADDED/UPDATED 이력을 기록하므로, 해당 프레임의
        // 변경 이력 캐시(전체 페이지)를 무효화해 히스토리 패널이 새 이력을 즉시 반영하게 한다.
        qc.invalidateQueries({ queryKey: LABEL_KEYS.historyByFrame(srcSn) });
      } else {
        qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      }
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
      qc.invalidateQueries({ queryKey: VERSION_KEYS.all });
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
