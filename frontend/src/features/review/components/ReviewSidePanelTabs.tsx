// SCREEN-019 검수 상세 — 우측 패널 탭 목록(객체 / 메타 / 이슈).
//
// 사양: 우측 패널은 이 3개 탭으로 전환된다.
//  - 객체 : 카테고리 트리 + 선택 객체 속성 + 검수 메모
//  - 메타 : 이벤트 어노테이션 + 시계열 메타 + 영상 기술 정보
//  - 이슈 : 서버 연동 문의 스레드
//
// ★라벨링 화면(우측 패널 탭)과 **같은 패턴**을 쓴다 — 두 화면을 오가는 검수자가 같은 조작을
//   하도록. 시각 클래스·탭 순서·라벨을 라벨링 화면에서 그대로 가져왔고, 여기에 WAI-ARIA
//   Tabs 패턴의 키보드 이동(←/→/Home/End + roving tabIndex)을 얹었다.
//
// ★roving tabIndex 가 필요한 이유: 탭 3개가 모두 Tab 순회 대상이면 키보드 사용자가 패널
//   본문에 닿기까지 탭을 3번 지나야 한다. 선택된 탭만 tabIndex=0 을 갖고 나머지는 -1 로 두어
//   Tab 한 번으로 탭 목록을 통과하고, 목록 안에서는 방향키로 옮긴다.
import { useEffect, useRef, type KeyboardEvent } from 'react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS, KRDS_HIT_AREA_MIN } from '@/lib/focusRing';

/** 우측 패널 탭 식별자. 값은 DOM id(`review-tab-*`/`review-panel-*`) 접미사로도 쓰인다. */
export type ReviewSideTab = 'objects' | 'meta' | 'issues';

/** 탭 순서 = 사양의 옵션 순서(객체 / 메타 / 이슈). 방향키 이동 순서의 진실원이기도 하다. */
export const REVIEW_SIDE_TABS: ReadonlyArray<{ value: ReviewSideTab; label: string }> = [
  { value: 'objects', label: '객체' },
  { value: 'meta', label: '메타' },
  { value: 'issues', label: '이슈' },
];

/** 탭 버튼 DOM id — 각 tabpanel 의 `aria-labelledby` 가 이 값을 가리킨다. */
export function reviewTabId(tab: ReviewSideTab): string {
  return `review-tab-${tab}`;
}

/** tabpanel DOM id — 각 탭 버튼의 `aria-controls` 가 이 값을 가리킨다. */
export function reviewPanelId(tab: ReviewSideTab): string {
  return `review-panel-${tab}`;
}

export interface ReviewSidePanelTabsProps {
  value: ReviewSideTab;
  onChange: (tab: ReviewSideTab) => void;
  /**
   * '이슈' 탭 배지에 표시할 미해결 문의 건수.
   *
   * ★배지를 탭에 두는 이유: 패널이 세로로 모두 보이던 구조에서는 문의 건수가 항상 눈에
   *   들어왔는데, 탭으로 접으면 다른 탭을 보는 동안 그 신호가 사라진다. 건수 자체는 '이슈'
   *   탭 안(`unresolved-inquiry-count`)이 계속 소유하고, 여기 배지는 탭이 가린 신호를
   *   되살리는 알림일 뿐이다.
   */
  unresolvedInquiries?: number;
}

export function ReviewSidePanelTabs({
  value,
  onChange,
  unresolvedInquiries = 0,
}: ReviewSidePanelTabsProps) {
  const tabRefs = useRef<Partial<Record<ReviewSideTab, HTMLButtonElement | null>>>({});
  // 방향키로 탭을 옮겼을 때만 포커스를 따라 옮긴다. 마우스 클릭·초기 렌더에서 focus() 를
  // 호출하면 사용자가 만지지 않은 요소로 포커스가 튄다.
  const moveFocusTo = useRef<ReviewSideTab | null>(null);

  useEffect(() => {
    const target = moveFocusTo.current;
    if (!target) return;
    moveFocusTo.current = null;
    tabRefs.current[target]?.focus();
  }, [value]);

  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    let nextIndex: number | null = null;
    if (e.key === 'ArrowRight') {
      nextIndex = (index + 1) % REVIEW_SIDE_TABS.length;
    } else if (e.key === 'ArrowLeft') {
      nextIndex = (index - 1 + REVIEW_SIDE_TABS.length) % REVIEW_SIDE_TABS.length;
    } else if (e.key === 'Home') {
      nextIndex = 0;
    } else if (e.key === 'End') {
      nextIndex = REVIEW_SIDE_TABS.length - 1;
    }
    if (nextIndex == null) return;
    e.preventDefault();
    const next = REVIEW_SIDE_TABS[nextIndex];
    if (!next) return;
    moveFocusTo.current = next.value;
    onChange(next.value);
  };

  return (
    <div
      className="flex shrink-0 border-b border-gray-200"
      role="tablist"
      aria-label="검수 우측 패널 탭"
      data-testid="review-side-tablist"
    >
      {REVIEW_SIDE_TABS.map((tab, idx) => {
        const selected = tab.value === value;
        return (
          <button
            key={tab.value}
            ref={(el) => {
              tabRefs.current[tab.value] = el;
            }}
            type="button"
            role="tab"
            id={reviewTabId(tab.value)}
            aria-selected={selected}
            aria-controls={reviewPanelId(tab.value)}
            tabIndex={selected ? 0 : -1}
            data-testid={`review-tab-${tab.value}`}
            onClick={() => onChange(tab.value)}
            onKeyDown={(e) => handleKeyDown(e, idx)}
            className={cn(
              'flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2 text-label font-semibold',
              // KRDS 최소 터치 타깃(44px). 공용 `components/common/Tabs` 와 같은 하한이며,
              // 라벨링 화면 탭(py-2 만 → 38px)보다 이 한 축만 높다.
              KRDS_HIT_AREA_MIN,
              KRDS_FOCUS,
              selected
                ? 'text-primary-700 border-b-2 border-primary-500'
                : 'text-gray-500 hover:text-gray-900',
            )}
          >
            {tab.label}
            {tab.value === 'issues' && unresolvedInquiries > 0 && (
              <span
                data-testid="review-tab-issues-badge"
                className="inline-flex min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold text-white"
                aria-label={`미해결 문의 ${unresolvedInquiries}건`}
              >
                {unresolvedInquiries}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
