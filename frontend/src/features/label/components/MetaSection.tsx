// 우측 패널 '메타' 탭 공통 섹션 래퍼.
// [@design SCREEN-019] [@design SCREEN-005] [@design SD-005] [@design DS-001]
//
// 프레임 설명(FrameDescriptionPanel)과 시계열 메타(TimeseriesSidePanel)가 동일한
// 접이식 헤더·여백·토글·textarea·저장 버튼 스타일을 공유하도록 추출한 프리젠테이션 컴포넌트.
// 데이터/저장 로직은 각 패널이 자체 훅으로 유지하고, 여기서는 GUI 통일만 담당한다.
//
// a11y: 헤더 토글 버튼에 aria-expanded, 시맨틱 <button>. div onClick 미사용.

import { useState, type ReactNode } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';

import { useMetaHelpVisible } from './metaHelp';

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
  /**
   * 구역 제목 아래 설명 한 줄 — 그 값이 어디서 왔고 어디로 나가는지 알린다.
   *
   * ★<b>도움말이 켜져 있을 때만</b> 그린다(기본은 감춤). 폭 360px 패널에서 이 한 문장이 두세
   * 줄로 접혀 값을 밀어내기 때문이며, 그 자리 문제를 사용자가 직접 지적했다(2026-09-15 확정).
   * 토글은 메타 탭 머리에 하나 있고 전 구역을 한꺼번에 여닫는다 — {@code metaHelp.tsx} 참조.
   */
  description?: string;
  children: ReactNode;
  /** 초기 펼침 여부 (기본 펼침) */
  defaultOpen?: boolean;
}

/**
 * 접이식 메타 섹션 래퍼 — 헤더(토글) + 본문 컨테이너.
 * FrameDescriptionPanel·EnvironmentMetaPanel 등 메타 탭 전 섹션이 공통 사용해 GUI 를 통일한다.
 *
 * <h3>시안 대조 (2026-09-15) — 값은 전부 디자인 체계 단계다</h3>
 * 시안(`.rv-panel-section` · `.meta-collapsible-head`)과 구 구현이 갈려 있어 같은 화면이
 * 시안보다 잘고 흐리게 보였다. 아래는 시안 CSS 원본 ↔ 우리 토큰 대응이며 <b>임의 px·임의 색이
 * 아니다</b>(임의 값을 쓰면 토큰 가드가 잡는다).
 *
 * <ul>
 *   <li>구역 안쪽 여백 {@code padding: var(--sp-md)} = 16px → {@code p-4}</li>
 *   <li>구역 구분선 {@code border-bottom: 1px solid var(--n-1)} = #E6E8EA → {@code border-b border-gray-100}
 *       (구 구현은 <b>위쪽</b> 1px {@code gray-200}#CDD1D5 — 방향도 색도 달랐다)</li>
 *   <li>구역 제목 {@code .t-title-sm} 17px/600 + {@code color: var(--n-9)} #1E2124
 *       → {@code text-title-sm text-gray-900} (구 구현은 {@code text-label} 14px/600 +
 *       {@code gray-600} + <b>대문자 변환·자간</b>까지 붙어 있었다 — 시안에 없다)</li>
 *   <li>제목 아래 여백 {@code margin-bottom: var(--sp-sm)} = 8px → {@code mb-2}</li>
 *   <li>셰브론은 시안에서 제목 <b>앞</b>이고 16px 이며 색이 {@code var(--n-5)} 다</li>
 * </ul>
 *
 * ⚠ 「본문 17px 이상」 규정은 <b>표 본문 셀</b>(읽는 데이터)에 대한 확정이다. 메타 패널은 그 축이
 * 아니며 여기 쓰는 {@code body-sm}(15px)·{@code caption}(14px)은 디자인 체계가 각각
 * 「보조 설명·메타」·「캡션」 용도로 정의한 정규 단계다 — 표·목록 화면에는 적용하지 않는다.
 *
 * ★이 래퍼는 <b>라벨링·검수·포털 작업</b> 세 화면이 함께 쓴다. 여기서 크기를 바꾸면 세 화면이
 * 같이 바뀌며 그것이 의도다(같은 자리가 화면마다 다른 크기로 보이면 대응시키지 못한다).
 */
