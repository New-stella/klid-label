import { useId, type KeyboardEvent, type ReactNode } from 'react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export interface TabItem {
  value: string;
  label: ReactNode;
  disabled?: boolean;
}

export interface TabsProps {
  items: TabItem[];
  value: string;
  onChange: (value: string) => void;
  ariaLabel?: string;
  children?: ReactNode;
  className?: string;
  /**
   * 탭 배치 방향. 기본값 `horizontal` — 기존 호출부 동작은 그대로다.
   *
   * [@design SCREEN-009] `vertical` 은 좌측 세로 레일 배치용 variant다. 카탈로그의 Tabs(UI-009)는
   * 가로 배치를 전제로 서술돼 있으나, 영상 상세 화면의 확정 디자인은 좌측 레일에 세로로 둔다 —
   * 별도 컴포넌트를 신설하지 않고 이 orientation variant로 흡수한다(design-notes 권고).
   */
  orientation?: 'horizontal' | 'vertical';
}

export function Tabs({
  items,
  value,
  onChange,
  ariaLabel = '탭',
  children,
  className,
  orientation = 'horizontal',
}: TabsProps) {
  const baseId = useId();
  const vertical = orientation === 'vertical';

  const handleKey = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    // 방향키 축은 배치 방향을 따른다(WAI-ARIA Tabs 패턴) — 세로 레일에서 좌/우 키를 쓰면
    // 화면상 이동 방향과 어긋난다.
    const forwardKey = vertical ? 'ArrowDown' : 'ArrowRight';
    const backwardKey = vertical ? 'ArrowUp' : 'ArrowLeft';
    if (e.key !== forwardKey && e.key !== backwardKey) return;
    e.preventDefault();
    const dir = e.key === forwardKey ? 1 : -1;
    let next = index;
    for (let i = 0; i < items.length; i++) {
      next = (next + dir + items.length) % items.length;
      if (!items[next]?.disabled) break;
    }
    const nextItem = items[next];
    if (nextItem) onChange(nextItem.value);
  };

  return (
    <div className={cn('flex flex-col', className)}>
      <div
        role="tablist"
        aria-label={ariaLabel}
        aria-orientation={vertical ? 'vertical' : undefined}
        className={cn(
          vertical ? 'flex flex-col gap-0.5' : 'flex border-b border-gray-200',
        )}
      >
        {items.map((item, idx) => {
          const selected = item.value === value;
          const id = `${baseId}-tab-${item.value}`;
          const panelId = `${baseId}-panel-${item.value}`;
          return (
            <button
              key={item.value}
              id={id}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls={panelId}
              tabIndex={selected ? 0 : -1}
              disabled={item.disabled}
              onClick={() => onChange(item.value)}
              onKeyDown={(e) => handleKey(e, idx)}
              className={cn(
                // 탭 = 내비게이션 축 → ladder `nav-link`(17px/w500). 크기는 구 `text-sm` 과 동일.
                'inline-flex min-h-11 items-center text-nav-link transition-colors duration-100 disabled:opacity-40',
                KRDS_FOCUS,
                vertical
                  // 세로 레일: 강조 축이 아래 밑줄이 아니라 **좌측 3px 보더 + 틴트 배경**이다
                  // (design-main.css `.tab-rail [role="tab"]`).
                  ? 'w-full justify-start rounded-md border-l-[3px] px-4 py-2.5 text-left'
                  : '-mb-px border-b-2 px-4 py-2.5',
                selected
                  ? vertical
                    // ★세로 레일의 선택 탭은 **600**이다(시안 `.tab-rail label[for=...]:checked`).
                    //   `nav-link` ladder 가 싣는 500 을 명시적으로 덮는다 — 500 이면 비선택 탭이
                    //   `font-normal`(400)이라 굵기 차가 한 단뿐이라 레일에서 선택 표식이 약하다.
                    //   ⚠ 가로 variant 는 이 축이 아니다(아래 500 유지) — 밑줄이 선택을 말하므로
                    //     굵기까지 올리면 강조가 이중이 된다. 두 값을 통일하지 말 것.
                    ? 'border-primary-500 bg-primary-50 font-semibold text-primary-600'
                    : 'border-primary-500 text-primary-600 font-medium'
                  // ⚠ `font-normal` 은 장식이 아니라 **기존 대비 보존**이다 — 구 `text-sm` 은 weight 를
                  //   싣지 않아 비선택 탭이 400 이었다. `nav-link` 는 500 을 실으므로 명시하지 않으면
                  //   선택(500)/비선택(500) weight 가 같아져 강조 대비가 사라진다.
                  : vertical
                    // ★세로 레일의 비선택 탭은 **60단(gray-600)이 하한**이다 — 이 레일은 흰
                    //   배경이 아니라 연한 표면(선택 탭의 `bg-primary-50`·페이지 `gray-50`) 위에
                    //   놓이는데 50단은 그 위에서 4.01:1 로 AA(4.5) 미달이다(DS-001 do_rules v8).
                    //   가로 variant 는 흰 카드 위 밑줄 탭이라 50단(4.51)이 그대로 유효하므로
                    //   **두 값을 통일하지 말 것**.
                    ? 'border-transparent font-normal text-gray-600 hover:bg-gray-50 hover:text-gray-800'
                    : 'border-transparent font-normal text-gray-500 hover:text-gray-700 hover:border-gray-300',
              )}
            >
              {item.label}
            </button>
          );
        })}
      </div>
      {children && (
        <div
          role="tabpanel"
          id={`${baseId}-panel-${value}`}
          aria-labelledby={`${baseId}-tab-${value}`}
          className="pt-4"
        >
          {children}
        </div>
      )}
    </div>
  );
}
