// R16 — 포털 라벨 저장 훅.
// 포털은 원본(LS_DATA_LBL) 미수정 — 본인 작업분을 LS_PORTAL_USER_LABEL 에 별도 적재한다.
// BE 계약: POST /v1/portal/user-labels (단건). 화면의 라벨 N건을 순차 POST 한다.
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { LABEL_KEYS } from '@/lib/queryKeys';
import type { Label } from '@/features/label/types';
import { useBusyTask } from '@/features/label/hooks/useBusyTask';
import { useIsBusyKind } from '@/stores/useLabelStore';

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

/** 포털 저장 성공 결과 — 폐기(null)와 구별되도록 반드시 non-null 을 돌려준다. */
export interface PortalSaveResult {
  saved: number;
}

/**
 * 포털 라벨 저장 mutation.
 *
 * 이 화면에서 가장 긴 작업(라벨 수만큼 순차 POST)이므로 내부 저장과 <b>동일하게</b> store busy
 * ('SAVE')로 배타 실행한다 — 락을 잡지 않으면 저장 도중 AI 분할/추적이 그대로 시작돼 진행 축이
 * 둘로 갈린다(R7 단일 진실원 위반).
 *
 * 반환은 성공 시 `{ saved }`, 거부·폐기 시 <b>null</b> 이다(호출측은 `=== null` 로 판정).
 *
 * @param srcSn 현재 프레임 PK
 * @param rawSn 영상 PK (LabelsResponse.videoId) — user-label 적재 키
 */
export function useSavePortalLabels(
  srcSn: number | undefined,
  rawSn: number | undefined,
  options: UseSavePortalLabelsOptions = {},
) {
  const qc = useQueryClient();
  const { runExclusiveOrNotify } = useBusyTask({ srcSn });
  const isSaving = useIsBusyKind('SAVE', srcSn);

  const mutation = useMutation<PortalSaveResult | null, unknown, Label[]>({
    mutationFn: async (labels: Label[]) => {
      if (srcSn === undefined || rawSn === undefined) {
        return Promise.reject(new Error('srcSn/rawSn is required'));
      }
      return runExclusiveOrNotify('SAVE', { srcSn }, async (isAlive) => {
        // 순차 저장 — BE 단건 계약. 본인 작업분만 적재 (IDOR 방어는 BE).
        for (const lbl of labels) {
          await savePortalUserLabel(serialize(rawSn, srcSn, lbl));
        }
        // 후처리(캐시 무효화)도 보호 구간 안에서. 폐기된 저장은 캐시를 건드리지 않는다.
        if (isAlive()) qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
        return { saved: labels.length };
      });
    },
    onSuccess: (result) => {
      // 폐기·거부(null)면 성공 후처리를 호출하지 않는다 — 호출측이 dirty 를 비우거나 이동하면 안 된다.
      if (result === null) return;
      options.onSuccess?.();
    },
    onError: options.onError,
  });

  // 진행 표시도 store busy 단일 진실원에서 파생 — 취소 시 즉시 풀린다(유령 잠금 방지).
  return { ...mutation, isPending: isSaving };
}
