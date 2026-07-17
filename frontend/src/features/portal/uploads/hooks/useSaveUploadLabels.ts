// Phase 6 — 포털 업로드 프레임 라벨 저장 훅 + FE Label→raw 직렬화.
//
// BE 계약: PUT /v1/portal/uploads/frames/{uldFrmeSn}/labels — body 는 **최상위 raw 배열**
//   [{lblTypeCd, label, points}]. points 는 number[][] 로 그대로 보낸다(문자열 직렬화 금지 —
//   기존 useSavePortalLabels 의 JSON.stringify(points) 방식과 다르다). 현재 프레임 전체교체(멱등).

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';
import type { Label } from '@/features/label/types';

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

/**
 * 업로드 프레임 라벨 저장 mutation(전체교체 PUT 1회).
 * @param uldFrmeSn 현재 프레임 PK. undefined 면 mutate 는 거부(reject).
 */
export function useSaveUploadLabels(
  uldFrmeSn: number | undefined,
  options: UseSaveUploadLabelsOptions = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (labels: Label[]) => {
      if (uldFrmeSn === undefined || !Number.isFinite(uldFrmeSn)) {
        return Promise.reject(new Error('uldFrmeSn is required'));
      }
      return replaceUploadFrameLabels(uldFrmeSn, serializeUploadLabels(labels));
    },
    onSuccess: () => {
      if (uldFrmeSn !== undefined) {
        qc.invalidateQueries({ queryKey: PORTAL_KEYS.uploadFrameLabels(uldFrmeSn) });
      }
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
