// SCR-REVIEW-002 Phase 4 — 객체 속성 패널.
//
// store.selectedLabelId 로 현재 선택된 라벨을 찾아 상세 속성을 표시한다.
//
// 표시 항목:
// - 카테고리 색상 점 + 카테고리 + `#순번` (카테고리 내 1-base)
// - 타입 (BBOX 시 x/y/w/h, POLYGON/SEGMENT/TRACK 시 점 개수)
// - confScore: 퍼센트 (소수 1자리) 또는 "—"
// - autoLblYn: 자동/수동 라벨 한글 표시
//
// 미선택 시 안내문 + 보조 안내.
//
// 보안: 모든 텍스트 JSX 자동 이스케이프.

import { useMemo } from 'react';

import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';

import type { LabelItem } from '../types';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import { reviewLabelColor } from '../utils/labelColor';
import { pointsToBBox } from '../utils/coordinates';

interface ObjectAttributesPanelProps {
  labels: LabelItem[];
}

/**
 * confScore (0~1) → 퍼센트 문자열. null 이면 "—".
 */
function formatConfScore(score: number | null | undefined): string {
  if (score == null || Number.isNaN(score)) return '—';
  const pct = score * 100;
  return `${pct.toFixed(1)}%`;
}

function AttrRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-3 py-1.5">
      <dt className="shrink-0 text-caption text-gray-500">{label}</dt>
      <dd className="text-right text-label font-medium text-gray-900">{value}</dd>
    </div>
  );
}

export function ObjectAttributesPanel({ labels }: ObjectAttributesPanelProps) {
  const selectedLabelId = useReviewSelectionStore((s) => s.selectedLabelId);
  // 색상 판정의 단일 진실원 = 라벨 마스터. 하드코딩 색상표(구 FIXED_COLORS)는 폐지됐다.
  const { data: labelMasters } = useLabelMasters();

  const selected = useMemo(
    () =>
      selectedLabelId == null ? null : labels.find((l) => l.id === selectedLabelId) ?? null,
    [selectedLabelId, labels],
  );

  const indexInCategory = useMemo(() => {
    if (!selected) return 0;
    let count = 0;
    for (const l of labels) {
      if (l.label === selected.label) {
        count += 1;
        if (l.id === selected.id) return count;
      }
    }
    return count;
  }, [selected, labels]);

  if (!selected) {
    return (
      <div
        className="rounded-md border border-dashed border-gray-300 px-4 py-6 text-center"
        data-testid="object-attributes-empty"
      >
        <p className="text-body-md text-gray-700">객체를 선택하세요</p>
        <p className="mt-1 text-caption text-gray-500">캔버스 또는 목록에서 객체를 클릭</p>
      </div>
    );
  }

  const color = reviewLabelColor(selected, labelMasters);
  const isBBox = selected.lblTypeCd === 'BBOX';
  const bbox = isBBox ? pointsToBBox(selected.points) : null;

  return (
    <div
      className="rounded-md border border-gray-200 bg-gray-50 p-3"
      data-testid="object-attributes-panel"
    >
      <div className="mb-2 flex items-center gap-2">
        <span
          aria-hidden="true"
          className="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
          style={{ backgroundColor: color }}
        />
        <span className="text-body-md font-semibold text-gray-900">
          {selected.label} #{indexInCategory}
        </span>
      </div>

      <dl className="divide-y divide-gray-200">
        <AttrRow label="타입" value={<span data-testid="attr-type">{selected.lblTypeCd}</span>} />

        {isBBox && bbox ? (
          <AttrRow
            label="좌표"
            value={
              // 「x 412 · y 268 · w 96 · h 214」 — 이름과 값을 띄어 쓰고 항목 사이를
              // 가운뎃점으로 나눈다. 구 표기 `x:173 y:87 …` 는 콜론이 이름에 붙고 구분이 공백
              // 하나뿐이라 좁은 패널에서 줄이 접히면 어느 숫자가 어느 이름의 값인지 흐려졌다.
              <span data-testid="attr-bbox-coords" className="font-mono">
                {`x ${Math.round(bbox.x)} · y ${Math.round(bbox.y)} · w ${Math.round(bbox.width)} · h ${Math.round(bbox.height)}`}
              </span>
            }
          />
        ) : (
          <AttrRow
            label="점 개수"
            value={
              <span data-testid="attr-point-count">{selected.points.length}</span>
            }
          />
        )}

        <AttrRow
          label="신뢰도"
          value={
            <span data-testid="attr-conf-score">{formatConfScore(selected.confScore)}</span>
          }
        />

        <AttrRow
          label="라벨 유형"
          value={
            <span data-testid="attr-auto-yn">
              {selected.autoLblYn === 'Y' ? '자동 라벨' : '수동 라벨'}
            </span>
          }
        />

        <AttrRow label="카테고리" value={<span data-testid="attr-category">{selected.label}</span>} />
      </dl>
    </div>
  );
}
