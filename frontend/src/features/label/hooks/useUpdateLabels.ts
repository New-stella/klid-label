import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  ASSIGNMENT_KEYS,
  LABEL_KEYS,
  REVIEW_KEYS,
  VERSION_KEYS,
  VIDEO_KEYS,
} from '@/lib/queryKeys';
import { useIsBusyKind } from '@/stores/useLabelStore';

import { putLabels } from '../api';
import type { DscdYn, Label, LabelsResponse } from '../types';

import { useBusyTask } from './useBusyTask';

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
  /**
   * R4·R5 — 이 저장에 함께 실을 <b>프레임 폐기여부</b>. 폐기·복원은 별도 엔드포인트가 아니라 저장
   * 계약의 선택 필드다(D8 — 화면에서 한 일은 저장을 눌러야 확정된다).
   *
   * ⚠ <b>값이 없으면(null/undefined) 필드를 보내지 않는다</b> — BE 가 "현재 값 유지"로 처리한다.
   *   폐기를 건드리지 않은 저장이 폐기 상태를 조용히 되돌리지 않게 하는 하위호환 규약이다.
   *   따라서 호출측은 <b>사용자가 실제로 전환했을 때만</b> 값을 넘겨야 한다(서버값을 되돌려 보내면
   *   전환하지 않은 저장이 명시 지정으로 바뀐다).
   */
  dscdYn?: DscdYn | null;
}

/**
 * 라벨 일괄 PUT mutation. 성공 시 다음 캐시를 무효화:
 *  - LABEL_KEYS: 저장한 프레임의 **내부(internal) 라벨 키만** 무효화. 이 PUT 은 내부 전용
 *      /frames/{id}/labels 경로(putLabels)라 포털 user-label 데이터에 영향을 주지 않는다.
 *      따라서 LABEL_KEYS.all 광역 무효화로 포털 캐시(`...byFrame, 'portal'`)까지 churn 하지
 *      않고, 저장한 프레임의 internal 키(`...byFrame(srcSn,0), 'internal'`)만 무효화한다.
 *  - VIDEO_KEYS: 영상 목록/상세 진행률 갱신
 *  - ASSIGNMENT_KEYS: 작업 배정 진행률 갱신
 *  - REVIEW_KEYS: 검수 진행률 갱신
 *  - VERSION_KEYS: 버전 이력 패널 캐시 무효화 (저장 자체는 버전을 만들지 않지만, 검수 승인으로
 *      쌓인 버전 목록이 화면 상태와 어긋나지 않도록 보수적으로 무효화한다. 버전 스냅샷은
 *      검수 승인 시점에 BE가 생성한다 — SFR-08).
 *
 * 라벨 저장 후 프레임을 왕복하거나 작업 목록으로 빠져나갈 때 저장 전 캐시가 그대로 노출되는
 * 회귀(증상: "프레임 넘어가면 초기화") 방지를 위해 invalidate 한다.
 *
 * 저장은 store busy('SAVE')로 배타 실행된다 — 진행 중 재요청은 발화하지 않으며(중복 제출 차단),
 * 취소·리셋·프레임 전환 뒤 도착한 응답은 캐시에 반영하지 않고 <b>null</b> 로 반환한다.
 * 다른 작업(AI 탐지/분할/추적) 진행 중이라 <b>거부</b>된 경우에도 null 이며, 이때는 사용자에게
 * "…진행 중입니다" 안내 토스트가 표시된다(누르고도 무반응인 화면 방지).
 * ⚠ 호출측은 `=== null` 로 "반영/이동 금지"를 판정해야 한다(성공 시엔 저장된 라벨 응답이 온다).
 */
