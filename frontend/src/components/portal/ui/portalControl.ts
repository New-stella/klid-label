/**
 * 포털 채널 컨트롤 클래스 조리법 — **포털 화면만 쓴다.**
 *
 * <h3>왜 관제 공통 컴포넌트를 쓰지 않나</h3>
 * `components/common/*` 은 관제 화면 145개가 함께 쓴다. 포털 모양을 그쪽에 넣으면 관제 화면이
 * 같이 바뀐다(사용자 확정 구속: **관제향 화면·컴포넌트 불변**). 그래서 포털은 자기 계층을 갖는다.
 *
 * <h3>색은 여기서 정하지 않는다</h3>
 * 아래 조리법이 쓰는 `primary-*`·`gray-*` 는 **채널이 값을 정하는 토큰 이름**이다. 포털 산출물
 * (`VITE_BUILD_CHANNEL=portal`)에서는 DS-002 값(코발트 `#2e45dc`·slate 쿨톤)으로, 관제 산출물
 * 에서는 DS-001 값(KRDS `#256ef4`)으로 해석된다 — `design-tokens/channel.js`.
 *
 * ⚠ **이름이 같다고 같은 색이 아니다**(`DS-002.dont_rules`). 값을 하드코딩해 두 축을 섞지 말 것.
 *
 * <h3>DS-002 가 관제와 갈리는 형태 축</h3>
 * <ul>
 *   <li><b>명령(버튼)은 캡슐</b> — `rounded-pill`. 내용 상자(입력·페이저 셀)는 `rounded-input`(8),
 *       표면(카드)은 `rounded-surface`(16), 조용한 사각 태그는 `rounded-tag`(6).</li>
 *   <li><b>굵기 상한 600</b> — 위계는 크기와 색으로 만든다. 700 이상 굵기 유틸을 쓰지 않는다
 *       (회귀 가드가 포털 소유 소스를 전수 스캔해 강제한다 — 주석에 그 클래스명을 적는 것도
 *       위반으로 잡히므로 이름을 쓰지 않고 단수로 적는다).</li>
 *   <li><b>평면이 출발점</b> — 그림자는 기본 없음이고 띄우는 것이 예외다.</li>
 * </ul>
 *
 * @design DS-002
 * @design SCREEN-033
 */

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

/**
 * 조작 최소 높이 — **40px(dense desktop 하한)**.
 *
 * ★시안(SD-026)은 작은 버튼을 36px 로 그렸고 그것을 「확정 관례」로 적어 뒀다. 여기서는 40 으로
 * 올린다 — 인계 문서가 경계한 방향(*"줄이면 접근성 후퇴"*)의 반대이고, 같은 채널에서 이동 탭을
 * 44→48 로 **키운** 선례가 이미 있다. 시안보다 나쁜 쪽으로 가지 않는다.
 *
 * ⚠ 44(`KRDS_HIT_AREA_MIN`)를 쓰지 않는 이유는 이 화면의 표 행이 조작을 셋씩 담아 44 를 강제하면
 *   행 높이가 목록 성격을 잃기 때문이다. 24×24(WCAG 2.2 AA) 는 여유 있게 넘는다.
 */
export const PORTAL_HIT_MIN = 'min-h-10';

/** 눌림 피드백 — 상호작용 상태라 keyframes 가 아니라 transition 이어야 중간에 끊긴다. */
const PRESS = 'transition-transform duration-fast ease-standard active:scale-[0.96]';

/**
 * 모든 조작의 공통 골격. 캡슐 + 포커스 링 + 눌림 + 최소 높이.
 *
 * ★**`aria-disabled` 도 함께 다룬다.** 못 누르는 사유를 그 자리에서 읽혀야 하는 조작은 native
 *  `disabled` 를 쓸 수 없다 — Tab 순서에서 통째로 빠져 **왜 못 누르는지 알 길이 사라진다**
 *  (WCAG 2.1.1). 그래서 포커스는 살리고 포인터만 막는다. 키보드 활성화는 호출부가 막는다.
 *  ⚠ 이렇게 두면 **hover 를 따로 눌러 둘 필요가 없다** — 포인터 이벤트가 오지 않아 hover 자체가
 *    발생하지 않는다. 호출부에 `hover:bg-*` 를 덧대지 말 것(표 안이면 행 hover 가드에도 걸린다).
 */
