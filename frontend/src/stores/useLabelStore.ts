import { create } from 'zustand';

import type { LabelChangeView, LabelSnapshotView } from '@/features/label/api';
import { clampPan as clampPanByScale } from '@/features/label/canvas/utils/canvasGeometry';
import type { Label, Shape, ToolType } from '@/features/label/types';
import { ToolType as ToolTypeEnum } from '@/features/label/types';
import { mergeDetections, DEDUP_IOU_THRESHOLD } from '@/features/label/utils/labelDedup';

interface UndoSnapshot {
  labels: Label[];
}

/**
 * 복사/붙여넣기 클립보드 (Phase 4, 세션 전용 — 영속 안 함).
 * - labels: 복사 시점의 라벨 딥클론 스냅샷
 * - sourceRawSn: 복사 원본 영상(videoId). 크로스영상 붙여넣기 판정용.
 *   null 이면 원본 영상 미상 → 안전하게 trackId 를 이관하지 않는다.
 */
export interface ClipboardEntry {
  labels: Label[];
  sourceRawSn: number | null;
}

/** 붙여넣기 시 완전동일 좌표 충돌을 피하기 위한 offset(px). */
export const PASTE_OFFSET = 10;

let pasteSeq = 0;

function nextPasteId(): string {
  pasteSeq += 1;
  return `paste-${Date.now().toString(36)}-${pasteSeq}`;
}

let autolabelSeq = 0;

/**
 * 오토라벨/추적 병합 라벨의 클라이언트 임시 ID 생성(HIGH #1).
 * BE 는 미저장이라 lblSn=null 을 주므로 id='' 충돌이 발생한다 — 여기서 유니크한 클라 id 를 부여한다.
 * serverId(BE PK)는 별도 필드로만 보존하며, 미저장이므로 undefined 로 둔다.
 */
function nextAutolabelId(): string {
  autolabelSeq += 1;
  return `autolabel-${Date.now().toString(36)}-${autolabelSeq}`;
}

let revertSeq = 0;

/**
 * "이 저장 되돌리기"에서 DELETED 를 되살릴 때(신규 삽입) 부여하는 클라이언트 임시 ID.
 * 원 lblSn 은 이미 삭제됐으므로 serverId 없이 새 라벨로 추가한다 → 저장 시 BE INSERT.
 */
function nextRevertId(): string {
  revertSeq += 1;
  return `revert-${Date.now().toString(36)}-${revertSeq}`;
}

/** revertSaveEvent 결과 요약 — 호출측 토스트 분기용. */
export interface RevertResult {
  /** 실제 작업본에 역적용된 변경 항목 수. */
  reverted: number;
  /** 대상 라벨 부재/좌표복원 불가(SEGMENT 등)로 건너뛴 항목 수. */
  skipped: number;
}

/** shape 좌표를 (dx, dy) 만큼 평행이동한 새 shape 반환(불변). */
function offsetShape(shape: Shape, dx: number, dy: number): Shape {
  if (shape.type === 'BBOX') {
    return {
      type: 'BBOX',
      left: shape.left + dx,
      top: shape.top + dy,
      right: shape.right + dx,
      bottom: shape.bottom + dy,
    };
  }
  if (shape.type === 'POLYGON') {
    return {
      type: 'POLYGON',
      points: shape.points.map((v, i) => (i % 2 === 0 ? v + dx : v + dy)),
    };
  }
  if (shape.type === 'KEYPOINT') {
    return {
      type: 'KEYPOINT',
      keypoints: shape.keypoints.map((kp) => ({ ...kp, x: kp.x + dx, y: kp.y + dy })),
    };
  }
  return { ...shape };
}

/** 좌표를 이미지 경계 [0, w]/[0, h] 로 clamp 한 새 shape 반환(불변). w/h 미지정 시 원본 유지. */
function clampShape(shape: Shape, w?: number, h?: number): Shape {
  const cx = (v: number) => (w != null ? Math.min(Math.max(v, 0), w) : Math.max(v, 0));
  const cy = (v: number) => (h != null ? Math.min(Math.max(v, 0), h) : Math.max(v, 0));
  if (shape.type === 'BBOX') {
    return {
      type: 'BBOX',
      left: cx(shape.left),
      top: cy(shape.top),
      right: cx(shape.right),
      bottom: cy(shape.bottom),
    };
  }
  if (shape.type === 'POLYGON') {
    return {
      type: 'POLYGON',
      points: shape.points.map((v, i) => (i % 2 === 0 ? cx(v) : cy(v))),
    };
  }
  if (shape.type === 'KEYPOINT') {
    return {
      type: 'KEYPOINT',
      keypoints: shape.keypoints.map((kp) => ({ ...kp, x: cx(kp.x), y: cy(kp.y) })),
    };
  }
  return { ...shape };
}

