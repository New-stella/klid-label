// 온디맨드 자동 추적 결과 → 검토 묶음(트랙 단위) 변환.
//
// ★ 수락·제외의 단위는 **트랙**이다 — 같은 객체로 이어진 결과를 묶어서 수락하거나 제외하며
//   검출 하나하나를 따로 고르지 않는다. 여러 프레임에 걸친 다중 객체 결과에서는 고를 항목이
//   너무 많아져 검토가 오히려 느려진다. 프레임(srcSn) 별로 나눠 담는 것은 기존 보류 스테이징
//   (`useLabelStore.stashPendingTracks`)이 그 형태를 그대로 먹기 때문이다.
//
// ★ 라벨 마스터 식별자(labelId)는 **응답값을 그대로** 쓴다. 검출 클래스명으로 마스터를 다시 찾지
//   않는다 — 그 해석은 서버가 한다. 식별자가 비어 있는 검출은 반영하지 않고 제외 건수로 세어
//   호출측이 사용자에게 알린다. 마스터 연결이 끊긴 라벨을 만들면 표시 색뿐 아니라 라벨명·속성
//   정의까지 함께 끊기고, **저장 전에는 정상으로 보이다가 다시 불러온 뒤에야 드러난다**.
//
// @design API-123, SCREEN-005, UC-034

import { autolabelItemToLabel, type AutolabelItem } from '../api';
import type { AutoTrackDetection, AutoTrackResponse } from '../api/autoTrack';
import type { Label } from '../types';

/** 수락·제외 단위 1건(트랙 하나, 또는 트랙 없는 단발 검출 하나). */
export interface AutoTrackGroup {
  /** 화면 선택 상태의 키. 트랙이면 트랙 축, 단발이면 프레임+순번 축이라 서로 겹치지 않는다. */
  key: string;
  /** 트래커가 부여한 객체 ID(문자열 정규화). null 이면 트랙 없는 단발 검출이다. */
  trackId: string | null;
  /** 검출 클래스명 — 묶음 안 첫 검출 기준. */
  className: string;
  /** 라벨 마스터 PK — 서버 응답값 그대로(묶음 안 첫 검출 기준). */
  labelId: number;
  /** 프레임(srcSn) 별 라벨. 그대로 병합·보류 스테이징에 넘길 수 있는 형태다. */
  bySrcSn: Record<number, Label[]>;
  /** 이 묶음의 라벨 수. */
  labelCount: number;
  /** 이 묶음이 걸친 프레임 수. */
  frameCount: number;
}

export interface AutoTrackReview {
  /** 트랙 단위 묶음. 응답 순서(프레임 순 → 검출 순)로 처음 등장한 순서를 유지한다. */
  groups: AutoTrackGroup[];
  /** 라벨 마스터 미연결(labelId 없음)로 **반영하지 않은** 검출 수. */
  unlinkedCount: number;
  /** 응답에 실려 온 검출 총수(제외분 포함). */
  detectionCount: number;
}

/** 결과가 비었는지 — 반영할 묶음이 하나도 없으면 검토 목록을 띄우지 않는다. */
export function isEmptyReview(review: AutoTrackReview): boolean {
  return review.groups.length === 0;
}

/**
 * 응답을 트랙 단위 검토 묶음으로 바꾼다.
 *
 * @param frameNoBySrcSn 프레임 PK → 프레임 번호. 요청에 실어 보낸 프레임에서 만든 사전이라
 *                       모든 응답 프레임이 여기 있어야 하며, 없으면 기존 추적 경로와 같은 규약
 *                       (프레임 번호 0)으로 둔다.
 */
