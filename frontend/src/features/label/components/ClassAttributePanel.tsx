// SCR-LABEL-001 우측 하단 속성 패널 (mock 정합 — 다크 톤, 선택된 객체 속성).
//
// 보안: 좌표 입력은 image 경계로 clamp. classId/className 변경은 availableLabels 화이트리스트만 허용.

import { useMemo } from 'react';

import { useLabelStore } from '@/stores/useLabelStore';

import { getLabelDisplayName } from '../labelColors';
import type { Label } from '../types';

interface ClassAttributePanelProps {
  labels: Label[];
  imageWidth?: number;
  imageHeight?: number;
}

export function ClassAttributePanel({
  labels,
  imageWidth = 1920,
  imageHeight = 1080,
}: ClassAttributePanelProps) {
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const updateLabel = useLabelStore((s) => s.updateLabel);

  const target = useMemo(() => labels.find((l) => l.id === selectedId), [labels, selectedId]);

  if (!target) {
    return (
      <div className="p-4 flex flex-col items-center justify-center h-full text-center text-gray-400">
        <p className="text-sm">객체를 선택하세요</p>
        <p className="text-xs mt-1 text-gray-500">캔버스 또는 목록에서 객체를 클릭</p>
      </div>
    );
  }

  const isAuto = target.source !== 'MANUAL';
  const confidencePct =
    target.confidence !== undefined ? Math.round(target.confidence * 100) : 0;

  function clampX(v: number) {
    return Math.max(0, Math.min(imageWidth - 1, v));
  }
  function clampY(v: number) {
    return Math.max(0, Math.min(imageHeight - 1, v));
  }

  function handleCoordChange(field: 'left' | 'top' | 'right' | 'bottom', raw: string) {
    if (!target || target.shape.type !== 'BBOX') return;
    const num = Number(raw);
    if (!Number.isFinite(num)) return;
    const clamped = field === 'left' || field === 'right' ? clampX(num) : clampY(num);
    const nextShape = { ...target.shape, [field]: clamped };
    updateLabel(target.id, { shape: nextShape });
  }

  function handleConfidenceChange(raw: string) {
    if (!target) return;
    const num = Number(raw);
    if (!Number.isFinite(num)) return;
    const clamped = Math.max(0, Math.min(100, num));
    updateLabel(target.id, { confidence: clamped / 100 });
  }

  const inputCls =
    'w-full bg-gray-700 border border-gray-600 rounded px-2 py-1 text-xs text-white focus:outline-none focus:ring-1 focus:ring-blue-500';

  return (
    <div className="overflow-y-auto flex-1 p-3 space-y-3 text-sm text-gray-200">
      {/* 라벨 (className 표시 — 객체 식별자는 trackId 우선, 없으면 id 앞 8자) */}
      <div>
        <span className="block text-xs text-gray-400 mb-1">라벨</span>
        <div className="text-sm text-white">
          {getLabelDisplayName(target.className)}{' '}
          <span className="text-gray-500 text-xs" data-testid="class-attribute-id">
            #{target.trackId ?? String(target.id ?? '').slice(0, 8)}
          </span>
        </div>
      </div>

      {/* 신뢰도 */}
      {target.confidence !== undefined && (
        <div>
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs text-gray-400">신뢰도</span>
            <input
              type="number"
              min={0}
              max={100}
              value={confidencePct}
              onChange={(e) => handleConfidenceChange(e.target.value)}
              aria-label="신뢰도"
              className="w-16 bg-gray-700 border border-gray-600 rounded px-2 py-0.5 text-xs text-white text-right focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>
          <input
            type="range"
            min={0}
            max={100}
            value={confidencePct}
            onChange={(e) => handleConfidenceChange(e.target.value)}
            className="w-full accent-blue-500 h-1.5"
            aria-label="신뢰도 슬라이더"
          />
        </div>
      )}

      {/* 생성 방식 뱃지 */}
      <div className="flex items-center gap-2">
        <span className="text-xs text-gray-400">생성방식</span>
        <span
          className={
            isAuto
              ? 'text-xs px-2 py-0.5 rounded-full bg-orange-900/60 text-orange-300'
              : 'text-xs px-2 py-0.5 rounded-full bg-blue-900/60 text-blue-300'
          }
        >
          {isAuto ? '🤖 자동' : '✏️ 수동'}
        </span>
      </div>

      {/* BBox 좌표 */}
      {target.shape.type === 'BBOX' && (
        <div>
          <span className="block text-xs text-gray-400 mb-1">
            좌표 (이미지 기준 {imageWidth}×{imageHeight})
          </span>
          <div className="grid grid-cols-2 gap-1.5">
            <CoordField
              label="LEFT"
              value={target.shape.left}
              onChange={(v) => handleCoordChange('left', v)}
              cls={inputCls}
            />
            <CoordField
              label="TOP"
              value={target.shape.top}
              onChange={(v) => handleCoordChange('top', v)}
              cls={inputCls}
            />
            <CoordField
              label="RIGHT"
              value={target.shape.right}
              onChange={(v) => handleCoordChange('right', v)}
              cls={inputCls}
            />
            <CoordField
              label="BOTTOM"
              value={target.shape.bottom}
              onChange={(v) => handleCoordChange('bottom', v)}
              cls={inputCls}
            />
          </div>
        </div>
      )}

      {/* Polygon 정보 */}
      {target.shape.type === 'POLYGON' && (
        <div>
          <span className="block text-xs text-gray-400 mb-1">폴리곤 좌표</span>
          <p className="text-xs text-gray-500">
            포인트 수: {target.shape.points.length / 2}개
          </p>
        </div>
      )}

      {/* Mask 정보 */}
      {target.shape.type === 'MASK' && (
        <div>
          <span className="block text-xs text-gray-400 mb-1">마스크</span>
          <p className="text-xs text-gray-500">RLE 마스크 (편집 미지원)</p>
        </div>
      )}

      {/* Track ID — Phase 5: trackId 는 string|null, null 인 경우 숨김 */}
      {target.trackId != null && target.trackId !== '' && (
        <div>
          <span className="block text-xs text-gray-400 mb-1">트랙 ID</span>
          <span className="text-xs text-gray-300 bg-gray-700 rounded px-2 py-1 inline-block">
            #{target.trackId}
          </span>
        </div>
      )}
    </div>
  );
}

function CoordField({
  label,
  value,
  onChange,
  cls,
}: {
  label: string;
  value: number;
  onChange: (raw: string) => void;
  cls: string;
}) {
  return (
    <label className="block">
      <span className="text-xs text-gray-500 mb-0.5 block">{label}</span>
      <input
        type="number"
        value={Number.isFinite(value) ? value : 0}
        onChange={(e) => onChange(e.target.value)}
        aria-label={label}
        className={cls}
      />
    </label>
  );
}