/** 두 shape 의 좌표가 완전 동일한지 비교(붙여넣기 offset 판정용). */
function shapeEquals(a: Shape, b: Shape): boolean {
  if (a.type !== b.type) return false;
  if (a.type === 'BBOX' && b.type === 'BBOX') {
    return a.left === b.left && a.top === b.top && a.right === b.right && a.bottom === b.bottom;
  }
  if (a.type === 'POLYGON' && b.type === 'POLYGON') {
    return a.points.length === b.points.length && a.points.every((v, i) => v === b.points[i]);
  }
  if (a.type === 'KEYPOINT' && b.type === 'KEYPOINT') {
    return (
      a.keypoints.length === b.keypoints.length &&
      a.keypoints.every((kp, i) => kp.x === b.keypoints[i].x && kp.y === b.keypoints[i].y && kp.v === b.keypoints[i].v)
    );
  }
  return false;
}

/**
 * 이미지 조절(밝기/대비/투명도) 세션 상태 (Phase 2c).
 * 영속 계층 없음 — zustand 인메모리라 새 세션/reset 시 원본값 복원.
 * - brightness: konva Brighten 필터 값 (-1..1, 0=원본)
 * - contrast: konva Contrast 필터 값 (-100..100, 0=원본)
 * - labelOpacity: 라벨 레이어 불투명도 (0..1)
 * - activeOpacity: 작업(오버레이) 레이어 불투명도 (0..1)
 */
export interface ImageAdjust {
  brightness: number;
  contrast: number;
  labelOpacity: number;
  activeOpacity: number;
}

export const DEFAULT_IMAGE_ADJUST: ImageAdjust = {
  brightness: 0,
  contrast: 0,
  labelOpacity: 1,
  activeOpacity: 1,
};

/**
 * 라벨링 화면의 장시간 작업 종류. 사용자 노출 문구는 화면 계층에서 매핑한다(모델명 미노출).
 */
export type BusyKind =
  | 'AI_DETECT'
  | 'AI_SEGMENT'
  | 'AI_TRACK'
  // 온디맨드 자동 추적 — 시작 객체를 고르지 않고 프레임 구간만으로 실행하는 경로다.
  // AI_TRACK(선택한 객체 하나를 따라가는 추적)과 **별개 종류**로 둔다: 진행·취소 안내 문구가
  // 그 이름에서 파생되므로 같은 종류로 묶으면 화면이 두 실행을 같은 이름으로 부른다.
  | 'AI_AUTO_TRACK'
  | 'SAVE'
  | 'LOAD';

/**
 * 진행 중인 장시간 작업 1건. 라벨링 화면의 "진행 중" 판정은 이 값 하나가 단일 진실원이다
 * (훅 로컬 state·inflight ref 를 두지 않는다 — 두 축이 생기면 취소가 한쪽만 풀어 유령 잠금이 된다).
 */
export interface BusyState {
  kind: BusyKind;
  /**
   * 어느 프레임을 위한 busy 인가. 프레임 전환 시 이전 프레임 busy 가 새 화면을 잠그지 않게 한다.
   * ⚠ 판정에 쓰이지 않는 컨텍스트 필드(영상 ID 등)는 두지 않는다 — 아무도 보지 않는 값이
   *   "영상 경계까지 판정한다"는 착각을 만든다(죽은 필드 금지).
   */
  srcSn?: number;
  startedAt: number;
  /** 이 작업의 세대 토큰. 취소/정상종료 시 죽으며, 죽은 토큰의 결과는 폐기한다. */
  token: number;
}

/**
 * beginBusy 결과. 판별 유니온이라 `if (!token)` 같은 falsy 비교가 불가능하다
 * — 세션 첫 토큰이 0 이어서 정상 시작을 "거부"로 오인하던 함정을 타입으로 차단한다.
 */
export type BeginBusyResult = { ok: false } | { ok: true; token: number };

/** busy 가 지금 화면(srcSn)에 적용되는지 — 프레임 컨텍스트가 없으면 현재 화면 것으로 본다. */
function busyAppliesTo(busy: BusyState, srcSn?: number): boolean {
  if (busy.srcSn === undefined || srcSn === undefined) return true;
  return busy.srcSn === srcSn;
}

interface LabelState {
  // 도구/선택
  activeTool: ToolType;
  selectedLabelId: string | null;

  /**
   * Phase 7: 라벨 사이드바에서 선택된 활성 라벨 마스터 ID.
   * 신규 BBOX/Polygon 그리기 시 이 라벨의 classId/className 이 자동 적용된다.
   * null 이면 OverlayLayer 가 sortNo 최소 활성 라벨로 fallback.
   */
  activeLabelId: number | null;

  // 라벨 데이터 (현재 프레임)
  labels: Label[];
  /** 변경 후 저장 전 dirty 플래그 (라벨 ID 집합) */
  dirtyLabels: Set<string>;

  // 캔버스 상태
  zoom: number;
  panX: number;
  panY: number;

