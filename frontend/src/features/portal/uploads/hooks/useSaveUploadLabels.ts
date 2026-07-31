// Phase 6 — 포털 업로드 프레임 라벨 저장 훅 + FE Label→raw 직렬화.
//
// BE 계약: PUT /v1/portal/uploads/frames/{uldFrmeSn}/labels — body 는 **최상위 raw 배열**
//   [{lblTypeCd, label, points}]. points 는 number[][] 로 그대로 보낸다(문자열 직렬화 금지 —
//   기존 useSavePortalLabels 의 JSON.stringify(points) 방식과 다르다). 현재 프레임 전체교체(멱등).

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';
import type { Label } from '@/features/label/types';
import { useBusyTask } from '@/features/label/hooks/useBusyTask';
import { useIsBusyKind } from '@/stores/useLabelStore';

import { replaceUploadFrameLabels, type UploadLabelRequest } from '../api';

/** LBL_NM 컬럼 상한(BE @Size(max=80))과 정합 — 초과분 안전 절단. */
const MAX_LABEL_LEN = 80;

/** FE Label 1건 → raw 요청 1행. BBOX/POLYGON 만 대상, 그 외(MASK/KEYPOINT)는 null(제외). */
export function serializeUploadLabel(lbl: Label): UploadLabelRequest | null {
  const label = (lbl.className ?? '').slice(0, MAX_LABEL_LEN);
  if (lbl.shape.type === 'BBOX') {
    const { left, top, right, bottom } = lbl.shape;
    return {
      lblTypeCd: 'BBOX',
      label,
      points: [
        [left, top],
        [right, bottom],
      ],
    };
  }
  if (lbl.shape.type === 'POLYGON') {
    const flat = lbl.shape.points;
    const points: number[][] = [];
    for (let i = 0; i + 1 < flat.length; i += 2) {
      points.push([flat[i], flat[i + 1]]);
    }
    return { lblTypeCd: 'POLYGON', label, points };
  }
  return null;
}

/** FE Label 배열 → raw 요청 배열(BBOX/POLYGON 만). */
export function serializeUploadLabels(labels: Label[]): UploadLabelRequest[] {
  return labels
    .map(serializeUploadLabel)
    .filter((r): r is UploadLabelRequest => r !== null);
}

export interface UseSaveUploadLabelsOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/** 저장 성공 결과 — 거부·폐기(null)와 구별되도록 반드시 non-null 을 돌려준다. */
export interface UploadSaveResult {
  saved: number;
}

/**
 * 업로드 프레임 라벨 저장 mutation(전체교체 PUT 1회).
 *
 * 이 화면의 유일한 장시간 작업이라 다른 라벨링 화면과 <b>동일한 배타 축</b>(store busy 'SAVE')에서
 * 실행한다. 배타 축에 올려야 저장이 도는 동안 캔버스 편집이 차단되고, 그래야 "저장 스냅샷 이후에
 * 그린 라벨"이 저장에도 못 담기고 재조회에 덮여 사라지는 경로가 원인째 없어진다(전체교체 PUT).
 *
 * 반환은 성공 시 `{ saved }`, 거부·폐기 시 <b>null</b> 이다(호출측은 `=== null` 로 판정).
 *
 * @param uldFrmeSn 현재 프레임 PK. undefined 면 mutate 는 거부(reject).
 */
export function useSaveUploadLabels(
  uldFrmeSn: number | undefined,
  options: UseSaveUploadLabelsOptions = {},
) {
  const qc = useQueryClient();
  const { runExclusiveOrNotify } = useBusyTask({ srcSn: uldFrmeSn });
  const isSaving = useIsBusyKind('SAVE', uldFrmeSn);

  const mutation = useMutation<UploadSaveResult | null, unknown, Label[]>({
    mutationFn: async (labels: Label[]) => {
      if (uldFrmeSn === undefined || !Number.isFinite(uldFrmeSn)) {
        return Promise.reject(new Error('uldFrmeSn is required'));
      }
      return runExclusiveOrNotify('SAVE', { srcSn: uldFrmeSn }, async (isAlive) => {
        await replaceUploadFrameLabels(uldFrmeSn, serializeUploadLabels(labels));
        // 후처리(캐시 무효화)도 보호 구간 안에서 — 폐기된 저장은 캐시를 건드리지 않는다.
        if (isAlive()) {
          qc.invalidateQueries({ queryKey: PORTAL_KEYS.uploadFrameLabels(uldFrmeSn) });
        }
        return { saved: labels.length };
      });
    },
    onSuccess: (result) => {
      // 거부·폐기(null)면 성공 후처리를 하지 않는다 — dirty 를 비우면 저장되지 않은 작업이 사라진다.
      if (result === null) return;
      options.onSuccess?.();
    },
    onError: options.onError,
  });

  // 진행 표시도 store busy 단일 진실원에서 파생 — 취소 시 즉시 풀린다(유령 잠금 방지).
  return { ...mutation, isPending: isSaving };
}
