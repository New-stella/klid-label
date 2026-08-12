// R6 / API-195·API-196(v4) — 「불러온 회차 세트」의 순수 변환 로직.
//
// 불러오기는 **서버에 아무것도 쓰지 않는다**. 확정 전까지 영상 전체의 라벨·폐기 상태는 화면(이 세트)에만
// 존재하고, 저장(API-196)이 그것을 한 트랜잭션으로 확정한다.
//
// ★ 확정 저장에 보내는 것은 **회차 번호 + 전 프레임 판번호 + 고친 프레임**뿐이다. 본문 전량을 되보내지
//   않는 이유는 서버가 회차 스냅샷을 직접 읽어 적용하기 때문이며, 그 덕분에 사람이 그린 것인지 자동으로
//   붙은 것인지(생산이력)와 trackId 가 회차에 적힌 대로 살아남는다.
//
// 이 파일에 순수 함수로 모아 두는 이유: 라벨링 화면이 이미 1,900줄이라 그 안에 두면 검증이 화면 렌더에
// 묶여 "고친 프레임만 실린다" 같은 계약을 좁게 고정할 수 없다.
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
  VideoLabelEdit,
  VideoLabelSavePayload,
} from './types';

/**
 * 불러온 회차 세트 — 저장 대기 중인 영상 전체 작업본.
 *
 * - `version`  : 어느 회차에서 시작했는지(확정 저장의 `loadedVersion`)
 * - `frames`   : 프레임별 판번호·폐기여부·회차 본문(**불러온 그대로, 변형하지 않는다**)
 * - `editedBy` : 사람이 고친 프레임의 편집 내용(`srcSn` → 편집분)
 *
 * `frames` 를 원본 그대로 두는 이유: **"고쳤는가"의 판정 기준선**이기 때문이다. 여기에 캔버스 내용을
 * 덮어써 버리면 기준선이 사라져 이후 비교가 항상 "변경 없음"이 된다.
 */
export interface LoadedVersionDraft {
  version: number;
  frames: VersionLabelFrame[];
  editedBy: Record<number, VideoLabelEdit>;
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
    editedBy: {},
  };
}

/** 세트에서 그 프레임의 회차 본문을 찾는다(없으면 `undefined` — 세트 밖 프레임). */
export function frameOf(
  draft: LoadedVersionDraft,
  srcSn: number,
): VersionLabelFrame | undefined {
  return draft.frames.find((f) => f.srcSn === srcSn);
}

/**
 * 지금 캔버스 내용이 <b>회차 본문과 다른지</b> 판정해, 다르면 편집으로 기록한다.
 *
 * <h3>"고쳤다"의 판정 근거 — 값 비교다 (편집 이벤트 추적이 아니다)</h3>
 * 캔버스는 한 번에 한 프레임만 보여주므로 실제 편집분은 사용자가 방문한 프레임뿐이지만, <b>방문했다는
 * 사실만으로 편집으로 기록하면</b> 그냥 넘겨 본 프레임까지 `edits` 에 실려 서버가 "사람이 고친 내용"으로
 * 취급한다. 반대로 편집 이벤트를 추적하는 방식은 undo/redo·AI 병합·되돌리기 등 캔버스를 바꾸는 경로가
 * 여러 개라 한 곳만 빠뜨려도 조용히 누락된다.
 *
 * <p>그래서 <b>불러온 값과 현재 값을 직렬화해 비교</b>한다 — 결과가 같으면 보낼 것이 없고, 다르면
 * 무엇을 통해 바뀌었든 잡힌다. 비교 대상은 저장에 실제로 실리는 필드뿐이다(직렬화 결과 자체를 비교하니
 * 표시용 필드는 애초에 들어오지 않는다).
 *
 * <p>직렬화는 프레임 단위 저장과 <b>같은 함수</b>(`serializeLabel`)를 쓴다 — 경로가 갈리면 같은 라벨이
 * 축마다 다르게 저장되고, 특히 `labelId` 누락은 <b>저장 후에만</b> 드러나 발견이 늦다.
 *
 * <p>세트에 없는 프레임(불러오기 이후 추출된 프레임 등)은 <b>기록하지 않는다</b> — 판번호를 모르는
 * 프레임을 편집 목록에 넣으면 전수 검증의 근거가 없어진다(서버도 404 로 거부한다).
 *
 * @param dscdYn 그 프레임의 <b>현재</b> 폐기여부. 회차 값과 다르면 그것만으로도 편집이다.
 */
