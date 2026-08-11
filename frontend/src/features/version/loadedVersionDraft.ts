// R6 / API-195·API-196 — 「불러온 회차 세트」의 순수 변환 로직.
//
// 불러오기는 **서버에 아무것도 쓰지 않는다**. 그래서 확정 전까지 영상 전체의 라벨·폐기 상태는
// 화면(이 세트)에만 존재하고, 저장(API-196)이 그것을 한 트랜잭션으로 확정한다.
//
// 이 파일에 순수 함수로 모아 두는 이유: 라벨링 화면이 이미 1,900줄이라 그 안에 두면 검증이
// 화면 렌더에 묶여 "불러오기만 하고 닫으면 서버 저장 요청이 없다" 같은 계약을 좁게 고정할 수 없다.
//
// @design API-195
// @design API-196
// @req R6

import { serializeLabel } from '@/features/label/api';
import type { Label } from '@/features/label/types';

import type {
  VersionLabelFrame,
  VersionLabelItem,
  VersionLabelsResponse,
  VideoLabelSavePayload,
} from './types';

/**
 * 불러온 회차 세트 — 저장 대기 중인 영상 전체 작업본.
 *
 * `version` 은 어느 회차에서 시작했는지(기록용, API-196 `loadedVersion`),
 * `frames` 는 프레임별 라벨·폐기여부·판번호다.
 */
export interface LoadedVersionDraft {
  version: number;
  frames: VersionLabelFrame[];
}

/** API-195 응답을 세트로 변환한다(서버 순서를 그대로 보존 — FE 가 다시 정렬하지 않는다). */
export function toLoadedDraft(loaded: VersionLabelsResponse): LoadedVersionDraft {
  return {
    version: loaded.version,
    frames: (loaded.frames ?? []).map((f) => ({
      srcSn: Number(f.srcSn),
      frmNo: Number(f.frmNo),
      dscdYn: f.dscdYn === 'Y' ? 'Y' : 'N',
      lblVer: Number(f.lblVer ?? 0),
      resolved: f.resolved !== false,
      items: Array.isArray(f.items) ? f.items : [],
    })),
  };
}

/**
 * 지금 캔버스에 있는 라벨을 그 프레임의 세트 항목으로 되쓴다(프레임 전환·저장 직전).
 *
 * <p>직렬화는 프레임 단위 저장과 <b>같은 함수</b>(`serializeLabel`)를 쓴다 — 경로가 갈리면 같은 라벨이
 * 축마다 다르게 저장되고, 특히 `labelId` 누락은 <b>저장 후에만</b> 드러나 발견이 늦다.
 *
 * <p>세트에 없는 프레임(불러오기 이후 추출된 프레임 등)은 <b>끼워 넣지 않는다</b> — 판번호를 모르는
 * 프레임을 저장 목록에 넣으면 전수 검증의 근거가 없어진다.
 */
export function captureFrameIntoDraft(
  draft: LoadedVersionDraft,
  srcSn: number,
  labels: Label[],
): LoadedVersionDraft {
  if (!draft.frames.some((f) => f.srcSn === srcSn)) return draft;
  const items = labels.map((l) => serializeLabel(l) as VersionLabelItem);
  return {
    ...draft,
    frames: draft.frames.map((f) => (f.srcSn === srcSn ? { ...f, items } : f)),
  };
}

/** 그 프레임의 폐기여부를 세트에 반영한다(폐기·복원도 저장을 눌러야 확정된다 — D8). */
export function applyDiscardToDraft(
  draft: LoadedVersionDraft,
  srcSn: number,
  dscdYn: 'Y' | 'N',
): LoadedVersionDraft {
  if (!draft.frames.some((f) => f.srcSn === srcSn)) return draft;
  return {
    ...draft,
    frames: draft.frames.map((f) => (f.srcSn === srcSn ? { ...f, dscdYn } : f)),
  };
}

/**
 * 확정 저장(API-196) 요청 본문을 만든다.
 *
 * <p><b>편집하지 않은 프레임도 함께 보낸다</b> — 서버가 프레임별 판번호를 전수 검증해 하나라도
 * 어긋나면 영상 전체를 거부하는 계약이기 때문이다(일부만 저장하면 서로 다른 시점의 프레임이 섞인다).
 *
 * <p>보낼 항목은 <b>명시적으로</b> 고른다. 불러온 항목에는 표시용·AI 메타(라벨명 해석·색상·신뢰도)가
 * 섞여 있는데 저장 계약에 없는 필드라, 통째로 되보내면 서버의 관용 역직렬화에 의존하게 된다.
 */
export function toVideoSavePayload(
  draft: LoadedVersionDraft,
  currentSrcSn: number | undefined,
  currentLabels: Label[],
): VideoLabelSavePayload {
  const merged =
    currentSrcSn === undefined ? draft : captureFrameIntoDraft(draft, currentSrcSn, currentLabels);
  return {
    frames: merged.frames.map((f) => ({
      srcSn: f.srcSn,
      lblVer: f.lblVer,
      dscdYn: f.dscdYn,
      items: f.items.map((item) => ({
        id: item.id ?? null,
        lblTypeCd: item.lblTypeCd,
        // ★ labelId 는 라벨 마스터 연결의 실체다. 빠뜨리면 재조회 응답의 색상·라벨명이 null 이 되어
        //   판정이 트랙 해시색으로 낙하하고 속성 정의까지 끊긴다(저장 전에는 정상으로 보인다).
        labelId: item.labelId ?? null,
        label: item.label ?? '',
        points: item.points,
      })),
    })),
    loadedVersion: String(draft.version),
  };
}

/** 해석하지 못한(그 회차 이하 스냅샷이 없어 현재 작업본이 실려 온) 프레임 수. */
export function unresolvedFrameCount(draft: LoadedVersionDraft | null): number {
  return (draft?.frames ?? []).filter((f) => !f.resolved).length;
}