  // Undo/Redo
  undoStack: UndoSnapshot[];
  redoStack: UndoSnapshot[];

  // 이미지 조절 (세션 전용 — 영속 안 함)
  imageAdjust: ImageAdjust;

  /**
   * 가시성 숨김 라벨 ID 집합 (세션 전용 — 영속 안 함, Phase 2 T 표시/숨김).
   * 여기 든 라벨은 LabelsLayer 렌더에서 skip. setLabels/reset 시 초기화.
   */
  hiddenLabelIds: Set<string>;

  /**
   * 편집 잠금 라벨 ID 집합 (세션 전용 — 영속 안 함, Phase 3 R6 개별 잠금).
   * 여기 든 라벨은 선택/이동/리사이즈/꼭짓점 편집이 차단되며 updateLabel/removeLabel 도
   * no-op(dirty/undo 미변화). setLabels/reset 시 초기화.
   */
  lockedLabelIds: Set<string>;

  /**
   * 복사/붙여넣기 클립보드 (Phase 4, 세션 전용). 프레임/영상 이동(setLabels/reset)에도
   * 유지되어 크로스프레임·크로스영상 붙여넣기를 지원한다. 명시적 copy 시에만 교체.
   */
  clipboard: ClipboardEntry | null;

  /**
   * R12 — 미래 프레임 추적 결과 보류 캐시 (srcSn → Label[]).
   * SAM2 자동추적 결과의 tracked[].srcSn 은 현재가 아닌 후속(미래) 프레임 값이라, 현재 프레임
   * 작업본에 즉시 병합할 수 없다. 여기 srcSn 별로 stash 해 두고, 해당 프레임에 진입할 때
   * drain 하여 그 프레임 작업본에 dedup 병합한다(사일런트 데이터 유실 방지).
   * 프레임 전환(setLabels)에는 보존되고, reset(언마운트/영상 변경) 시에만 초기화된다.
   */
  pendingTracks: Record<number, Label[]>;

  /**
   * 진행 중인 장시간 작업(AI 탐지/분할/추적, 저장/불러오기). null 이면 유휴.
   * 동시에 1건만 존재한다(배타 실행).
   */
  busy: BusyState | null;

  /**
   * 세대 카운터(단조 증가). beginBusy 가 현재 값을 토큰으로 발급하고, 취소·정상종료마다 증가한다.
   * 세션 첫 토큰은 0 이다 — 호출측은 반드시 BeginBusyResult.ok 로 성공을 판정해야 한다.
   */
  busyGeneration: number;

  // Actions
  setActiveTool: (tool: ToolType) => void;
  selectLabel: (id: string | null) => void;
  setActiveLabelId: (id: number | null) => void;
  setLabels: (labels: Label[]) => void;
  addLabel: (label: Label) => void;
  updateLabel: (id: string, patch: Partial<Label>) => void;
  removeLabel: (id: string) => void;
  setZoom: (zoom: number) => void;
  setPan: (x: number, y: number) => void;
  /** 프레임 전환/초기 진입 시 뷰를 fit(zoom=1)·중앙(pan=0)으로 되돌린다(라벨/undo 는 보존). */
  resetView: () => void;
  undo: () => void;
  redo: () => void;
  clearDirty: () => void;
  setImageAdjust: (patch: Partial<ImageAdjust>) => void;
  resetImageAdjust: () => void;
  toggleLabelVisibility: (id: string) => void;
  toggleLabelLock: (id: string) => void;

  /**
   * 라벨 복사 — 선택 라벨(onlySelected=true, 선택 없으면 전체) 또는 전체를 클립보드에 딥클론 저장.
   * @returns 복사된 라벨 수 (0 이면 no-op — 호출측 토스트)
   */
  copyLabels: (opts?: { onlySelected?: boolean; sourceRawSn?: number | null }) => number;

  /**
   * 클립보드 라벨을 현재 프레임에 붙여넣기.
   *  - 빈 클립보드 → 0 (no-op)
   *  - 크로스영상(sourceRawSn 불일치) → trackId/serverId 제거, 좌표/라벨만 이관
   *  - 완전동일 좌표가 현재 프레임에 있으면 +PASTE_OFFSET 이동 후 이미지 경계 clamp
   * @returns 붙여넣은 라벨 수
   */
  pasteLabels: (opts: {
    frameNo: number;
    sourceRawSn?: number | null;
    imageWidth?: number;
    imageHeight?: number;
  }) => number;

  /**
   * 오토라벨/추적 검출 결과를 현재 작업본에 병합(HIGH #1/#3/#4, MED #6).
   *  - 기존 라벨을 보존한 채 detected 만 추가(refetch/전체교체 아님).
   *  - 같은 클래스 + IoU≥임계값 중복은 mergeDetections 로 스킵.
   *  - 병합 대상 각 라벨에 클라이언트 임시 id 부여(id 충돌 방지), serverId 는 미저장(undefined).
   *  - 병합 전 1회만 undo 스냅샷 push(개별 addLabel 루프 금지), 병합 id 를 dirtyLabels 에 일괄 추가.
   * @returns 실제 병합된 라벨 수(중복 전부 스킵 시 0 — 이때 undo/dirty 변화 없음).
   */
  mergeAutoLabels: (labels: Label[]) => number;