export function captureFrameIntoDraft(
  draft: LoadedVersionDraft,
  srcSn: number,
  labels: Label[],
  dscdYn?: 'Y' | 'N' | null,
): LoadedVersionDraft {
  const frame = draft.frames.find((f) => f.srcSn === srcSn);
  if (!frame) return draft;

  const current = labels.map((l) => serializeLabel(l, { includeTrackId: true }) as VersionLabelItem);
  const nextDscdYn = dscdYn ?? frame.dscdYn;
  const labelsChanged = !sameItems(frame.items, current);
  // ⚠ 폐기 토글도 편집이다 — 본문이 그대로여도 이 축만 바뀌면 edits 에 실려야 한다.
  const discardChanged = nextDscdYn !== frame.dscdYn;

  if (!labelsChanged && !discardChanged) {
    // 고친 것이 없으면 편집 기록을 <b>지운다</b>(되돌려 놓은 프레임이 계속 실리지 않게).
    if (draft.editedBy[srcSn] === undefined) return draft;
    const rest = { ...draft.editedBy };
    delete rest[srcSn];
    return { ...draft, editedBy: rest };
  }
  return {
    ...draft,
    editedBy: {
      ...draft.editedBy,
      [srcSn]: { srcSn, items: current, dscdYn: nextDscdYn },
    },
  };
}

/**
 * 저장에 실리는 필드만으로 두 라벨 목록이 같은지 본다.
 *
 * <p>회차 본문(`VersionLabelItem`)에는 표시용·AI 메타가 섞여 있고 캔버스 직렬화 결과에는 없으므로,
 * 두 형태를 <b>같은 축으로 좁혀</b> 비교해야 "고치지 않았는데 고쳤다"는 오탐이 나지 않는다.
 * 좌표는 부동소수 표현 차이를 피해 숫자 배열 그대로 비교한다.
 */
function sameItems(loaded: VersionLabelItem[], current: VersionLabelItem[]): boolean {
  if (loaded.length !== current.length) return false;
  const key = (i: VersionLabelItem) =>
    JSON.stringify([
      i.id ?? null,
      i.lblTypeCd,
      i.labelId ?? null,
      i.label ?? '',
      i.trackId ?? null,
      i.points,
    ]);
  const a = loaded.map(key).sort();
  const b = current.map(key).sort();
  return a.every((v, idx) => v === b[idx]);
}

/**
 * 확정 저장(API-196) 요청 본문을 만든다.
 *
 * <p><b>전 프레임 판번호</b>는 항상 보낸다(전수 검증의 입력이고, 전 프레임을 덮지 않으면 서버가 400 이다).
 * <b>본문은 고친 프레임만</b> 보낸다 — 나머지는 서버가 회차 스냅샷에서 직접 읽는다.
 *
 * @param currentSrcSn 지금 캔버스에 있는 프레임(저장 직전 편집분을 반영하기 위해 함께 판정한다)
 */
export function toVideoSavePayload(
  draft: LoadedVersionDraft,
  currentSrcSn: number | undefined,
  currentLabels: Label[],
  currentDscdYn?: 'Y' | 'N' | null,
): VideoLabelSavePayload {
  const merged =
    currentSrcSn === undefined
      ? draft
      : captureFrameIntoDraft(draft, currentSrcSn, currentLabels, currentDscdYn);
  return {
    loadedVersion: merged.version,
    frameVersions: merged.frames.map((f) => ({ srcSn: f.srcSn, lblVer: f.lblVer })),
    edits: Object.values(merged.editedBy),
  };
}

/** 해석하지 못한(그 회차 이하 스냅샷이 없어 현재 작업본이 실려 온) 프레임 수. */
export function unresolvedFrameCount(draft: LoadedVersionDraft | null): number {
  return (draft?.frames ?? []).filter((f) => !f.resolved).length;
}

/** 사람이 고친 프레임 수 — 화면이 "저장할 것이 있는가"를 판단하는 축. */
export function editedFrameCount(draft: LoadedVersionDraft | null): number {
  return draft === null ? 0 : Object.keys(draft.editedBy).length;
}