export function MetaSection({
  title,
  description,
  children,
  defaultOpen = true,
}: MetaSectionProps) {
  const [open, setOpen] = useState(defaultOpen);
  const helpVisible = useMetaHelpVisible();
  const showDescription = open && helpVisible && description != null && description !== '';

  return (
    <div className="border-b border-gray-100 p-4" data-testid="meta-section">
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        className="flex w-full items-center gap-1.5 text-left text-title-sm text-gray-900 transition-colors hover:text-primary-700"
        aria-expanded={open}
      >
        {/* 접힘/펼침 표식 — 상태는 버튼의 aria-expanded 가 이미 낭독한다. 아이콘을 또 읽히면
            중복 안내가 되므로 aria-hidden 을 유지한다(방향 셰브론).
            ★시안은 이 표식을 제목 <b>앞</b>에 둔다 — 구 구현은 반대쪽 끝이었다. */}
        <span className="inline-flex shrink-0 text-gray-500" aria-hidden="true">
          {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
        </span>
        <span className="min-w-0 flex-1">{title}</span>
      </button>

      {/* 시안 {@code .meta-panel-help} — {@code .t-caption} 14px + {@code var(--n-5)} #6D7882 +
          아래 8px. 제목 <b>바로 아래</b>가 자리다(구 구현은 구역 <b>끝</b>에 11px 로 있었다). */}
      {showDescription && (
        <p className="mb-2 mt-2 text-caption text-gray-500" data-testid="meta-section-description">
          {description}
        </p>
      )}

      {/* 시안 {@code .meta-field { margin-bottom: var(--sp-md) }} = 필드 행 사이 16px. */}
      {open && <div className="mt-2 space-y-4">{children}</div>}
    </div>
  );
}

/**
 * 통일 글자수 카운터 — textarea 하단 우측.
 *
 * ⚠ 구 구현은 10px 이었다. 시안에 10px 단계가 없고 디자인 체계 사다리의 최소 단계가 14px
 * ({@code caption})이라 그 단계로 올린다 — 잔글씨를 되살리지 말 것.
 */
export function MetaCharCount({ current, max }: { current: number; max: number }) {
  return (
    <div className="text-right text-caption text-gray-500" aria-hidden="true">
      {current}/{max}
    </div>
  );
}

/** 값이 없을 때의 표식 — 빈 칸을 남기지 않는다. */
export const NO_VALUE_MARK = '—';

/**
 * 검수 화면이 쓰는 빈 값 문구 — 화면정의서 SCREEN-019 문구표 「검수 화면의 빈 값」.
 * 촬영환경만 「미입력」을 따로 쓴다(같은 문구표).
 */
export const NOT_FILLED_TEXT = '아직 채우지 않았습니다';
export const NOT_ENTERED_TEXT = '미입력';

/**
 * 개인정보 판정 세 값을 <b>한 줄</b>로 잇는다 — 검수 화면 전용 표기.
 *
 * <p>사양(SCREEN-019)은 읽기 전용에서 「익명 · 가명 · 개인정보 포함」 라벨 하나에 값을 같은
 * 차례로 이어 붙이라고 정한다(보기 「예 · 아니오 · 아니오」). 영상 축·프레임 축 두 패널이
 * 같은 규칙이라 <b>여기 한 곳</b>에 둔다 — 두 벌로 두면 한쪽만 고쳐져도 화면은 그럴듯해 보인다.
 *
 * <p>세 값이 <b>모두</b> 비어 있으면 {@code null} 을 돌려 읽기 전용 행이 빈 값 문구를 쓰게 한다.
 * 「— · — · —」로 채우면 «판정이 없다»가 «판정이 셋 다 미상이다»처럼 읽혀 잡음만 남는다.
 * 일부만 비면 자리를 유지해야 어느 항목이 빈 것인지 알 수 있으므로 그 자리에 표식을 둔다.
 */
export function joinPrivacyValues(values: readonly (string | null)[]): string | null {
  if (values.every((v) => v == null || v === '')) return null;
  return values.map((v) => (v != null && v !== '' ? v : NO_VALUE_MARK)).join(' · ');
}

/** 개인정보 판정 한 줄 묶음의 라벨 — 영상 축·프레임 축 공통(사양 SCREEN-019). */
export const PRIVACY_GROUPED_LABEL = '익명 · 가명 · 개인정보 포함';

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
  help,
  emptyText = NO_VALUE_MARK,
  testId,
}: {
  label: string;
  value: string | null | undefined;
  /** 값 옆에 덧붙이는 보조 표기(예: 기본값). */
  hint?: string;
  /** 값 <b>아래</b> 한 줄 보조 설명 — 시안 {@code .meta-help}. */
  help?: string;
  /** 값이 없을 때 대신 보일 문구. 기본은 표식 하나이며 화면이 사양 문구를 명시로 넘긴다. */
  emptyText?: string;
  testId?: string;
}) {
  const shown = value != null && value !== '' ? value : emptyText;
  // ★`help` 는 <b>도움말 축</b>이라 구역 설명문과 같은 토글을 탄다(사양 SCREEN-019).
  //   ⚠ 값 박스 <b>안</b>의 `hint`(예: 「기본값」)는 도움말이 아니라 <b>값의 일부</b>라 게이트하지
  //     않는다 — 그것을 숨기면 그 값이 사람이 정한 것인지 기본값인지 구분이 사라진다.
  //     두 축을 「일관성」을 이유로 합치지 말 것.
  const helpVisible = useMetaHelpVisible();
  return (
    <div data-testid={testId}>
      {/* 시안 {@code .meta-label} — {@code .t-label} 14px/600 + {@code var(--n-8)} #33363D + 아래 4px.
          구 구현은 {@code caption}(14px/<b>400</b>) + {@code gray-500} 이라 라벨이 값보다 흐렸다. */}
      <span className="mb-1 block text-label text-gray-800">{label}</span>
      {/* ★시안 {@code .meta-readonly-value} — 15px + {@code var(--n-8)} #33363D + 안쪽 여백 10px +
          배경 {@code var(--n-0)} #F4F5F6 + 모서리 {@code var(--radius-md)} 6px.
          구 구현에는 <b>박스가 아예 없었다</b> — 그래서 구역이 카드 덩어리가 아니라 줄 목록으로
          읽혔고, 그것이 「시안과 전혀 다르다」는 체감의 가장 큰 원인이었다. 박스를 빼지 말 것.
          (높이 10 + 15×1.6 + 10 = 44px 로 시안 {@code --hit-area} 와 같다.) */}
      <p className="whitespace-pre-wrap break-words rounded-md bg-gray-50 p-2.5 text-body-sm text-gray-800">
        {shown}
        {hint != null && hint !== '' && (
          // ⚠ 이 보조 표기는 값 박스 <b>안</b>에 있어 배경이 흰색이 아니라 gray-50 이다.
          //   그래서 바깥 보조 문장과 달리 gray-500 을 쓰면 4.13:1 로 AA(4.5:1)에 못 미친다
          //   (전역 대비 가드가 실제로 잡았다). 한 단 올려 5.77:1 로 통과시킨다 —
          //   박스를 넣으면서 같이 따라온 조건이며 두 자리의 색이 갈린 것은 의도다.
          <span className="ml-1 text-caption text-gray-600">{hint}</span>
        )}
      </p>
      {helpVisible && help != null && help !== '' && (
        // 시안 {@code .meta-help} — {@code .t-caption} 14px + {@code var(--n-5)} #6D7882 + 위 4px.
        <p className="mt-1 text-caption text-gray-500" data-testid="meta-field-help">
          {help}
        </p>
      )}
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
