// 우측 패널 '메타' 탭 공통 섹션 래퍼.
//
// 프레임 설명(FrameDescriptionPanel)과 시계열 메타(TimeseriesSidePanel)가 동일한
// 접이식 헤더·여백·토글·textarea·저장 버튼 스타일을 공유하도록 추출한 프리젠테이션 컴포넌트.
// 데이터/저장 로직은 각 패널이 자체 훅으로 유지하고, 여기서는 GUI 통일만 담당한다.
//
// a11y: 헤더 토글 버튼에 aria-expanded, 시맨틱 <button>. div onClick 미사용.

import { useState, type ReactNode } from 'react';

/** 통일 textarea 클래스 — 다크 우측 패널용. */
export const META_TEXTAREA_CLASS =
  'w-full resize-y rounded border border-gray-600 bg-gray-800 text-gray-100 text-sm p-2 placeholder-gray-500 focus:outline-none focus:ring-1 focus:ring-primary-500 disabled:opacity-60';

/** 통일 저장 버튼 클래스. */
export const META_SAVE_BUTTON_CLASS =
  'w-full rounded bg-primary-600 text-white text-sm py-1.5 disabled:bg-gray-500 disabled:cursor-not-allowed hover:bg-primary-500 transition-colors';

export interface MetaSectionProps {
  /** 섹션 헤더 텍스트 */
  title: string;
  children: ReactNode;
  /** 초기 펼침 여부 (기본 펼침) */
  defaultOpen?: boolean;
}

/**
 * 접이식 메타 섹션 래퍼 — 헤더(토글) + 본문 컨테이너.
 * FrameDescriptionPanel·TimeseriesSidePanel 공통 사용으로 GUI 통일.
 */
export function MetaSection({ title, children, defaultOpen = true }: MetaSectionProps) {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <div className="border-t border-gray-700" data-testid="meta-section">
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        className="w-full flex items-center justify-between px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide hover:bg-gray-700/50 transition-colors"
        aria-expanded={open}
      >
        <span>{title}</span>
        <span className="text-gray-500" aria-hidden="true">
          {open ? '▾' : '▸'}
        </span>
      </button>

      {open && <div className="px-2 pb-2 space-y-2">{children}</div>}
    </div>
  );
}

/** 통일 글자수 카운터 — textarea 하단 우측. */
export function MetaCharCount({ current, max }: { current: number; max: number }) {
  return (
    <div className="text-right text-[10px] text-gray-500" aria-hidden="true">
      {current}/{max}
    </div>
  );
}
