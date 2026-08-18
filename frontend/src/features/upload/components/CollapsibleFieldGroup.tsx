import { useId, useState, type ReactNode } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';

import { KRDS_FOCUS } from '@/lib/focusRing';
import { cn } from '@/lib/cn';

export interface CollapsibleFieldGroupProps {
  /** 접기 버튼에 보이는 묶음 이름 — 안의 fieldset legend 와 같은 낱말을 쓴다. */
  title: string;
  /** 접힌 상태에서도 보이는 보조 문구(선택 여부·미전송 안내 등). */
  summary?: ReactNode;
  /** 처음에 펼칠지 — 기본은 접힘(선택 묶음은 입력 부담을 낮추기 위해 접어 둔다). */
  defaultOpen?: boolean;
  children: ReactNode;
}

/**
 * 선택 입력 묶음을 접어 두는 래퍼. [@design SCREEN-027]
 *
 * <p>업로드 폼은 반드시 채우는 것이 식별 정보 네 항목뿐이고 나머지는 전부 선택이라, 처음부터 다
 * 펼치면 필수가 어디인지 묻히고 스크롤만 길어진다. 그래서 선택 묶음은 접어 두고 필요할 때만 연다.
 *
 * <p><b>내용을 조건부 렌더하지 않고 {@code hidden} 으로 숨긴다.</b> 언마운트하면 접는 순간
 * 입력 요소가 사라져 브라우저가 들고 있던 상태(스크롤·IME 조합·검증 메시지)가 날아가고, 무엇보다
 * "접혀 있으면 값이 지워진 것"으로 오해할 여지가 생긴다. {@code hidden} 은 실제로 감추면서
 * (브라우저가 {@code display:none} 적용) 값과 DOM 을 보존한다 — 클래스만으로 감추는 방식과 달리
 * 스타일이 빠진 환경에서도 노출되지 않는다.
 *
 * <p>접힘 상태는 {@code aria-expanded} 로 노출한다(스크린리더 + 테스트 공통 판정축).
 */
export function CollapsibleFieldGroup({
  title,
  summary,
  defaultOpen = false,
  children,
}: CollapsibleFieldGroupProps) {
  const [open, setOpen] = useState(defaultOpen);
  const contentId = useId();

  return (
    <div className="rounded-lg border border-gray-200">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={contentId}
        onClick={() => setOpen((v) => !v)}
        className={cn(
          'flex w-full items-center gap-2 rounded-lg px-4 py-3 text-left',
          'hover:bg-gray-50',
          KRDS_FOCUS,
        )}
      >
        {/* 방향 셰브론은 장식이므로 접근성 트리에서 뺀다 — 상태는 aria-expanded 가 전달한다.
            문자 글리프(▸/▾)를 쓰지 않는 이유는 폰트에 따라 이모지로 렌더돼 톤이 깨지기 때문이다. */}
        <span aria-hidden="true" className="inline-flex text-gray-600">
          {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
        </span>
        <span className="text-body font-medium text-gray-800">{title}</span>
        {/* hover 시 배경이 gray-50 이 되므로 보조 문구는 60단 이상을 쓴다(AA 4.5:1). */}
        <span className="text-sub font-normal text-gray-600">(선택)</span>
        {summary && <span className="ml-auto text-sub text-gray-600">{summary}</span>}
      </button>
      <div id={contentId} hidden={!open} className="border-t border-gray-200 p-4">
        {children}
      </div>
    </div>
  );
}

export default CollapsibleFieldGroup;
