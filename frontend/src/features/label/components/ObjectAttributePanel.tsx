// SCR-LABEL-002 — 오브젝트 속성 패널 (Phase 6 완성).
//
// 보안: 좌표 입력은 image 경계로 clamp. classId/className 변경은 availableLabels 화이트리스트만 허용.

import { useMemo } from 'react';

import { useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from '../hooks/useLabelMasters';
import { Sam2TrackTool } from '../canvas/tools/Sam2TrackTool';
import { normalizeBox } from '../canvas/utils/canvasGeometry';
import { shouldRenderVertexAnchors } from '../canvas/utils/polygonEdit';
import type { DetectShapeType, Sam2TrackedItem } from '../api';
import type { Label } from '../types';
import { ToolType } from '../types';
import { resolveLabelDisplayName } from '../utils/labelDisplayName';

import { ObjectAttributeSection } from './ObjectAttributeSection';
import { TOLERANCE_DEFAULT, ToleranceSlider } from './PrecisionSliders';

/**
 * 선택 객체의 실제 형태 → 추적 결과 형태(DetectShapeType).
 * BBOX/POLYGON 만 매핑하고, 그 외(MASK/KEYPOINT 등)는 undefined 를 반환해
 * 호출측이 팝업 값(track.shape) 폴백을 쓰게 한다. 추적 결과 형태를 취약한 모달 라디오
 * 상태(track.shape)에 묶지 않고 "선택 객체가 박스면 박스, 폴리곤이면 폴리곤"으로 결정한다.
 */
function shapeToDetectType(shape: Label['shape']): DetectShapeType | undefined {
  if (shape.type === 'BBOX') return 'BBOX';
  if (shape.type === 'POLYGON') return 'POLYGON';
  return undefined;
}

/** 선택 라벨의 shape → SAM2 Track 시작 폴리곤([[x,y],...]). 박스는 4점 폐곡선으로 변환. */
function shapeToPolygon(shape: Label['shape']): number[][] | undefined {
  if (shape.type === 'BBOX') {
    return [
      [shape.left, shape.top],
      [shape.right, shape.top],
      [shape.right, shape.bottom],
      [shape.left, shape.bottom],
    ];
  }
  if (shape.type === 'POLYGON') {
    const out: number[][] = [];
    for (let i = 0; i + 1 < shape.points.length; i += 2) {
      out.push([shape.points[i], shape.points[i + 1]]);
    }
    return out.length >= 3 ? out : undefined;
  }
  return undefined;
}

export interface AvailableLabel {
  id: number;
  /** 라벨 마스터 등록명 — 화면 표시값이자 **저장되는 className 값**이다(사전 치환 없음). */
  name: string;
  color?: string;
}

export interface ObjectAttributePanelProps {
  labels: Label[];
  /**
   * 도메인 라벨 목록 (드롭다운 옵션) — 명시 시 useLabelMasters 호출을 우회.
   * Phase 8: 미제공 시 useLabelMasters() 응답을 자동 사용.
   */
  availableLabels?: AvailableLabel[];
  /**
   * 좌표 clamp용 이미지 실측 크기(네이티브 픽셀). 이미지 로드 전이면 undefined —
   * 이 경우 상한 clamp 를 적용하지 않는다(하드코딩 1920/1080 으로 잘못 자르지 않기 위함).
   * 하한 0 은 항상 유지. 로드 후 실측값이 오면 우/하단 경계로 clamp.
   */
  imageWidth?: number;
  imageHeight?: number;
  /**
   * SAM2 자동추적 컨텍스트 — TRACK 도구가 활성이고 라벨이 선택되면 Sam2TrackTool 렌더.
   * - srcSn       : 시작 프레임 SRC_SN
   * - nextSrcSns  : 후속 프레임 SRC_SN 리스트 (현재 프레임 이후 siblings)
   * - onTracked   : 전파 성공 시 콜백 (라벨 재조회 등)
   * 미제공 시 추적 토글 비노출(하위호환).
   */
  track?: {
    srcSn: number | undefined;
    nextSrcSns: number[];
    /** 추적 성공분 콜백 — (tracked, partial). 상위가 작업본 병합/토스트 담당(미저장 병합). */
    onTracked?: (tracked: Sam2TrackedItem[], partial: boolean) => void;
    /** (R12) AI Tool 팝업에서 고른 추적 형태(BBOX/POLYGON). 미지정이면 BE 기본 POLYGON. */
    shape?: DetectShapeType;
    /** (R12) AI Tool 팝업에서 고른 추적 라벨명. 있으면 캔버스 선택 객체 클래스보다 우선. */
    label?: string;
  };
  /**
   * (Phase 2 FE) AI 분할(SAM_SEGMENT) 조절 컨텍스트 — 도구 활성 시 "AI 분할 정밀도" 섹션 노출.
   * 인식 민감도는 분할에 무의미하므로 노출하지 않는다(경계 세밀함만).
   * - defaultTolerance      : 프리필 값(시스템 설정 POLYGON_SIMPLIFY_TOLERANCE). 미지정 시 코드 상수 폴백.
   * - tolerance             : 상위가 보유한 현재 조절 값(undefined=미조절 → 프리필 표시, 요청 미포함).
   * - onToleranceChange     : 조절 콜백. 상위(LabelingPage)가 값을 보유해 분할 요청에 배선한다.
   * - immediateDraw         : "즉시 그리기" 토글 상태(controlled). true 면 분할 클릭마다 프리뷰를 즉시 그린다.
   *                           미지정 시 OFF(false). 상위(LabelingPage)가 값과 콜백을 함께 소유한다.
   * - onImmediateDrawChange : "즉시 그리기" 토글 변경 콜백.
   * 미제공 시 섹션 비노출(하위호환).
   */
  segment?: {
    defaultTolerance?: number;
    tolerance?: number;
    onToleranceChange?: (value: number) => void;
    immediateDraw?: boolean;
    onImmediateDrawChange?: (value: boolean) => void;
  };
}

/**
 * 선택된 라벨의 속성 패널.
 * - 라벨 드롭다운 / 출처 뱃지 / 신뢰도 바 / 좌표 X/Y/W/H / 속성(성별/연령대) / SAM2 추적 토글 placeholder
 */
export function ObjectAttributePanel({
  labels,
  availableLabels,
  imageWidth,
  imageHeight,
  track,
  segment,
}: ObjectAttributePanelProps) {
  const activeTool = useLabelStore((s) => s.activeTool);
  // 편집 차단 단일 판정원 — 장시간 작업 중에는 라벨 수정·정밀도 조절·즉시 그리기 토글을 막는다.
  // (추적 실행 버튼은 Sam2TrackTool 이 같은 셀렉터로 자체 비활성화한다.)
  const editBlocked = useIsEditBlocked(track?.srcSn);

  // AI 분할 도구 활성 시 경계 세밀함 슬라이더 + "즉시 그리기" 토글 — 선택 객체 유무와 무관하게
  // 노출(분할은 클릭/박스로 새 객체를 만드는 도구라 선택이 없어도 조절 가능해야 한다).
  // 미조절이면 프리필만 표시. "즉시 그리기"는 이 도구의 클릭 프리뷰에만 효력이 있어 여기에 둔다
  // (구 위치인 AI Tool 팝업에서는 팝업 실행에 아무 영향이 없어 오해를 유발했다).
  const segmentControl =
    segment && activeTool === ToolType.SAM_SEGMENT ? (
      <div className="mt-1 rounded bg-gray-700 p-2">
        <div className="mb-2 text-xs font-semibold text-gray-200">AI 분할 정밀도</div>
        <ToleranceSlider
          id="ai-segment-tolerance"
          dark
          disabled={editBlocked}
          value={segment.tolerance ?? segment.defaultTolerance ?? TOLERANCE_DEFAULT}
          onChange={(v) => segment.onToleranceChange?.(v)}
        />
        <div className="mt-3 flex flex-col gap-1 border-t border-gray-600 pt-2">
          <label
            htmlFor="ai-segment-immediate"
            className="flex cursor-pointer items-center gap-2 text-sm"
          >
            <input
              id="ai-segment-immediate"
              type="checkbox"
              className="h-4 w-4 accent-primary-600 disabled:cursor-not-allowed disabled:opacity-50"
              disabled={editBlocked}
              checked={segment.immediateDraw ?? false}
              onChange={(e) => segment.onImmediateDrawChange?.(e.target.checked)}
            />
            <span className="font-medium text-gray-200">즉시 그리기</span>
          </label>
          <p className="pl-6 text-xs text-gray-400">클릭할 때마다 미리보기가 그려집니다.</p>
        </div>
      </div>
    ) : null;
  // Phase 8: availableLabels 미전달 시 useLabelMasters 에서 자동 채움.
  const { data: labelMasters } = useLabelMasters();
  const resolvedAvailable: AvailableLabel[] = useMemo(() => {
    if (availableLabels && availableLabels.length > 0) return availableLabels;
    if (!labelMasters) return [];
    return labelMasters
      .filter((m) => m.useYn === 'Y')
      .sort((a, b) => {
        if (a.sortNo !== b.sortNo) return a.sortNo - b.sortNo;
        return a.labelId - b.labelId;
      })
      .map((m) => ({ id: m.labelId, name: m.name, color: m.color }));
  }, [availableLabels, labelMasters]);
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const updateLabel = useLabelStore((s) => s.updateLabel);
  const target = useMemo(() => labels.find((l) => l.id === selectedId), [labels, selectedId]);

  if (!target) {
    return (
      <aside
        className="flex h-full w-72 flex-col gap-2 border-l border-gray-700 bg-gray-800 p-3"
        aria-label="객체 속성"
      >
        <h3 className="text-sub font-semibold text-gray-100">객체 속성</h3>
        <p className="text-sub text-gray-400">선택된 객체가 없습니다</p>
        {segmentControl}
      </aside>
    );
  }

  const lowConfidence = target.confidence !== undefined && target.confidence < 0.5;
  const sourceLabel = target.source === 'MANUAL' ? '수동' : '자동';
  // 헤더 식별자: 중립적 객체 식별자(라벨 id 앞 8자)만 사용한다. track_id 는 참조하지 않는다 —
  // track_id 는 아래 신설 "트랙 ID" 필드가 전담하므로 헤더 #N 과 역할을 완전히 분리한다.
  const objectNumber = String(target.id ?? '').slice(0, 8);

  // 실측 크기 미확정(undefined) 시 상한 clamp 미적용 — 하한 0 만 유지. 실측값이 있으면
  // 우/하단 경계(width-1 / height-1)로 clamp 해 이미지 밖 좌표·데이터 손실을 방지한다.
  function clampX(v: number): number {
    const lower = Math.max(0, v);
    return imageWidth != null ? Math.min(imageWidth - 1, lower) : lower;
  }
  function clampY(v: number): number {
    const lower = Math.max(0, v);
    return imageHeight != null ? Math.min(imageHeight - 1, lower) : lower;
  }

  function handleLabelChange(e: React.ChangeEvent<HTMLSelectElement>) {
    if (editBlocked) return;
    const newId = Number(e.target.value);
    const found = resolvedAvailable.find((l) => l.id === newId);
    if (!found || !target) return;
    updateLabel(target.id, { classId: found.id, className: found.name });
  }

  function handleCoordChange(field: 'left' | 'top' | 'right' | 'bottom', raw: string) {
    if (editBlocked) return;
    if (!target || target.shape.type !== 'BBOX') return;
    const num = Number(raw);
    if (!Number.isFinite(num)) return;
    const clamped =
      field === 'left' || field === 'right' ? clampX(num) : clampY(num);
    const merged = { ...target.shape, [field]: clamped };
    // left>right / top>bottom 역전 입력 시 음수 width/height Rect 가 생성되는 것을 방지하기 위해
    // 저장 직전 정규화한다 (left<right, top<bottom 보장).
    const norm = normalizeBox(merged.left, merged.top, merged.right, merged.bottom);
    const nextShape = { ...merged, ...norm };
    updateLabel(target.id, { shape: nextShape });
  }

  return (
    <aside
      className="flex h-full w-72 flex-col gap-3 overflow-y-auto border-l border-gray-700 bg-gray-800 p-3"
      aria-label="객체 속성"
    >
      <h3 className="flex items-center gap-2 text-sub font-semibold text-gray-100">
        <span>객체 속성</span>
        <span className="text-gray-400 text-xs" data-testid="object-attribute-id">
          #{objectNumber}
        </span>
      </h3>

      {/* 라벨 드롭다운 — Phase 8: useLabelMasters 응답을 자동 사용 */}
      {resolvedAvailable.length > 0 ? (
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-400">라벨</span>
          <select
            aria-label="라벨 선택"
            value={target.classId}
            disabled={editBlocked}
            onChange={handleLabelChange}
            className="rounded border border-gray-600 bg-gray-700 px-2 py-1 text-sub text-white focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
          >
            {/* 표시는 마스터 등록명 그대로(공용 함수). 저장 값도 같은 al.name 이다. */}
            {resolvedAvailable.map((al) => (
              <option key={al.id} value={al.id}>
                {resolveLabelDisplayName(al.name)} (#{al.id})
              </option>
            ))}
          </select>
        </label>
      ) : (
        <Field
          label="라벨"
          value={
            target.className
              ? `${resolveLabelDisplayName(target.className)} (#${target.classId})`
              : '라벨 없음'
          }
        />
      )}

      {/* 트랙 ID — 헤더의 objectNumber(식별자 fallback) 와 별개로, track_id 원값을 명확히 노출한다.
          있으면 실제 track_id, 없으면(null/undefined) "미부여"로 명시. */}
      <Field
        label="트랙 ID"
        value={
          <span data-testid="object-track-id">
            {target.trackId != null ? target.trackId : '미부여'}
          </span>
        }
      />

      <Field
        label="생성출처"
        value={
          <span>
            {sourceLabel}
            {lowConfidence && (
              <span className="ml-2 rounded bg-amber-500/20 px-1 text-xs text-amber-300" role="status">
                낮은 신뢰도
              </span>
            )}
          </span>
        }
      />

      {target.confidence !== undefined && (
        <div className="flex flex-col gap-1">
          <span className="text-xs text-gray-400">신뢰도</span>
          <div
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(target.confidence * 100)}
            aria-label="신뢰도"
            className="h-2 w-full overflow-hidden rounded bg-gray-700"
          >
            <div
              className={lowConfidence ? 'h-full bg-amber-400' : 'h-full bg-emerald-400'}
              style={{ width: `${(target.confidence * 100).toFixed(1)}%` }}
            />
          </div>
          <span className="text-xs text-gray-400">
            {(target.confidence * 100).toFixed(1)}%
          </span>
        </div>
      )}

      <Field label="형태" value={target.shape?.type ?? '-'} />

      {target.shape?.type === 'BBOX' && (
        <CoordsEditor target={target} onChange={handleCoordChange} disabled={editBlocked} />
      )}
      {target.shape && target.shape.type !== 'BBOX' && <CoordsReadonly target={target} />}

      {/* 라벨 관리에서 정의한 속성 입력 — 라벨 마스터 id(classId=LABEL_ID)로 정의 조회, serverId(lblSn)로
          값 로드/저장. parseLabel 이 로드 라벨의 classId 를 labelId 로 폴백 매핑하므로(단일 소스) 로드된
          객체도 classId>0 을 가져 게이트를 통과한다. 별도 labelId 폴백 없이 classId 만으로 판정한다. */}
      {target.classId > 0 && (
        // key: 객체 전환 시 강제 리마운트로 draft/dirtyRef(편집 중 미커밋 값)를 초기화한다.
        // 같은 클래스의 다른 객체(다른 serverId)로 캔버스에서 바로 전환해도 이전 객체의 미커밋
        // draft 가 새 객체 화면에 남아 오염되는 것을 차단(정합성 결함 수정). 미저장 객체는
        // serverId 가 없으므로 client id(target.id)로 폴백해 서로 다른 미저장 객체 간에도 리마운트.
        // 동일 객체 내 저장→invalidate→재조회 시엔 serverId 불변 → key 불변 → 리마운트 없음 →
        // within-object dirty 보존(이슈2) 정상 유지.
        <ObjectAttributeSection
          key={target.serverId ?? target.id}
          classId={target.classId}
          serverId={target.serverId}
          // 속성값 커밋은 즉시 서버 쓰기다 — 좌표 편집(CoordsEditor)과 같은 축으로 차단한다.
          editBlocked={editBlocked}
        />
      )}

      {/* SAM2 자동추적 — TRACK 도구 활성 + 선택 라벨이 있을 때 노출. */}
      {track && activeTool === ToolType.TRACK && (
        <div className="mt-2 rounded bg-gray-700 p-2 text-xs text-gray-300">
          <div className="mb-1 font-semibold text-gray-200">AI 추적</div>
          <Sam2TrackTool
            srcSn={track.srcSn}
            prevPolygon={shapeToPolygon(target.shape)}
            label={target.className}
            labelOverride={track.label}
            // 추적 결과 형태는 선택 객체 실제 형태(BBOX/POLYGON) 우선 — 모달 라디오(track.shape)는
            // 그 외 형태(MASK/KEYPOINT 등)일 때만 폴백. 박스 객체 → 항상 박스 추적을 보장한다.
            shape={shapeToDetectType(target.shape) ?? track.shape}
            trackId={target.trackId ?? String(target.id ?? '')}
            nextSrcSns={track.nextSrcSns}
            onCompleted={track.onTracked}
          />
          {track.nextSrcSns.length === 0 && (
            <p className="mt-1 text-[11px] text-gray-400">후속 프레임이 없어 추적할 수 없습니다.</p>
          )}
        </div>
      )}

      {segmentControl}
    </aside>
  );
}

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-xs text-gray-400">{label}</span>
      <span className="text-sub text-gray-100">{value}</span>
    </div>
  );
}

