// SCR-REVIEW-002 Phase 4 — 객체 목록 (카테고리 트리) 패널.
//
// mock 정합:
// - 카테고리 그룹별 collapse 가능 (▶/▼ 토글).
// - 각 row: 색상점 + `{label} #{indexInCategory}` + 타입 배지 (BBOX/POLYGON 등).
// - 선택된 row 는 강조 (배경 + ring), hover row 는 가벼운 배경.
//
// 양방향 동기화:
// - 캔버스에서 라벨 클릭 → store.selectedLabelId 변경 →
//   해당 라벨의 카테고리를 자동으로 expand + row 강조.
//
// a11y:
// - row: role="button" + aria-selected + 키보드 enter/space 지원.
// - group header: aria-expanded.
//
// 보안: 모든 표시 텍스트는 JSX 텍스트 (자동 이스케이프) — dangerouslySetInnerHTML 금지.

import { useEffect, useMemo, useState, type KeyboardEvent } from 'react';

import type { LabelItem, LabelType } from '../types';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import { colorForLabel } from '../utils/labelColor';

interface ObjectListPanelProps {
  labels: LabelItem[];
}

interface GroupedLabel {
  label: string;
  items: { item: LabelItem; indexInCategory: number }[];
}

/**
 * 라벨 배열을 카테고리(label) 별로 그룹화한다.
 *
 * - 카테고리 내 순번은 1-base (원래 순서 유지).
 * - 카테고리 순서는 라벨명 가나다 정렬 (mock 시각 정합 위해 안정적 ordering 보장).
 */
function groupLabels(labels: LabelItem[]): GroupedLabel[] {
  const map = new Map<string, LabelItem[]>();
  for (const l of labels) {
    const arr = map.get(l.label) ?? [];
    arr.push(l);
    map.set(l.label, arr);
  }
  return [...map.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([label, items]) => ({
      label,
      items: items.map((item, i) => ({ item, indexInCategory: i + 1 })),
    }));
}

// KRDS 예외: 범주 구분색(라벨 타입 BBOX/POLYGON/SEGMENT/TRACK, 데이터시각화 성격) — 토큰 획일화 제외(의도적 유지).
// 라이트 패널 기준 셰이드(50 배경 / 700 전경 / 200 테두리) — 흰 배경에서 WCAG AA 를 만족한다.
const TYPE_BADGE_CLASS: Record<LabelType, string> = {
  BBOX: 'bg-blue-50 text-blue-700 border-blue-200',
  POLYGON: 'bg-purple-50 text-purple-700 border-purple-200',
  SEGMENT: 'bg-indigo-50 text-indigo-700 border-indigo-200',
  TRACK: 'bg-pink-50 text-pink-700 border-pink-200',
};

function TypeBadge({ type }: { type: LabelType }) {
  const cls = TYPE_BADGE_CLASS[type] ?? 'bg-gray-100 text-gray-600 border-gray-200';
  return (
    <span
      className={`inline-flex items-center rounded border px-1.5 py-0.5 text-[10px] font-medium uppercase ${cls}`}
      data-testid="object-list-type-badge"
    >
      {type.toLowerCase()}
    </span>
  );
}

/**
 * 카테고리 트리 — 객체 목록 패널.
 */