  /**
   * "이 저장 되돌리기" — 저장 이벤트의 changes[] 를 현재 작업본에 역적용(즉시 DB 저장 아님).
   *  - UPDATED : 대상 라벨(serverId===lblSn)을 before 상태(좌표/타입/labelId/라벨명)로 복원.
   *  - ADDED   : 대상 라벨(serverId===lblSn)을 작업본에서 제거.
   *  - DELETED : before 내용을 신규 라벨로 추가(클라 임시 id, serverId 미저장 → 저장 시 INSERT).
   *  - 현재 작업본에 대상이 없거나 좌표복원 불가(SEGMENT 등)면 해당 항목만 스킵(skipped 증가).
   *  - 실제 역적용이 1건 이상일 때만 단일 undo 스냅샷 1회 push + dirty 일괄 표시(0건이면 no-op).
   * @param changes 저장 이벤트의 라벨 단위 변경 목록(BE LabelChangeView[]).
   * @param frameNo 현재 프레임 번호(복원/신규 라벨에 부여).
   * @param toLabel before/after 스냅샷 → FE Label 변환기(api.snapshotToLabel). 복원 불가 시 null 반환.
   */
  revertSaveEvent: (
    changes: LabelChangeView[],
    frameNo: number,
    toLabel: (snap: LabelSnapshotView | null, frameNo: number) => Label | null,
  ) => RevertResult;

  /**
   * R12 — 미래 프레임 추적 결과를 srcSn 별로 보류 캐시에 stash(누적).
   *  - 같은 srcSn 은 덮어쓰지 않고 뒤에 누적한다(여러 번 추적 시 합산).
   *  - 각 보류 라벨에 유니크 클라이언트 임시 id 부여(id 충돌 방지), serverId 는 미저장(undefined).
   *  - 불변 — 기존 pendingTracks/배열을 변형하지 않고 새 객체·배열 생성.
   */
  stashPendingTracks: (bySrcSn: Record<number, Label[]>) => void;

  /**
   * R12 — 지정 프레임(srcSn)의 보류 추적 라벨을 반환하고 보류 캐시에서 제거.
   *  - 보류가 없으면 빈 배열 반환 + 캐시 불변(no-op).
   *  - 반환한 라벨은 호출측이 mergeAutoLabels 로 dedup 병합한다(기존 라벨 보존).
   */
  drainPendingTracks: (srcSn: number) => Label[];

  /**
   * 장시간 작업 시작 선점. 이미 다른 작업이 진행 중이면 `{ ok: false }` 로 거부한다(배타 실행).
   * 성공 시 발급한 토큰은 결과 반영 여부의 최종 게이트(isTokenAlive)로 쓴다.
   */
  beginBusy: (kind: BusyKind, ctx?: { srcSn?: number }) => BeginBusyResult;

  /**
   * 작업 종료. **토큰이 현재 busy 와 일치할 때만** 해제한다(멱등).
   * 취소·자동해제로 이미 죽은 토큰이 뒤늦게 종료를 알려도 새 작업을 끊지 않는다.
   */
  endBusy: (token: number) => void;

  /**
   * 진행 중 작업 취소. 세대를 올려 기존 토큰을 죽이고 즉시 해제한다(재실행 가능).
   * busy 가 없으면 no-op. 네트워크 자체는 중단하지 않으며 도착한 결과를 폐기하는 방식이다.
   */
  cancelBusy: () => void;

  /** 결과 반영 여부의 최종 게이트 — 취소·리셋·프레임 전환·자동해제 이후면 false. */
  isTokenAlive: (token: number) => boolean;

  reset: () => void;
}

// fit(zoom=1) 을 최소 배율 바닥으로 둔다. zoom<1 이면 이미지가 fit 아래로 축소돼
// 캔버스 사방에 어두운 여백이 생기므로(버그) 허용하지 않는다. zoom=1 이 곧 한 축을
// 꽉 채우는 fit 상태이며, 그 이상(줌인)만 허용한다.
export const MIN_ZOOM = 1.0;
export const MAX_ZOOM = 8;
const MAX_UNDO = 50;

export function clampZoom(z: number): number {
  return Math.min(Math.max(z, MIN_ZOOM), MAX_ZOOM);
}

/**
 * 팬(panX/panY) 클램프 — 확대(zoom>1)된 이미지가 뷰포트 밖으로 완전히 이탈하지 않도록 제한.
 * fit(zoom=1)에서는 (0,0) 유지(no-op). 렌더 geometry 의 clampPan(scale 기반, canvasGeometry)과
 * 동일 규칙을 재사용해 store pan 과 캔버스 배치가 항상 일치하도록 한다(좌표계 정합 유지).
 * @param view  캔버스(Stage) CSS 픽셀 크기
 * @param image 로드된 이미지 실측 네이티브 픽셀 크기
 */
