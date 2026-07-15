// R16 — 포털 라벨 저장 훅.
// 포털은 원본(LS_DATA_LBL) 미수정 — 본인 작업분을 LS_PORTAL_USER_LABEL 에 별도 적재한다.
// BE 계약: POST /v1/portal/user-labels (단건). 화면의 라벨 N건을 순차 POST 한다.
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { LABEL_KEYS } from '@/lib/queryKeys';
import type { Label } from '@/features/label/types';

import { savePortalUserLabel, type PortalUserLabelRequest } from '../api';

/**
 * FE Label → BE PortalUserLabelRequest 직렬화 (points 는 JSON 문자열).
 *
 * Phase 9 (ADR-013 override) — 포털에 키포인트(SKELETON) 라벨을 허용한다. KEYPOINT shape 는
 * 삼중값 [[x,y,v], x17] 로 직렬화하고 lblTypeCd='SKELETON' 으로 보낸다. BE PortalLabelService 는
 * SKELETON 을 삼중값 전용 경로로 검증(17점·v∈{0,1,2})하며 LS_PORTAL_USER_LABEL 에만 적재(단방향).
 */
function serialize(rawSn: number, srcSn: number, lbl: Label): PortalUserLabelRequest {
  if (lbl.shape.type === 'KEYPOINT') {
    const points = lbl.shape.keypoints.map((kp) => [kp.x, kp.y, kp.v]);
    return {
      sourceRawSn: rawSn,
      sourceSrcSn: srcSn,
      lblTypeCd: 'SKELETON',
      label: lbl.className,
      points: JSON.stringify(points),
    };
  }
  const lblTypeCd = lbl.shape.type === 'MASK' ? 'SEGMENT' : lbl.shape.type;
  let points: number[][];
  if (lbl.shape.type === 'BBOX') {
    points = [
      [lbl.shape.left, lbl.shape.top],
      [lbl.shape.right, lbl.shape.bottom],
    ];
  } else if (lbl.shape.type === 'POLYGON') {
    const flat = lbl.shape.points;
    points = [];
    for (let i = 0; i + 1 < flat.length; i += 2) {
      points.push([flat[i], flat[i + 1]]);
    }
  } else {
    points = [];
  }
  return {
    sourceRawSn: rawSn,
    sourceSrcSn: srcSn,
    lblTypeCd,
    label: lbl.className,
    points: JSON.stringify(points),
  };
}

export interface UseSavePortalLabelsOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/**
 * 포털 라벨 저장 mutation.
 * @param srcSn 현재 프레임 PK
 * @param rawSn 영상 PK (LabelsResponse.videoId) — user-label 적재 키
 */
export function useSavePortalLabels(
  srcSn: number | undefined,
  rawSn: number | undefined,
  options: UseSavePortalLabelsOptions = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (labels: Label[]) => {
      if (srcSn === undefined || rawSn === undefined) {
        return Promise.reject(new Error('srcSn/rawSn is required'));
      }
      // 순차 저장 — BE 단건 계약. 본인 작업분만 적재 (IDOR 방어는 BE).
      for (const lbl of labels) {
        await savePortalUserLabel(serialize(rawSn, srcSn, lbl));
      }
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
