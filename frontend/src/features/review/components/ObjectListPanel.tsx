// SCR-REVIEW-002 Phase 4 — 객체 목록 (카테고리 트리) 패널.
//
// 색상 축 2개 (통일 금지 — 서로 다른 것을 보여주는 것이 의도):
// - 그룹 헤더 점 = **분류 축**. 라벨 마스터 색상이며 판정은 공용 단일 진실원
//   `features/label/utils/labelColor.getLabelDisplayColor` 에 위임한다(하드코딩 색상표 폐지).
// - 각 행 막대 = **트랙 시각화 축**. trackId 해시색(`trackIdToColor`).
//
// mock 정합:
// - 카테고리 그룹별 collapse 가능 (방향 셰브론 토글).
// - 각 row: 트랙 색 막대 + `{label} #{indexInCategory}` + 타입 배지 (BBOX/POLYGON 등).
// - 선택된 row 는 강조 (배경 + ring), hover row 는 가벼운 배경.
//
// 양방향 동기화:
// - 캔버스에서 라벨 클릭 → store.selectedLabelId 변경 →
//   해당 라벨의 카테고리를 자동으로 expand + row 강조.
//
// a11y:
// - 카테고리 목록: ul[role="listbox"] + 각 행 li[role="option"] + aria-selected.
//   ★선택 상태 전달은 이 패널의 기능 요구다 — 선택이 캔버스와 양방향으로 동기화되므로
//   "지금 무엇이 선택돼 있는가"가 보조기술에 전달되지 않으면 동기화 자체를 인지할 수 없다.
//   role="button" 은 aria-selected 를 지원하지 않으므로(그 조합은 린트가 지적한다) 속성을
//   떼는 대신 선택을 표현할 수 있는 listbox/option 조합으로 맞췄다.
// - row: 키보드 enter/space 지원(tabIndex=0).
// - group header: aria-expanded.
//
// 보안: 모든 표시 텍스트는 JSX 텍스트 (자동 이스케이프) — dangerouslySetInnerHTML 금지.

import { useEffect, useMemo, useState, type KeyboardEvent } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';

import { groupRepresentative } from '@/features/label/components/ObjectClassTree';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';
import {
  FALLBACK_LABEL_COLOR,
  getLabelDisplayColor,
  safeHexColor,
} from '@/features/label/utils/labelColor';

import type { LabelItem, LabelType } from '../types';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import { reviewTrackBarColor, toColorLabel } from '../utils/labelColor';

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