export function clampPan(
  panX: number,
  panY: number,
  zoom: number,
  view: { w: number; h: number },
  image: { w: number; h: number },
): { x: number; y: number } {
  if (view.w <= 0 || view.h <= 0 || image.w <= 0 || image.h <= 0) {
    return { x: 0, y: 0 };
  }
  const fitScale = Math.min(view.w / image.w, view.h / image.h);
  const scale = fitScale * zoom;
  const { panX: cx, panY: cy } = clampPanByScale(
    { width: image.w, height: image.h },
    { width: view.w, height: view.h },
    scale,
    panX,
    panY,
  );
  return { x: cx, y: cy };
}

/**
 * 프레임 전환 시 뷰(zoom/pan) 리셋(fit) 여부 판정 — 순수 함수(부작용 없음).
 *  - 이전 프레임 dims 미상(초기 진입)  → true(fit)
 *  - 영상 변경(videoId 상이)          → true(fit)
 *  - 이미지 해상도(width/height) 상이  → true(fit)
 *  - 동일 영상 + 동일 해상도            → false(뷰 유지)
 */
export function shouldResetView(
  prev: { videoId?: number; width: number; height: number } | null | undefined,
  next: { videoId?: number; width: number; height: number },
): boolean {
  if (!prev) return true;
  if (prev.videoId !== next.videoId) return true;
  return prev.width !== next.width || prev.height !== next.height;
}

/**
 * shape 딥클론 — 중첩 배열(POLYGON.points / KEYPOINT.keypoints)까지 새로 생성.
 * 얕은 복사({...shape})면 배열 참조가 스냅샷과 공유돼 in-place 변형 시 undo/redo 가 오염된다.
 */
function cloneShape(shape: Shape): Shape {
  if (shape.type === 'KEYPOINT') {
    return { type: 'KEYPOINT', keypoints: shape.keypoints.map((kp) => ({ ...kp })) };
  }
  if (shape.type === 'POLYGON') {
    return { type: 'POLYGON', points: [...shape.points] };
  }
  return { ...shape };
}

function snapshot(labels: Label[]): UndoSnapshot {
  return { labels: labels.map((l) => ({ ...l, shape: cloneShape(l.shape) })) };
}

/**
 * busy 취소 patch — cancelBusy 와 reset 이 **같은 로직을 공유**하도록 분리한다.
 * reset 이 busy 를 초기화 목록에서 빠뜨리면(zustand set(partial) 은 명시 필드만 병합) 살아남은
 * 토큰이 리셋된 프레임에 옛 결과를 부활시킨다.
 */
function cancelBusyPatch(state: Pick<LabelState, 'busy' | 'busyGeneration'>): {
  busy: null;
  busyGeneration: number;
} {
  return {
    busy: null,
    // 진행 중이던 작업이 있을 때만 세대를 올린다(무의미한 증가 방지).
    busyGeneration: state.busy === null ? state.busyGeneration : state.busyGeneration + 1,
  };
}

