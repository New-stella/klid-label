// 우측 패널 '메타' 탭 공통 섹션 래퍼.
//
// 프레임 설명(FrameDescriptionPanel)과 시계열 메타(TimeseriesSidePanel)가 동일한
// 접이식 헤더·여백·토글·textarea·저장 버튼 스타일을 공유하도록 추출한 프리젠테이션 컴포넌트.
// 데이터/저장 로직은 각 패널이 자체 훅으로 유지하고, 여기서는 GUI 통일만 담당한다.
//
// a11y: 헤더 토글 버튼에 aria-expanded, 시맨틱 <button>. div onClick 미사용.

import { useState, type ReactNode } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';

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
        className="w-full flex items-center justify-between px-3 py-2 text-label font-semibold text-gray-600 uppercase tracking-wide hover:bg-gray-50 transition-colors"
        aria-expanded={open}
      >
        <span>{title}</span>
        {/* 접힘/펼침 표식 — 상태는 버튼의 aria-expanded 가 이미 낭독한다. 아이콘을 또 읽히면
            중복 안내가 되므로 aria-hidden 을 유지한다(방향 셰브론). */}
        <span className="inline-flex text-gray-600" aria-hidden="true">
          {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
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

/** 값이 없을 때의 표식 — 빈 칸을 남기지 않는다. */
export const NO_VALUE_MARK = '—';

/**
 * 메타 패널 <b>읽기 전용</b> 라벨-값 행.
 *
 * 검수 화면은 메타를 고치지 않고 확인만 한다(값의 수정은 라벨링 화면이 담당하고 검수 승인
 * 시점에 동결된다). 그때 각 패널이 편집 컨트롤 대신 이 행을 쓴다 — ★비활성 컨트롤을 두지
 * 않고 <b>렌더 자체를 하지 않는</b> 이유는, 눌리는 모양인데 반응이 없으면 검수자가 고장으로
 * 읽기 때문이다.
 *
 * 값이 없으면 빈 칸을 남기지 않고 미입력 표식을 보여준다 — 빈 칸은 "값이 없다"와 "화면이
 * 값을 못 그렸다"를 구분해 주지 못한다.
 *
 * 보안: 값은 React 텍스트 노드로만 출력해 자동 escape 된다(CWE-79).
 */
export function MetaReadonlyField({
  label,
  value,
  hint,
  testId,
}: {
  label: string;
  value: string | null | undefined;
  /** 값 옆에 덧붙이는 보조 표기(예: 기본값). */
  hint?: string;
  testId?: string;
}) {
  const shown = value != null && value !== '' ? value : NO_VALUE_MARK;
  return (
    <div data-testid={testId}>
      <span className="block text-caption text-gray-500">{label}</span>
      <p className="whitespace-pre-wrap break-words text-body-md text-gray-900">
        {shown}
        {hint != null && hint !== '' && (
          <span className="ml-1 text-[10px] text-gray-500">{hint}</span>
        )}
      </p>
    </div>
  );
}

/**
 * 개인정보 판정(Y/N)의 화면 표시 문구 — 영상 축·프레임 축 <b>두 패널이 공유</b>한다.
 *
 * 두 패널에 각각 두면 한쪽만 바뀌어도 화면은 그럴듯하게 보인다. 값이 없으면 null 을 돌려
 * 읽기 전용 행이 미입력 표식을 쓰게 한다 — 판정하지 않은 것과 '아니오'로 판정한 것은 다르다.
 */
export function ynLabel(value: string | null | undefined): string | null {
  if (value === 'Y') return '예';
  if (value === 'N') return '아니오';
  return null;
}