export function ObjectListPanel({ labels }: ObjectListPanelProps) {
  const groups = useMemo(() => groupLabels(labels), [labels]);
  // Zustand selector 패턴 (전체 store 구독 금지).
  const selectedLabelId = useReviewSelectionStore((s) => s.selectedLabelId);
  const hoverLabelId = useReviewSelectionStore((s) => s.hoverLabelId);
  const setSelected = useReviewSelectionStore((s) => s.setSelected);
  const setHover = useReviewSelectionStore((s) => s.setHover);

  // 카테고리별 expanded 상태 (기본 전부 펼침).
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  // 외부(캔버스 클릭)에서 selected 가 바뀌면 해당 카테고리 자동 expand.
  useEffect(() => {
    if (selectedLabelId == null) return;
    const selected = labels.find((l) => l.id === selectedLabelId);
    if (!selected) return;
    setCollapsed((prev) => {
      if (!prev[selected.label]) return prev;
      return { ...prev, [selected.label]: false };
    });
  }, [selectedLabelId, labels]);

  const toggle = (label: string) => {
    setCollapsed((prev) => ({ ...prev, [label]: !prev[label] }));
  };

  const handleRowClick = (id: number) => {
    setSelected(id);
  };

  const handleRowKeyDown = (e: KeyboardEvent<HTMLDivElement>, id: number) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      setSelected(id);
    }
  };

  const handleRowHoverIn = (id: number) => {
    if (selectedLabelId === id) return; // 선택된 항목은 hover indicator 생략 (selected 강조 우선).
    setHover(id);
  };

  const handleRowHoverOut = (id: number) => {
    // 다른 row 에 진입 전까지 잔존하면 호버 indicator 가 깜빡임 → 진입 시 setHover 가 갱신하므로 빠른 leave 만 처리.
    if (hoverLabelId === id) setHover(null);
  };

  if (labels.length === 0) {
    return (
      <div
        className="px-3 py-6 text-center text-caption text-gray-500"
        data-testid="object-list-empty"
      >
        라벨이 없습니다
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1" data-testid="object-list-panel">
      {groups.map((g) => {
        const isCollapsed = collapsed[g.label] === true;
        const color = colorForLabel(g.label);
        return (
          <div key={g.label} data-testid={`object-list-group-${g.label}`}>
            <button
              type="button"
              className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-label font-medium text-gray-900 hover:bg-gray-50"
              aria-expanded={!isCollapsed}
              data-testid={`object-list-group-header-${g.label}`}
              onClick={() => toggle(g.label)}
            >
              <span
                aria-hidden="true"
                className="text-[10px] text-gray-500"
              >
                {isCollapsed ? '▶' : '▼'}
              </span>
              <span
                aria-hidden="true"
                className="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
                style={{ backgroundColor: color }}
              />
              <span className="flex-1">{g.label}</span>
              <span className="text-[10px] text-gray-500">({g.items.length})</span>
            </button>

            {!isCollapsed && (
              <ul className="ml-3 flex flex-col gap-0.5 border-l border-gray-200 pl-2">
                {g.items.map(({ item, indexInCategory }, listIdx) => {
                  const isSelected = selectedLabelId === item.id;
                  const isHover = hoverLabelId === item.id;
                  const rowClass = [
                    'flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-caption',
                    isSelected
                      ? 'bg-primary-50 text-primary-700 ring-1 ring-primary-500'
                      : isHover
                        ? 'bg-gray-100 text-gray-900'
                        : 'text-gray-700 hover:bg-gray-50',
                  ].join(' ');
                  return (
                    // BE 데이터 정합 이슈로 동일 id 가 들어와도 key 충돌이 발생하지 않도록 인덱스 폴백 포함.
                    <li key={`${item.id}-${listIdx}`}>
                      <div
                        role="button"
                        tabIndex={0}
                        aria-selected={isSelected}
                        className={rowClass}
                        data-testid={`object-list-row-${item.id}`}
                        onClick={() => handleRowClick(item.id)}
                        onKeyDown={(e) => handleRowKeyDown(e, item.id)}
                        onMouseEnter={() => handleRowHoverIn(item.id)}
                        onMouseLeave={() => handleRowHoverOut(item.id)}
                      >
                        <span
                          aria-hidden="true"
                          className="inline-block h-2 w-2 shrink-0 rounded-full"
                          style={{ backgroundColor: color }}
                        />
                        <span className="flex-1 truncate">
                          {item.label} #{indexInCategory}
                        </span>
                        <TypeBadge type={item.lblTypeCd} />
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        );
      })}
    </div>
  );
}
