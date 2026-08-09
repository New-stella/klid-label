import { forwardRef } from 'react';
import { Check } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import {
  PROCESS_KIND_DESCRIPTION,
  PROCESS_KIND_ICON,
  PROCESS_KIND_LABEL,
  type ProcessKind,
} from '../types';

export interface ProcessKindCardProps {
  kind: ProcessKind;
  selected: boolean;
  onSelect: () => void;
  /** 비활성화 — 제출 처리 중이거나, 대상 영상이 파생영상이라 요청이 **영구히** 불가한 경우. */
  disabled?: boolean;
  /**
   * 비활성 사유 — 툴팁(`title`)으로 안내한다.
   *
   * 사양은 "이때는 요청 진입 자체가 막히고 사유가 툴팁으로 안내된다" 고 규정하는데 props_schema
   * 에는 이 자리가 없었다(사양 보강 필요). 문자열을 못 받으면 사용자는 왜 못 누르는지 알 수 없다.
   */
  disabledReason?: string;
  /** 로빙 tabindex — 선택(또는 진입점) 카드만 0, 나머지는 -1 (a11y radiogroup). */
  tabIndex?: number;
  /** 부모 radiogroup 의 화살표 키 탐색 핸들러. */
  onKeyDown?: (e: React.KeyboardEvent<HTMLButtonElement>) => void;
}

/**
 * 처리 종류 카드 — SCR-AUG-001 통합 단일 선택(라디오) UI.
 *
 * 증강 3종(WINTER/NIGHT/RAIN)과 해상도 변경(RESOLUTION)을 동일 그리드에서
 * 하나만 선택한다. 부모 radiogroup 안에서 `role="radio"` + `aria-checked` 로
 * 단일 선택 시맨틱을 표현한다. 라벨/아이콘/설명은 종류 상수 맵에서 도출한다.
 */
export const ProcessKindCard = forwardRef<
  HTMLButtonElement,
  ProcessKindCardProps
>(function ProcessKindCard(
  { kind, selected, onSelect, disabled, disabledReason, tabIndex, onKeyDown },
  ref,
) {
  // 픽토그램은 아이콘 컴포넌트 — 종류 상수 맵에서 도출한다(이모지 문자열 아님).
  const KindIcon = PROCESS_KIND_ICON[kind];

  return (
    <button
      ref={ref}
      type="button"
      role="radio"
      aria-checked={selected}
      tabIndex={tabIndex}
      onClick={onSelect}
      onKeyDown={onKeyDown}
      disabled={disabled}
      title={disabled ? disabledReason : undefined}
      data-testid={`process-kind-${kind}`}
      className={cn(
        'group relative flex flex-col items-start gap-2 rounded-lg border bg-white p-4 text-left transition-all',
        KRDS_FOCUS,
        'disabled:cursor-not-allowed disabled:opacity-50',
        selected
          ? 'border-primary-500 bg-primary-50 shadow-sm ring-1 ring-primary-300'
          : 'border-gray-200 hover:border-primary-300 hover:shadow-sm',
      )}
    >
      {selected && (
        <span className="absolute right-2 top-2 inline-flex h-5 w-5 items-center justify-center rounded-full bg-primary-600 text-white">
          <Check size={12} aria-hidden />
        </span>
      )}
      {/* 장식 픽토그램 — 종류는 바로 아래 라벨 텍스트가 말한다(색·아이콘만으로 정보를 주지 않는다). */}
      <span aria-hidden>
        <KindIcon
          className={cn('h-6 w-6', selected ? 'text-primary-600' : 'text-gray-500')}
        />
      </span>
      <span
        className={cn(
          'text-body-md font-semibold',
          selected ? 'text-primary-700' : 'text-gray-800',
        )}
      >
        {PROCESS_KIND_LABEL[kind]}
      </span>
      <span className="text-caption text-gray-600">
        {PROCESS_KIND_DESCRIPTION[kind]}
      </span>
    </button>
  );
});
