// 메타 탭 도움말 — 설명문을 한꺼번에 여닫는 토글 하나와 그 상태를 나르는 통로.
// [@design SCREEN-005] [@design SCREEN-019]
//
// <h3>왜 구역마다 두지 않고 <b>패널 머리에 하나</b>인가</h3>
// 메타 탭에는 접이식 구역이 여섯~일곱 개다. 구역마다 물음표 버튼을 달면 폭 360px 패널에 버튼이
// 일곱 개 서고, 그 버튼들이 도로 자리를 차지해 「자리를 너무 차지한다」는 지적을 스스로 되풀이한다.
// 도움말을 보려는 사람은 대개 화면 전체가 처음이지 한 구역만 처음인 것이 아니므로, 한 번 눌러
// 전부 펼치는 쪽이 조작 수도 적다.
//
// ★이 토글의 <b>자리(패널 머리 · 버튼 하나)와 기본값은 사양이 규정한다</b> —
//   `SCREEN-019` v52(도움말 버튼을 메타 탭 부품으로 명시) · `UI-157` v3(토글의 소유자는 카드가
//   아니라 패널이다). 값을 여기 옮겨 적지 않는다 — 옮겨 적은 사본은 사양이 바뀌어도 컴파일도
//   시험도 신호를 주지 않는다. 지금 동작이 궁금하면 그 ITEM 과 `metaHelpPreference` 를 본다.
//
// <h3>상태를 context 로 나르는 이유</h3>
// 설명문을 그리는 자리는 {@link MetaSection} 안쪽이고, 그 사이에 패널이 여러 겹 끼어 있다.
// prop 으로 내리면 일곱 패널이 전부 이 값을 받아 그대로 넘기는 배관이 되고, 한 곳만 빠뜨려도
// <b>그 구역의 설명만 영영 안 열리는</b> 조용한 실패가 된다.
//
// a11y: 아이콘만 있는 버튼이라 이름을 반드시 준다. 지금 켜져 있는지는 {@code aria-pressed} 가 알린다
//   (창의 도움말 토글과 같은 규약 — 두 자리의 <b>모양</b>은 같고 기본값만 반대다).

import { createContext, useContext, type ReactNode } from 'react';
import { HelpCircle } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { HELP_TOGGLE_LABEL } from './annotationWording';

/**
 * 메타 탭 설명문이 지금 보이는가.
 *
 * ★기본값이 {@code false} 다 — 제공자를 두지 않은 화면(포털 작업 탭)에서는 설명문이 그려지지
 * 않는다. 그 화면은 설명문을 넘기지 않으므로 관측되는 차이가 없다.
 */
const MetaHelpContext = createContext<boolean>(false);

export function useMetaHelpVisible(): boolean {
  return useContext(MetaHelpContext);
}

export function MetaHelpProvider({
  visible,
  children,
}: {
  visible: boolean;
  children: ReactNode;
}) {
  return <MetaHelpContext.Provider value={visible}>{children}</MetaHelpContext.Provider>;
}

/**
 * 도움말 토글 버튼 — 창의 제목 표시줄 토글과 <b>같은 모양</b>이다(물음표 아이콘만·같은 크기·
 * 같은 포커스 링). 두 자리에서 같은 일을 하는 버튼이 서로 달라 보이면 같은 기능으로 읽히지 않는다.
 */
export function MetaHelpToggleButton({
  visible,
  onToggle,
}: {
  visible: boolean;
  onToggle: () => void;
}) {
  const label = visible ? HELP_TOGGLE_LABEL.hide : HELP_TOGGLE_LABEL.show;
  return (
    <button
      type="button"
      onClick={onToggle}
      data-testid="meta-help-toggle"
      aria-label={label}
      aria-pressed={visible}
      title={label}
      className={cn(
        'inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-sm text-gray-600 hover:bg-gray-100',
        KRDS_FOCUS,
      )}
    >
      <HelpCircle aria-hidden="true" className="h-4 w-4" />
    </button>
  );
}
