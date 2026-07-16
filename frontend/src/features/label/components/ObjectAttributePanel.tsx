// SCR-LABEL-002 — 오브젝트 속성 패널 (Phase 6 완성).
//
// 보안: 좌표 입력은 image 경계로 clamp. classId/className 변경은 availableLabels 화이트리스트만 허용.

import { useMemo } from 'react';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from '../hooks/useLabelMasters';
import { Sam2TrackTool } from '../canvas/tools/Sam2TrackTool';
import { normalizeBox } from '../canvas/utils/canvasGeometry';
import { shouldRenderVertexAnchors } from '../canvas/utils/polygonEdit';
import type { Sam2TrackResponse } from '../api';
import type { Label } from '../types';
import { ToolType } from '../types';

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
  /** 좌표 clamp용 이미지 크기 */
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
    onTracked?: (res: Sam2TrackResponse) => void;
    /**
     * Phase 9 — 포털 모드면 포털 전용 /portal/frames/{id}/sam2-track 경로로 추적(persist 없이 좌표만).
     * 내부 경로는 PORTAL 채널 403 이므로 호출 금지.
     */
    portalMode?: boolean;
  };
}

/**
 * 선택된 라벨의 속성 패널.
 * - 라벨 드롭다운 / 출처 뱃지 / 신뢰도 바 / 좌표 X/Y/W/H / 속성(성별/연령대) / SAM2 추적 토글 placeholder
 */
export function ObjectAttributePanel({
  labels,
  availableLabels,
  imageWidth = 1920,
  imageHeight = 1080,
  track,
}: ObjectAttributePanelProps) {
  const activeTool = useLabelStore((s) => s.activeTool);
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
      </aside>
    );
  }

  const lowConfidence = target.confidence !== undefined && target.confidence < 0.5;
  const sourceLabel = target.source === 'MANUAL' ? '수동' : '자동';
  // 객체 식별자: trackId 가 있으면 우선, 없으면 라벨 id 앞 8자 (UUID/short hash 가독성)
  const objectNumber = target.trackId ?? String(target.id ?? '').slice(0, 8);

  function clampX(v: number): number {
    return Math.max(0, Math.min(imageWidth - 1, v));
  }
  function clampY(v: number): number {
    return Math.max(0, Math.min(imageHeight - 1, v));
  }

  function handleLabelChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const newId = Number(e.target.value);
    const found = resolvedAvailable.find((l) => l.id === newId);
    if (!found || !target) return;
    updateLabel(target.id, { classId: found.id, className: found.name });
  }

  function handleCoordChange(field: 'left' | 'top' | 'right' | 'bottom', raw: string) {
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
            onChange={handleLabelChange}
            className="rounded border border-gray-600 bg-gray-700 px-2 py-1 text-sub text-white focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
          >
            {resolvedAvailable.map((al) => (
              <option key={al.id} value={al.id}>
                {al.name} (#{al.id})
              </option>
            ))}
          </select>
        </label>
      ) : (
        <Field label="라벨" value={target.className ? `${target.className} (#${target.classId})` : '라벨 없음'} />
      )}

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
        <CoordsEditor target={target} onChange={handleCoordChange} />
      )}
      {target.shape && target.shape.type !== 'BBOX' && <CoordsReadonly target={target} />}

      {/* SAM2 자동추적 — TRACK 도구 활성 + 선택 라벨이 있을 때 노출. */}
      {track && activeTool === ToolType.TRACK && (
        <div className="mt-2 rounded bg-gray-700 p-2 text-xs text-gray-300">
          <div className="mb-1 font-semibold text-gray-200">SAM2 자동추적</div>
          <Sam2TrackTool
            srcSn={track.srcSn}
            prevPolygon={shapeToPolygon(target.shape)}
            label={target.className}
            trackId={target.trackId ?? String(target.id ?? '')}
            nextSrcSns={track.nextSrcSns}
            onCompleted={track.onTracked}
            portalMode={track.portalMode}
          />
          {track.nextSrcSns.length === 0 && (
            <p className="mt-1 text-[11px] text-gray-400">후속 프레임이 없어 추적할 수 없습니다.</p>
          )}
        </div>
      )}
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
}: {
  target: Label;
  onChange: (field: 'left' | 'top' | 'right' | 'bottom', raw: string) => void;
}) {
  if (target.shape.type !== 'BBOX') return null;
  const { left, top, right, bottom } = target.shape;
  return (
    <div className="grid grid-cols-2 gap-2">
      <NumberField label="X 좌표" value={left} onChange={(v) => onChange('left', v)} />
      <NumberField label="Y 좌표" value={top} onChange={(v) => onChange('top', v)} />
      <NumberField label="W 우측" value={right} onChange={(v) => onChange('right', v)} />
      <NumberField label="H 하단" value={bottom} onChange={(v) => onChange('bottom', v)} />
    </div>
  );
}

function NumberField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: number;
  onChange: (raw: string) => void;
}) {
  return (
    <label className="flex flex-col gap-0.5">
      <span className="text-xs text-gray-400">{label}</span>
      <input
        type="number"
        aria-label={label}
        value={Number.isFinite(value) ? value : ''}
        onChange={(e) => onChange(e.target.value)}
        className="rounded border border-gray-600 bg-gray-700 px-2 py-1 text-sub text-white focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
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
        <Field label="좌표" value={<span>키포인트 {kps.length}관절</span>} />
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