const BASE = cn(
  'inline-flex items-center justify-center gap-1.5 rounded-pill px-4',
  'text-btn-label font-medium whitespace-nowrap',
  'disabled:cursor-not-allowed',
  'aria-disabled:pointer-events-none aria-disabled:cursor-not-allowed',
  PORTAL_HIT_MIN,
  PRESS,
  KRDS_FOCUS,
);

export type PortalButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

/**
 * 조작 클래스.
 *
 * ⚠ **비활성을 흐림(opacity)으로 처리하지 않는다** — 시안 근거: 채도를 뺀 면(`gray-100` 배경 +
 *   `gray-600` 글자)이 대비 5.65:1 로 AA 를 통과하는데, 주버튼에 `opacity-50` 을 걸면 글자가
 *   면과 함께 옅어져 읽히지 않는다.
 */
export function portalButton(
  variant: PortalButtonVariant = 'secondary',
  extra?: string,
): string {
  const skin: Record<PortalButtonVariant, string> = {
    // 주 명령. hover/pressed 는 스케일 60/70 단이며 채널이 값을 정한다.
    primary: cn(
      'bg-primary-500 text-white transition-colors duration-fast',
      'hover:bg-primary-600 active:bg-primary-700',
      'disabled:bg-gray-100 disabled:text-gray-600 disabled:hover:bg-gray-100',
      'aria-disabled:bg-gray-100 aria-disabled:text-gray-600',
    ),
    // 경계로 형태를 드러내는 보조 명령. 경계는 `border-strong` 축(gray-500)이라 3:1 을 넘는다.
    secondary: cn(
      'border border-gray-500 bg-white text-gray-700 transition-colors duration-fast',
      'hover:bg-gray-50 active:bg-gray-100',
      'disabled:border-gray-300 disabled:bg-white disabled:text-gray-500',
      'aria-disabled:border-gray-300 aria-disabled:bg-white aria-disabled:text-gray-500',
    ),
    // 표면 위에 얹히는 조용한 명령. 경계가 없어 hover 로만 형태가 드러난다.
    ghost: cn(
      'text-gray-700 transition-colors duration-fast',
      'hover:bg-gray-100 active:bg-gray-200',
      'disabled:text-gray-400 disabled:hover:bg-transparent',
    ),
    // 되돌릴 수 없는 명령. 색만으로 위험을 말하지 않으므로 호출부가 아이콘·문구를 함께 둔다.
    danger: cn(
      'border border-danger-500 bg-white text-danger-600 transition-colors duration-fast',
      'hover:bg-danger-50 active:bg-danger-100',
      'disabled:border-gray-300 disabled:bg-white disabled:text-gray-500',
    ),
  };
  return cn(BASE, skin[variant], extra);
}

/**
 * 작은 조작 — 표 행 안처럼 밀도가 필요한 자리.
 * 높이는 그대로 40 을 지키고 **가로 여백과 글자 크기만** 줄인다(hit area 를 깎지 않는다).
 */
export function portalButtonSm(
  variant: PortalButtonVariant = 'secondary',
  extra?: string,
): string {
  return portalButton(variant, cn('px-3 text-caption', extra));
}

/**
 * 표면(카드·표 셸·빈 상태) — `rounded-surface`(16) + 평면.
 *
 * ★그림자를 기본으로 두지 않는다. DS-002 는 평면이 출발점이고 띄우는 것이 예외다 — 관제 축이
 *   카드에 항상 `shadow-sm` 을 걸던 것과 갈리는 지점이다. 면 분리는 경계가 담당한다.
 */
export const PORTAL_SURFACE = 'rounded-surface border border-gray-200 bg-white';

/**
 * 표면 안에 놓이는 내용 상자 — `rounded-tile`(12).
 *
 * ★바깥(16)보다 작다. 중첩된 모서리는 «바깥 = 안쪽 + 여백» 이어야 눌린 것처럼 보이지 않는다.
 */
export const PORTAL_TILE = 'rounded-tile border border-gray-200 bg-white';