export const useLabelStore = create<LabelState>((set, get) => ({
  activeTool: ToolTypeEnum.SELECT,
  selectedLabelId: null,
  activeLabelId: null,
  labels: [],
  dirtyLabels: new Set<string>(),
  zoom: 1,
  panX: 0,
  panY: 0,
  undoStack: [],
  redoStack: [],
  imageAdjust: { ...DEFAULT_IMAGE_ADJUST },
  hiddenLabelIds: new Set<string>(),
  lockedLabelIds: new Set<string>(),
  clipboard: null,
  pendingTracks: {},
  busy: null,
  busyGeneration: 0,

  setActiveTool: (tool) => set({ activeTool: tool }),
  // 잠금 라벨은 어떤 UI 진입점(캔버스/트리)에서도 선택 불가 — 불변식 일관 강제.
  // 선택 해제(id=null)는 항상 허용. lock 토글은 selectLabel 을 거치지 않아 영향 없음.
  selectLabel: (id) => {
    if (id != null && get().lockedLabelIds.has(id)) return;
    set({ selectedLabelId: id });
  },
  setActiveLabelId: (id) => set({ activeLabelId: id }),

  setLabels: (labels) =>
    set({
      labels,
      dirtyLabels: new Set<string>(),
      undoStack: [],
      redoStack: [],
      selectedLabelId: null,
      hiddenLabelIds: new Set<string>(),
      lockedLabelIds: new Set<string>(),
    }),

  addLabel: (label) => {
    const prev = get().labels;
    const dirty = new Set(get().dirtyLabels);
    dirty.add(label.id);
    const undoStack = [...get().undoStack, snapshot(prev)].slice(-MAX_UNDO);
    set({ labels: [...prev, label], dirtyLabels: dirty, undoStack, redoStack: [] });
  },

  updateLabel: (id, patch) => {
    // 잠금 라벨은 편집 차단 — dirty/undo 도 쌓이지 않도록 진입부에서 no-op.
    if (get().lockedLabelIds.has(id)) return;
    const prev = get().labels;
    const dirty = new Set(get().dirtyLabels);
    dirty.add(id);
    const undoStack = [...get().undoStack, snapshot(prev)].slice(-MAX_UNDO);
    const next = prev.map((l) => (l.id === id ? { ...l, ...patch } : l));
    set({ labels: next, dirtyLabels: dirty, undoStack, redoStack: [] });
  },

  removeLabel: (id) => {
    // 잠금 라벨은 삭제 차단 — dirty/undo 미변화(no-op).
    if (get().lockedLabelIds.has(id)) return;
    const prev = get().labels;
    const dirty = new Set(get().dirtyLabels);
    dirty.add(id);
    const undoStack = [...get().undoStack, snapshot(prev)].slice(-MAX_UNDO);
    const next = prev.filter((l) => l.id !== id);
    set({
      labels: next,
      dirtyLabels: dirty,
      undoStack,
      redoStack: [],
      selectedLabelId: get().selectedLabelId === id ? null : get().selectedLabelId,
    });
  },

  setZoom: (zoom) => set({ zoom: clampZoom(zoom) }),
  setPan: (x, y) => set({ panX: x, panY: y }),
  resetView: () => set({ zoom: 1, panX: 0, panY: 0 }),

  undo: () => {
    const { undoStack, labels, redoStack } = get();
    if (undoStack.length === 0) return;
    const prev = undoStack[undoStack.length - 1];
    set({
      labels: prev.labels,
      undoStack: undoStack.slice(0, -1),
      redoStack: [...redoStack, snapshot(labels)].slice(-MAX_UNDO),
    });
  },

  redo: () => {
    const { redoStack, labels, undoStack } = get();
    if (redoStack.length === 0) return;
    const next = redoStack[redoStack.length - 1];
    set({
      labels: next.labels,
      redoStack: redoStack.slice(0, -1),
      undoStack: [...undoStack, snapshot(labels)].slice(-MAX_UNDO),
    });
  },

  clearDirty: () => set({ dirtyLabels: new Set<string>() }),

  setImageAdjust: (patch) =>
    set({ imageAdjust: { ...get().imageAdjust, ...patch } }),

  resetImageAdjust: () => set({ imageAdjust: { ...DEFAULT_IMAGE_ADJUST } }),

  // 불변성 유지 — 기존 Set 을 변형하지 않고 새 Set 을 생성해 구독자 재렌더 보장.
  toggleLabelVisibility: (id) => {
    const next = new Set(get().hiddenLabelIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    set({ hiddenLabelIds: next });
  },

  // 불변성 유지 — toggleLabelVisibility 와 동일하게 새 Set 생성(기존 Set 미변형).
  toggleLabelLock: (id) => {
    const next = new Set(get().lockedLabelIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    set({ lockedLabelIds: next });
  },

  copyLabels: (opts) => {
    const { labels, selectedLabelId } = get();
    const onlySelected = opts?.onlySelected ?? false;
    const picked =
      onlySelected && selectedLabelId
        ? labels.filter((l) => l.id === selectedLabelId)
        : labels;
    if (picked.length === 0) {
      return 0; // 빈 선택/빈 프레임 — no-op (호출측 토스트)
    }
    // 딥클론 — 이후 캔버스 편집이 클립보드 스냅샷을 오염시키지 않도록 shape 까지 새로 생성.
    const snap = picked.map((l) => ({ ...l, shape: cloneShape(l.shape) }));
    set({ clipboard: { labels: snap, sourceRawSn: opts?.sourceRawSn ?? null } });
    return snap.length;
  },

  pasteLabels: (opts) => {
    const clip = get().clipboard;
    if (!clip || clip.labels.length === 0) {
      return 0; // 빈 클립보드 — no-op (호출측 토스트)
    }
    const prev = get().labels;
    // 크로스영상: 원본 영상과 현재 영상이 다르면 trackId/serverId 제거(좌표·라벨만 이관).
    const crossVideo =
      clip.sourceRawSn != null &&
      opts.sourceRawSn != null &&
      clip.sourceRawSn !== opts.sourceRawSn;

    const pasted: Label[] = clip.labels.map((src) => {
      // 완전동일 좌표가 현재 프레임에 이미 있으면 +offset 후 경계 clamp.
      const duplicate = prev.some((p) => shapeEquals(p.shape, src.shape));
      const shifted = duplicate ? offsetShape(src.shape, PASTE_OFFSET, PASTE_OFFSET) : src.shape;
      const shape = clampShape(shifted, opts.imageWidth, opts.imageHeight);
      const base: Label = {
        ...src,
        id: nextPasteId(),
        serverId: undefined, // 신규 라벨 — BE INSERT 대상
        frameNo: opts.frameNo,
        shape,
      };
      if (crossVideo) {
        base.trackId = null; // 영상별 트랙 연속성 없음 — 이관 금지
      }
      return base;
    });

    const dirty = new Set(get().dirtyLabels);
    pasted.forEach((l) => dirty.add(l.id));
    // 붙여넣기 전체를 단일 undo 스냅샷으로 — 한 번의 undo 로 통째 취소.
    const undoStack = [...get().undoStack, snapshot(prev)].slice(-MAX_UNDO);
    set({ labels: [...prev, ...pasted], dirtyLabels: dirty, undoStack, redoStack: [] });
    return pasted.length;
  },

  mergeAutoLabels: (incoming) => {
    const prev = get().labels;
    // 중복 제거 — 기존 라벨/검출세트 내부 중복을 같은 클래스 + IoU 로 스킵.
    const kept = mergeDetections(prev, incoming, DEDUP_IOU_THRESHOLD);
    if (kept.length === 0) return 0; // 전부 중복 — undo/dirty 변화 없이 no-op.
    // 클라이언트 임시 id 부여 + serverId 제거(미저장). 불변 — 새 객체 생성.
    const merged: Label[] = kept.map((l) => ({
      ...l,
      id: nextAutolabelId(),
      serverId: undefined,
    }));
    const dirty = new Set(get().dirtyLabels);
    merged.forEach((l) => dirty.add(l.id));
    // 병합 전체를 단일 undo 스냅샷으로 — 한 번의 undo 로 통째 취소(개별 addLabel 루프 금지).
    const undoStack = [...get().undoStack, snapshot(prev)].slice(-MAX_UNDO);
    set({ labels: [...prev, ...merged], dirtyLabels: dirty, undoStack, redoStack: [] });
    return merged.length;
  },

  revertSaveEvent: (changes, frameNo, toLabel) => {
    const prev = get().labels;
    // 불변 — 원본을 변형하지 않고 작업 사본에 역적용한 뒤 한 번에 커밋한다.
    let next = [...prev];
    const dirtyIds: string[] = [];
    let reverted = 0;
    let skipped = 0;

    for (const change of changes) {
      if (change.changeKind === 'UPDATED') {
        // before 상태로 복원 — 대상은 현재 작업본의 serverId===lblSn 라벨.
        if (change.lblSn == null) {
          skipped += 1;
          continue;
        }
        const idx = next.findIndex((l) => l.serverId === change.lblSn);
        const restored = toLabel(change.before, frameNo);
        if (idx < 0 || !restored) {
          skipped += 1; // 작업본에 없음 or 좌표복원 불가(SEGMENT 등).
          continue;
        }
        const target = next[idx];
        next = next.map((l, i) =>
          i === idx
            ? {
                ...l,
                shape: restored.shape,
                labelId: restored.labelId,
                classId: restored.classId,
                className: restored.className,
                // color 도 before(restored) 값으로 정합 — getLabelDisplayColor 는
                // label.color(hex) 를 최우선 참조하므로, labelId 만 되돌리고 기존 color 를
                // 유지하면 stale 색이 남는다. restored.color(스냅샷엔 color 없음 → null)로
                // 덮어써 색을 클리어하면 렌더가 복원된 labelId 로 마스터 색을 재파생한다.
                color: restored.color,
              }
            : l,
        );
        dirtyIds.push(target.id);
        reverted += 1;
      } else if (change.changeKind === 'ADDED') {
        // 추가됐던 라벨을 작업본에서 제거.
        if (change.lblSn == null) {
          skipped += 1;
          continue;
        }
        const idx = next.findIndex((l) => l.serverId === change.lblSn);
        if (idx < 0) {
          skipped += 1;
          continue;
        }
        dirtyIds.push(next[idx].id);
        next = next.filter((_, i) => i !== idx);
        reverted += 1;
      } else {
        // DELETED — before 내용을 신규 라벨로 되살린다(serverId 미저장 → 저장 시 INSERT).
        const restored = toLabel(change.before, frameNo);
        if (!restored) {
          skipped += 1; // 좌표복원 불가(SEGMENT 등).
          continue;
        }
        const id = nextRevertId();
        next = [...next, { ...restored, id, serverId: undefined, frameNo }];
        dirtyIds.push(id);
        reverted += 1;
      }
    }

    if (reverted === 0) {
      // 역적용 0건 — undo/dirty 변화 없이 no-op(호출측이 안내 토스트).
      return { reverted: 0, skipped };
    }

    const dirty = new Set(get().dirtyLabels);
    dirtyIds.forEach((id) => dirty.add(id));
    // 되돌리기 배치 전체를 단일 undo 스냅샷으로 — 한 번의 undo 로 통째 취소.
    const undoStack = [...get().undoStack, snapshot(prev)].slice(-MAX_UNDO);
    set({ labels: next, dirtyLabels: dirty, undoStack, redoStack: [] });
    return { reverted, skipped };
  },

  stashPendingTracks: (bySrcSn) => {
    const prev = get().pendingTracks;
    const next: Record<number, Label[]> = { ...prev };
    for (const [key, list] of Object.entries(bySrcSn)) {
      const srcSn = Number(key);
      if (!Array.isArray(list) || list.length === 0) continue;
      // 유니크 클라 id 부여 + serverId 제거(미저장). 불변 — 새 객체 생성.
      const withIds = list.map((l) => ({ ...l, id: nextAutolabelId(), serverId: undefined }));
      next[srcSn] = [...(next[srcSn] ?? []), ...withIds];
    }
    set({ pendingTracks: next });
  },

  drainPendingTracks: (srcSn) => {
    const prev = get().pendingTracks;
    const drained = prev[srcSn] ?? [];
    if (drained.length === 0) return [];
    // 불변 — 해당 키만 제거한 새 객체 생성.
    const next: Record<number, Label[]> = { ...prev };
    delete next[srcSn];
    set({ pendingTracks: next });
    return drained;
  },

  beginBusy: (kind, ctx) => {
    if (get().busy !== null) return { ok: false }; // 배타 실행 — 진행 중이면 거부
    const token = get().busyGeneration;
    set({
      busy: {
        kind,
        srcSn: ctx?.srcSn,
        startedAt: Date.now(),
        token,
      },
    });
    return { ok: true, token };
  },

  endBusy: (token) => {
    const busy = get().busy;
    // 토큰 불일치(취소 후 뒤늦은 종료 통지 등)면 무시 — 남의 작업을 끊지 않는다.
    if (busy === null || busy.token !== token) return;
    set(cancelBusyPatch(get()));
  },

  cancelBusy: () => {
    if (get().busy === null) return; // no-op 안전
    set(cancelBusyPatch(get()));
  },

  isTokenAlive: (token) => {
    const busy = get().busy;
    return busy !== null && busy.token === token;
  },

  reset: () =>
    set({
      ...cancelBusyPatch(get()), // 진행 중 작업 취소 포함 — 리셋 후 도착 응답 폐기
      activeTool: ToolTypeEnum.SELECT,
      selectedLabelId: null,
      activeLabelId: null,
      labels: [],
      dirtyLabels: new Set<string>(),
      zoom: 1,
      panX: 0,
      panY: 0,
      undoStack: [],
      redoStack: [],
      imageAdjust: { ...DEFAULT_IMAGE_ADJUST },
      hiddenLabelIds: new Set<string>(),
      lockedLabelIds: new Set<string>(),
      pendingTracks: {},
    }),
}));

/**
 * 지정 종류의 작업이 이 화면(srcSn)에서 진행 중인지. 훅의 `isSegmenting`/`isAutolabeling` 같은
 * 공개 값은 전부 이 파생값에서 나온다(로컬 state 금지 — 단일 진실원 유지).
 */
export function useIsBusyKind(kind: BusyKind, srcSn?: number): boolean {
  return useLabelStore((s) => s.busy !== null && s.busy.kind === kind && busyAppliesTo(s.busy, srcSn));
}

/**
 * 편집 차단 판정(순수 술어) — 장시간 작업이 이 화면(srcSn)에서 진행 중이면 true.
 *
 * 훅(렌더 값)과 실시간 조회가 **같은 술어**를 공유하도록 분리한다. 컴포넌트마다 `busy !== null`
 * 같은 조건을 다시 세우면 판정원이 갈라져, 한쪽만 갱신됐을 때 조용히 열린 구멍이 생긴다.
 */
export function isEditBlockedState(state: Pick<LabelState, 'busy'>, srcSn?: number): boolean {
  return state.busy !== null && busyAppliesTo(state.busy, srcSn);
}

/**
 * 편집 차단 단일 판정원 — 장시간 작업이 이 화면에서 진행 중이면 true.
 * 차단 배선(캔버스/툴바/프레임 전환/단축키/패널)은 이 셀렉터 하나만 참조한다.
 */
export function useIsEditBlocked(srcSn?: number): boolean {
  return useLabelStore((s) => isEditBlockedState(s, srcSn));
}

/**
 * 편집 차단 **실시간** 판정 — 렌더 값이 아직 낡았을 수 있는 이벤트 핸들러 안에서 쓴다.
 * 렌더 사이(핸들러 실행 시점)에 시작된 busy 는 구독 값에 반영되기 전이므로, 차단은
 * `렌더 값 || 실시간 값`(둘 중 하나라도 참이면 막는다 = fail-closed)으로 판정한다.
 */
export function isEditBlockedNow(srcSn?: number): boolean {
  return isEditBlockedState(useLabelStore.getState(), srcSn);
}
