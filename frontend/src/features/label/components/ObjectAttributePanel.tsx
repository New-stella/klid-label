// SCR-LABEL-002 — 오브젝트 속성 패널 (Phase 6 완성).
//
// 보안: 좌표 입력은 image 경계로 clamp. classId/className 변경은 availableLabels 화이트리스트만 허용.

import { useMemo } from 'react';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from '../hooks/useLabelMasters';
import type { Label } from '../types';

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
}: ObjectAttributePanelProps) {
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
        className="flex h-full w-72 flex-col gap-2 border-l border-border bg-white p-3"
        aria-label="객체 속성"
      >
        <h3 className="text-sub font-semibold text-primary">객체 속성</h3>
        <p className="text-sub text-neutral">선택된 객체가 없습니다</p>
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
    const nextShape = { ...target.shape, [field]: clamped };
    updateLabel(target.id, { shape: nextShape });
  }

  return (
    <aside
      className="flex h-full w-72 flex-col gap-3 overflow-y-auto border-l border-border bg-white p-3"
      aria-label="객체 속성"
    >
      <h3 className="flex items-center gap-2 text-sub font-semibold text-primary">
        <span>객체 속성</span>
        <span className="text-gray-500 text-xs" data-testid="object-attribute-id">
          #{objectNumber}
        </span>
      </h3>

      {/* 라벨 드롭다운 — Phase 8: useLabelMasters 응답을 자동 사용 */}
      {resolvedAvailable.length > 0 ? (
        <label className="flex flex-col gap-1">
          <span className="text-xs text-neutral">라벨</span>
          <select
            aria-label="라벨 선택"
            value={target.classId}
            onChange={handleLabelChange}
            className="rounded border border-border px-2 py-1 text-sub"
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
              <span className="ml-2 rounded bg-warning/10 px-1 text-xs text-warning" role="status">
                낮은 신뢰도
              </span>
            )}
          </span>
        }
      />

      {target.confidence !== undefined && (
        <div className="flex flex-col gap-1">
          <span className="text-xs text-neutral">신뢰도</span>
          <div
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(target.confidence * 100)}
            aria-label="신뢰도"
            className="h-2 w-full overflow-hidden rounded bg-bgLight"
          >
            <div
              className={lowConfidence ? 'h-full bg-warning' : 'h-full bg-success'}
              style={{ width: `${(target.confidence * 100).toFixed(1)}%` }}
            />
          </div>
          <span className="text-xs text-neutral">
            {(target.confidence * 100).toFixed(1)}%
          </span>
        </div>
      )}

      <Field label="형태" value={target.shape?.type ?? '-'} />

      {target.shape?.type === 'BBOX' && (
        <CoordsEditor target={target} onChange={handleCoordChange} />
      )}
      {target.shape && target.shape.type !== 'BBOX' && <CoordsReadonly target={target} />}

      {/* SAM2 자동추적 토글 placeholder (Sam2TrackTool 컴포넌트 외부에서 결합) */}
      <div className="mt-2 rounded bg-bgLight p-2 text-xs text-neutral">
        SAM2 자동추적은 도구바에서 [T] 버튼으로 활성화
      </div>
    </aside>
  );
}

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-xs text-neutral">{label}</span>
      <span className="text-sub text-primary">{value}</span>
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
      <span className="text-xs text-neutral">{label}</span>
      <input
        type="number"
        aria-label={label}
        value={Number.isFinite(value) ? value : ''}
        onChange={(e) => onChange(e.target.value)}
        className="rounded border border-border px-2 py-1 text-sub"
      />
    </label>
  );
}

function CoordsReadonly({ target }: { target: Label }) {
  if (target.shape.type === 'POLYGON') {
    return <Field label="좌표" value={<span>{target.shape.points.length / 2}개 정점</span>} />;
  }
  return <Field label="좌표" value={<span>Mask</span>} />;
}