export function useUpdateLabels(srcSn: number | undefined, options: UseUpdateLabelsOptions = {}) {
  const qc = useQueryClient();
  const { runExclusiveOrNotify } = useBusyTask({ srcSn });
  const isSaving = useIsBusyKind('SAVE', srcSn);

  const mutation = useMutation<LabelsResponse | null, unknown, Label[]>({
    mutationFn: async (labels: Label[]) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      const internalKey = [...LABEL_KEYS.byFrame(srcSn, 0), 'internal'];
      return runExclusiveOrNotify('SAVE', { srcSn }, async (isAlive) => {
        // DEV_FIX H12 — 저장 토큰은 <b>호출 시점의 캐시 값</b>을 우선 사용한다. options.labelVersion 은
        //   렌더 시점 클로저라, 직전 저장이 갱신한 버전이 리렌더로 전달되기 전에 2회차 저장이 나가면
        //   낡은 값을 보내 자기 자신과 409 가 났다. 캐시는 아래 setQueryData 로 즉시 최신화된다.
        const cached = qc.getQueryData<LabelsResponse>(internalKey);
        const version = cached?.labelVersion ?? options.labelVersion;
        const saved = await putLabels(srcSn, labels, version, options.dscdYn);
        if (!isAlive()) {
          // 취소·리셋·프레임 전환 뒤 도착 — 화면/캐시 내용에는 반영하지 않는다. 다만 취소는
          // 클라이언트 결과 폐기일 뿐 서버 저장을 되돌리지 않으므로, 라벨셋 버전이 낡은 채로
          // 남으면 다음 저장이 자기 자신과 409 를 낸다(→ '최신 라벨 불러오기'로 미저장분 소실).
          // 내용 병합 없이 재조회만 예약해 버전 정합을 회복한다.
          qc.invalidateQueries({ queryKey: internalKey });
          return null;
        }

        // 후처리(캐시 반영)도 보호 구간 안에서 수행 — 반영 도중 재저장이 끼어들지 않게 한다.
        // DEV_FIX H12 — 저장 응답의 새 labelVersion 을 <b>동기적으로</b> 캐시에 반영한다.
        //   invalidate → refetch(비동기) 로만 갱신하면 refetch 완료 전 2회차 저장이 낡은 버전을 보내
        //   409 가 나고, '최신 라벨 불러오기' 선택 시 본인의 미저장 작업이 소실된다.
        qc.setQueryData<LabelsResponse>(internalKey, (prev) =>
          prev === undefined ? saved : { ...prev, ...saved },
        );
        qc.invalidateQueries({ queryKey: internalKey });
        // 저장 시 BE 가 LS_DATA_LBL_HSTRY 에 ADDED/UPDATED 이력을 기록하므로, 해당 프레임의
        // 변경 이력 캐시(전체 페이지)를 무효화해 히스토리 패널이 새 이력을 즉시 반영하게 한다.
        qc.invalidateQueries({ queryKey: LABEL_KEYS.historyByFrame(srcSn) });
        // R4·R5 — 폐기여부를 <b>실제로 실어 보냈을 때만</b> 형제 프레임 캐시까지 넓힌다.
        //   폐기 상태는 다른 프레임의 응답에도 `siblings[].dscdYn` 로 실려 있어(썸네일 띠의 폐기
        //   표식 근거) 이 프레임 키만 무효화하면 다른 프레임으로 이동했을 때 옛 표식이 남는다.
        //   ⚠ 평상시 저장에는 걸지 않는다 — 위 주석대로 포털 캐시까지 churn 시키기 때문이다.
        if (options.dscdYn != null) {
          qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
        }
        qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
        qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
        qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
        qc.invalidateQueries({ queryKey: VERSION_KEYS.all });
        return saved;
      });
    },
    onSuccess: (saved) => {
      // 폐기(null)면 성공 후처리를 호출하지 않는다 — 호출측이 dirty 를 비우거나 이동하면 안 된다.
      if (saved === null) return;
      options.onSuccess?.();
    },
    onError: options.onError,
  });

  // 저장 진행 표시도 store busy 단일 진실원에서 파생 — 취소 시 즉시 풀린다(유령 잠금 방지).
  return { ...mutation, isPending: isSaving };
}