function CoordsEditor({
  target,
  onChange,
  disabled = false,
}: {
  target: Label;
  onChange: (field: 'left' | 'top' | 'right' | 'bottom', raw: string) => void;
  disabled?: boolean;
}) {
  if (target.shape.type !== 'BBOX') return null;
  const { left, top, right, bottom } = target.shape;
  return (
    <div className="grid grid-cols-2 gap-2">
      <NumberField label="X 좌표" value={left} disabled={disabled} onChange={(v) => onChange('left', v)} />
      <NumberField label="Y 좌표" value={top} disabled={disabled} onChange={(v) => onChange('top', v)} />
      <NumberField label="W 우측" value={right} disabled={disabled} onChange={(v) => onChange('right', v)} />
      <NumberField label="H 하단" value={bottom} disabled={disabled} onChange={(v) => onChange('bottom', v)} />
    </div>
  );
}

function NumberField({
  label,
  value,
  onChange,
  disabled = false,
}: {
  label: string;
  value: number;
  onChange: (raw: string) => void;
  disabled?: boolean;
}) {
  return (
    <label className="flex flex-col gap-0.5">
      <span className="text-xs text-gray-400">{label}</span>
      <input
        type="number"
        aria-label={label}
        value={Number.isFinite(value) ? value : ''}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        className="rounded border border-gray-600 bg-gray-700 px-2 py-1 text-sub text-white focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-50"
      />
    </label>
  );
}