// 범주 구분색 축이다 — 라벨 형태(BBOX/POLYGON/SEGMENT/TRACK/SKELETON)는 서로 우열이 없는 대등한
// 분류라 semantic 이 아니라 DS-001 의 **범주 구분색 8슬롯**(`category-N`)에서 고른다. 이벤트 유형·
// 역할 축과 같은 한 팔레트를 공유한다. 슬롯 번호는 우열·순서를 뜻하지 않는다.
// 라이트 패널 기준 셰이드(50 배경 / 700 전경 / 200 테두리) — 흰 배경에서 WCAG AA 를 만족한다.
//
// ★이 표는 **형태(type) 뱃지**이지 라벨 색이 아니다. 라벨 표시 색상의 단일 진실원은 라벨
//  마스터(LS_LABEL.COLR_VL)이고 판정기는 `getLabelDisplayColor` 한 곳이다 — 여기에 라벨
//  분류별 색을 넣으면 그 정책을 어기는 두 번째 진실원이 된다.
//
// ⚠ TRACK 이 6번(pink)이 아니라 **8번(fuchsia)** 인 것은 의도다 — 6번은 이벤트 축의 폭력·싸움·
//   흉기소지가 쓴다. 축이 달라 겹쳐도 무방하지만, 두 배지가 나란히 놓이는 화면에서 같은 색이
//   서로 다른 뜻으로 읽히는 것을 줄인다.
// [@design DS-001]
const TYPE_BADGE_CLASS: Record<LabelType, string> = {
  BBOX: 'bg-category-1-50 text-category-1-700 border-category-1-200',
  POLYGON: 'bg-category-2-50 text-category-2-700 border-category-2-200',
  SEGMENT: 'bg-category-5-50 text-category-5-700 border-category-5-200',
  TRACK: 'bg-category-8-50 text-category-8-700 border-category-8-200',
  SKELETON: 'bg-category-4-50 text-category-4-700 border-category-4-200',
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
  // 라벨 마스터 — 색상 판정의 단일 진실원. 라벨링 화면과 같은 쿼리 키(staleTime 5분)를 공유하므로
  // 추가 요청은 사실상 발생하지 않는다.
  const { data: labelMasters } = useLabelMasters();
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

  const handleRowKeyDown = (e: KeyboardEvent<HTMLLIElement>, id: number) => {
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
        // 그룹 점 = **분류 축**(라벨 마스터 색상). 판정은 공용 단일 진실원(getLabelDisplayColor)에
        // 위임하고, 대표 항목은 라벨링 객체 트리와 같은 순서 독립 규칙(groupRepresentative)으로 고른다.
        // useTrackFallback:false — 그룹은 분류 축이라 trackId 해시색이 새어들면 안 된다.
        // ⚠ 아래 개별 행 막대(trackIdToColor)는 **트랙 시각화 축**이다. 두 축을 통일하지 말 것 —
        //   서로 다른 것을 보여주는 것이 의도다.
        // id 는 groupRepresentative 의 tiebreak 키다 — 전부 미연결(labelId null)인 그룹에서도
        // 순서 독립이 성립하려면 실제 라벨 id 를 실어야 한다(빈 문자열이면 tiebreak 가 무력화된다).
        const representative = groupRepresentative(
          g.items.map(({ item }) => ({ ...toColorLabel(item), id: String(item.id) })),
        );
        const color = representative
          ? safeHexColor(getLabelDisplayColor(representative, labelMasters, { useTrackFallback: false }))
          : FALLBACK_LABEL_COLOR;
        return (
          <div key={g.label} data-testid={`object-list-group-${g.label}`}>
            <button
              type="button"
              className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-label font-medium text-gray-900 hover:bg-gray-50"
              aria-expanded={!isCollapsed}
              data-testid={`object-list-group-header-${g.label}`}
              onClick={() => toggle(g.label)}
            >
              {/* 접힘/펼침 표식 — 상태는 위 aria-expanded 가 낭독하므로 아이콘은 aria-hidden.
                  아래 listbox/option + aria-selected 계약과는 무관하다(건드리지 않는다). */}
              <span aria-hidden="true" className="inline-flex text-gray-600">
                {isCollapsed ? (
                  <ChevronRight className="h-3 w-3" />
                ) : (
                  <ChevronDown className="h-3 w-3" />
                )}
              </span>
              <span
                aria-hidden="true"
                className="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
                style={{ backgroundColor: color }}
              />
              <span className="flex-1">{g.label}</span>
              <span className="text-[10px] text-gray-600">({g.items.length})</span>
            </button>

            {!isCollapsed && (
              <ul
                className="ml-3 flex flex-col gap-0.5 border-l border-gray-200 pl-2"
                role="listbox"
                aria-label={`${g.label} 객체 목록`}
              >
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
                    <li
                      key={`${item.id}-${listIdx}`}
                      role="option"
                      aria-selected={isSelected}
                      tabIndex={0}
                      className={rowClass}
                      data-testid={`object-list-row-${item.id}`}
                      onClick={() => handleRowClick(item.id)}
                      onKeyDown={(e) => handleRowKeyDown(e, item.id)}
                      onMouseEnter={() => handleRowHoverIn(item.id)}
                      onMouseLeave={() => handleRowHoverOut(item.id)}
                    >
                      {/* 개별 항목 막대 = **트랙 시각화 축**(trackId 해시색). 위 그룹 점의
                          분류 축(마스터 색)과 서로 다른 것을 보여주는 것이 의도다 — 통일 금지.
                          라벨링 객체 트리(ObjectClassTree)의 label-color-bar 와 같은 규칙. */}
                      <span
                        data-testid="review-label-color-bar"
                        aria-hidden="true"
                        className="inline-block h-4 w-1 shrink-0 rounded-sm"
                        style={{ backgroundColor: reviewTrackBarColor(item) }}
                      />
                      <span className="flex-1 truncate">
                        {item.label} #{indexInCategory}
                      </span>
                      <TypeBadge type={item.lblTypeCd} />
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