export function buildAutoTrackReview(
  res: AutoTrackResponse | null | undefined,
  frameNoBySrcSn: ReadonlyMap<number, number>,
): AutoTrackReview {
  const groups: AutoTrackGroup[] = [];
  const byKey = new Map<string, AutoTrackGroup>();
  let unlinkedCount = 0;
  let detectionCount = 0;

  for (const frame of res?.frames ?? []) {
    const srcSn = frame?.srcSn;
    if (typeof srcSn !== 'number') continue;
    const frameNo = frameNoBySrcSn.get(srcSn) ?? 0;
    const detections = frame.detections ?? [];
    for (let i = 0; i < detections.length; i += 1) {
      const det = detections[i];
      detectionCount += 1;
      // 값을 지어내지 않는다 — 마스터 미연결 검출은 라벨로 만들지 않고 제외 건수로만 센다.
      if (!isLinked(det)) {
        unlinkedCount += 1;
        continue;
      }
      const labelId = det.labelId as number;
      // 트래커 ID 가 없으면 트랙 없는 단발 라벨이다(임의 발급하지 않는다). 이때는 검출 하나가
      // 곧 하나의 묶음이라 프레임·순번으로 키를 만든다.
      const trackId = qualifiedTrackId(det.trackId, frame.trackSegment);
      const key = trackId === null ? `single:${srcSn}:${i}` : `track:${trackId}`;
      let group = byKey.get(key);
      if (group === undefined) {
        group = {
          key,
          trackId,
          className: det.label,
          labelId,
          bySrcSn: {},
          labelCount: 0,
          frameCount: 0,
        };
        byKey.set(key, group);
        groups.push(group);
      }
      const bucket = group.bySrcSn[srcSn];
      if (bucket === undefined) {
        group.bySrcSn[srcSn] = [toLabel(det, labelId, frameNo, trackId)];
        group.frameCount += 1;
      } else {
        bucket.push(toLabel(det, labelId, frameNo, trackId));
      }
      group.labelCount += 1;
    }
  }

  return { groups, unlinkedCount, detectionCount };
}

/** 고른 묶음들을 프레임(srcSn) 별 라벨로 합친다. 제외한 묶음은 결과에 들어가지 않는다. */
export function mergeGroupsBySrcSn(
  groups: readonly AutoTrackGroup[],
  acceptedKeys: ReadonlySet<string>,
): Record<number, Label[]> {
  const out: Record<number, Label[]> = {};
  for (const group of groups) {
    if (!acceptedKeys.has(group.key)) continue;
    for (const [srcSn, labels] of Object.entries(group.bySrcSn)) {
      const key = Number(srcSn);
      out[key] = [...(out[key] ?? []), ...labels];
    }
  }
  return out;
}

/** 마스터 연결 여부 — 정수 PK 만 유효하다. */
function isLinked(det: AutoTrackDetection | null | undefined): boolean {
  return typeof det?.labelId === 'number' && Number.isFinite(det.labelId);
}

/**
 * 이어 보낸 조각의 객체 번호를 **앞 조각과 구분되게** 만든다.
 *
 * ★ 요청마다 트래커가 리셋돼 번호가 1 부터 다시 매겨진다 — 조각이 다르면 번호가 같아도 같은
 *   객체라는 근거가 없다. 그대로 두면 서로 다른 객체가 한 트랙으로 합쳐져 저장되고, 그 합침은
 *   **사실이 아니다**(정말 같은 객체면 사용자가 트랙 병합으로 잇는다 — 화면이 단정하지 않는다).
 *
 * 첫 조각(0 · 표식 없음)은 **번호를 그대로 둔다** — 잘리지 않은 흔한 경우의 표기가 달라지지 않게.
 */
function qualifiedTrackId(
  trackId: number | null | undefined,
  segment: number | undefined,
): string | null {
  if (trackId === null || trackId === undefined) return null;
  const seg = typeof segment === 'number' && Number.isFinite(segment) ? Math.floor(segment) : 0;
  return seg > 0 ? `${trackId}-${seg + 1}` : String(trackId);
}

/**
 * 검출 1건 → 라벨. **변환은 AI 검출 결과의 단일 진입점**(`autolabelItemToLabel` → `normalizeLabel`)
 * 을 거친다 — 도구마다 payload 를 따로 만들면 같은 필드를 나란히 빠뜨린다.
 *
 * @param trackId 묶음 축과 **같은** 객체 식별자. 이어 보낸 조각이면 조각을 구분한 값이라 저장되는
 *                라벨도 그 값을 써야 한다 — 여기만 원본 번호를 쓰면 화면의 묶음과 저장된 트랙이
 *                갈려, 검토에서 나눠 놓은 객체가 저장 시 다시 합쳐진다.
 */
function toLabel(
  det: AutoTrackDetection,
  labelId: number,
  frameNo: number,
  trackId: string | null,
): Label {
  const item: AutolabelItem = {
    lblSn: null,
    labelId,
    label: det.label,
    points: det.points ?? undefined,
    score: det.score,
    trackId: det.trackId,
    shapeType: 'BBOX',
  };
  const label = autolabelItemToLabel(item, frameNo);
  // 트랙 축만 덮는다 — 좌표·신뢰도·마스터 연결은 단일 진입점이 만든 그대로 둔다.
  return label.trackId === trackId ? label : { ...label, trackId };
}