function CoordsReadonly({ target }: { target: Label }) {
  if (target.shape.type === 'KEYPOINT') {
    const kps = target.shape.keypoints;
    const visible = kps.filter((k) => k.v === 2).length;
    const occluded = kps.filter((k) => k.v === 1).length;
    const unlabeled = kps.filter((k) => k.v === 0).length;
    return (
      <div className="flex flex-col gap-1">
        <Field label="좌표" value={<span>스켈레톤 {kps.length}관절</span>} />
        <Field
          label="가시성"
          value={
            <span>
              가시 {visible} · 비가시 {occluded} · 미표기 {unlabeled}
            </span>
          }
        />
        <p className="text-[11px] text-gray-400">
          관절을 Alt+클릭하면 가시성(가시→비가시→미표기)이 순환됩니다.
        </p>
      </div>
    );
  }
  if (target.shape.type === 'POLYGON') {
    const points = target.shape.points;
    // 임계 초과 폴리곤은 꼭짓점 앵커를 렌더하지 않으므로(렉 방지) 전체 이동만 가능함을 안내.
    const vertexEditable = shouldRenderVertexAnchors(points);
    return (
      <div className="flex flex-col gap-1">
        <Field label="좌표" value={<span>{points.length / 2}개 정점</span>} />
        {!vertexEditable && (
          <p className="text-[11px] text-amber-300" role="status">
            정점이 많아 꼭짓점 편집은 비활성화됩니다. 폴리곤 전체 이동만 가능합니다.
          </p>
        )}
      </div>
    );
  }
  return <Field label="좌표" value={<span>Mask</span>} />;
}
