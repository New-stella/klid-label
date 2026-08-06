// 우측 패널 '메타' 탭 공통 섹션 래퍼.
//
// 프레임 설명(FrameDescriptionPanel)과 시계열 메타(TimeseriesSidePanel)가 동일한
// 접이식 헤더·여백·토글·textarea·저장 버튼 스타일을 공유하도록 추출한 프리젠테이션 컴포넌트.
// 데이터/저장 로직은 각 패널이 자체 훅으로 유지하고, 여기서는 GUI 통일만 담당한다.
//
// a11y: 헤더 토글 버튼에 aria-expanded, 시맨틱 <button>. div onClick 미사용.

import { useState, type ReactNode } from 'react';

// ─────────────────────────────────────────────────────────────────────────────
// 라벨링 우측 패널은 앱의 나머지 화면과 같은 <b>라이트 톤</b>이다.
//
// 컨트롤은 공통 컴포넌트(`components/common/{Button,Textarea,Select,Checkbox}`)를 그대로 쓰고
// 색 override 를 붙이지 않는다 — 공통 컴포넌트의 기본값이 곧 정답이다.
// (구 상수 META_DARK_TEXTAREA_CLASS / META_DARK_SELECT_CLASS / META_DARK_CHECKBOX_CLASS /
//  META_DARK_SAVE_BUTTON_CLASS / META_DARK_CONTROL_LABEL_CLASS 는 다크 톤을 덮기 위한
//  override 였으므로 라이트 전환과 함께 폐기했다. 색 override 를 다시 만들지 말 것 —
//  공통 컴포넌트에 색을 덧칠하면 twMerge 가 기본 크기 토큰까지 삼키는 함정이 되살아난다.
//  자세한 내용은 `__tests__/CommonControlFontSize.test.tsx` 참조.)
// ─────────────────────────────────────────────────────────────────────────────

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
    <div className="border-t border-gray-200" data-testid="meta-section">
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        className="w-full flex items-center justify-between px-3 py-2 text-label font-semibold text-gray-500 uppercase tracking-wide hover:bg-gray-50 transition-colors"
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
