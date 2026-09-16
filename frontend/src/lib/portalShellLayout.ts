import { isPortalEmbedChannel } from '@/lib/buildChannel';

/**
 * 포털 셸의 «가로 정렬선» — 상단 이동 탭과 본문이 한 곳에서 받아 간다.
 *
 * 이 값을 두 곳이 따로 들면 한쪽만 고쳐질 때 탭과 본문의 시작선이 조용히 어긋난다
 * (`PortalLayout` 의 DS-002 주석이 "탭과 본문이 같은 정렬선을 공유한다"를 불변식으로 못
 * 박아 둔 자리다). 그래서 문자열을 여기 한 벌만 둔다.
 *
 * ## ★두 채널이 같은 정렬선을 쓴다 (2026-09-16 — Host CSS 실측으로 확정)
 *
 * **Host 는 우리가 마운트되는 순간 슬롯 여백을 스스로 0 으로 만든다.**
 *
 * ```css
 * .klid-authoring-slot[data-state='mounted'] { padding: 0; }
 * ```
 *
 * 그 규칙에 Host 가 붙여 둔 주석이 계약을 그대로 말한다 —
 * *"저작도구가 뜬 자리 … iframe 은 제 바닥을 갖고 오므로 카드가 안쪽 여백을 두면 흰 테가 한 겹
 * 더 생긴다"* · *"여백은 탭 줄 · 콘텐츠 자리가 각자 갖는다"*.
 * 즉 **임베드 안쪽 여백은 우리 책임**이고, Host 자신의 저작도구 화면도 같은 값을 쓴다:
 * 탭 줄 `padding-inline: 24` · 콘텐츠 자리 `padding: 24` · 판 `max-width: 1152`(=1200−24×2) 가운데.
 * 그 셋의 합이 곧 아래 정렬선 문자열이라 **두 채널이 같은 값을 쓰는 것이 맞다.**
 *
 * ⚠⚠ **구 서술·구 동작 폐기 (2026-09-15 → 2026-09-16)** — *"포털 채널(임베드)은 Host 슬롯이
 *   이미 자기 좌우 여백 24px 를 갖고 있으므로 아무것도 세우지 않고 슬롯 안쪽 폭을 그대로
 *   채운다"* 는 **틀렸다.** 그 실측은 **마운트 전** 슬롯(안내문이 서는 상태)을 잰 것이다.
 *   마운트되면 위 규칙이 여백을 0 으로 만들어, 우리 화면이 카드 모서리에 그대로 붙었다
 *   (사용자 신고 — *"포털향 우리 저작도구가 컨테이너에 너무 딱 달라붙었다"*).
 *   ⚠ 「되돌리지 말 것」이라고 적혀 있던 그 지시문까지 함께 폐기다 — 근거가 된 실측이 무효다.
 *   되돌리면 여백이 다시 사라진다(겹치지 않는다 — Host 쪽이 0 이다).
 *
 * ⚠ **라벨링 편집기는 이 정렬선을 받지 않는다** — 화면 전체를 덮는 고정 판(`fixed inset-0`)이라
 *   셸 바깥이다. Host 도 같은 판단이다(*"라벨링 편집기는 여백 없이 흰 판으로 꽉 찬다"*).
 *   마킹은 셸 안이라 이 여백을 받고, 그것도 Host 쪽 값(24)과 같다.
 *
 * @design SHELL-002
 * @design DS-002
 */
export const PORTAL_SHELL_ALIGN_STANDALONE = 'mx-auto w-full max-w-wrap px-4 md:px-column';

/**
 * 임베드 — 독립 앱과 **같은 정렬선**이다. 위 ★ 절 참조.
 *
 * 값을 따로 선언하는 것은 「우연히 같다」와 「같아야 한다」를 구분하기 위해서다. 채널이 갈리는
 * 자리가 다시 생기면 여기만 고치면 되고, 갈리지 않는 동안에는 아래 상수 대조 시험이 두 값이
 * 같음을 못 박는다.
 */
export const PORTAL_SHELL_ALIGN_EMBED = PORTAL_SHELL_ALIGN_STANDALONE;

/**
 * 탭 줄이 영역 윗변에서 떼는 여백 — Host 의 `padding-block-start: var(--krds-padding-8)` 과 같은 24.
 *
 * ⚠ **임베드에서만 준다.** 독립 앱에는 탭 줄 위에 자체 머리 영역이 있어 그 아래로 또 24 를 떼면
 *   머리와 탭이 멀어진다(임베드에는 그 머리 영역이 없다 — Host 가 소유한다).
 */
export const PORTAL_TABS_TOP_EMBED = 'pt-column';

/**
 * 지금 산출물의 채널에 맞는 가로 정렬선 클래스.
 *
 * `isPortalEmbedChannel()` 과 같은 이유로 모듈 상수가 아니라 «함수»다 — 테스트가
 * `vi.stubEnv` 로 채널을 바꿔 가며 두 갈래를 모두 검증할 수 있어야 한다.
 */
export function portalShellAlign(): string {
  return isPortalEmbedChannel() ? PORTAL_SHELL_ALIGN_EMBED : PORTAL_SHELL_ALIGN_STANDALONE;
}

/** 탭 줄이 받을 윗 여백 클래스 — 임베드에서만 값이 있다. */
export function portalTabsTopPadding(): string {
  return isPortalEmbedChannel() ? PORTAL_TABS_TOP_EMBED : '';
}
