import { isPortalEmbedChannel } from '@/lib/buildChannel';

/**
 * 포털 셸의 «가로 정렬선» — 상단 이동 탭과 본문이 한 곳에서 받아 간다.
 *
 * 이 값을 두 곳이 따로 들면 한쪽만 고쳐질 때 탭과 본문의 시작선이 조용히 어긋난다
 * (`PortalLayout` 의 DS-002 주석이 "탭과 본문이 같은 정렬선을 공유한다"를 불변식으로 못
 * 박아 둔 자리다). 그래서 문자열을 여기 한 벌만 둔다.
 *
 * ## 채널마다 다른 이유 (2026-09-15 개발망 실측)
 *
 * - **관제 채널(독립 앱)** — 우리가 문서를 소유하므로 최대폭 1200 + 좌우 거터 24 를 «우리가»
 *   세운다. DS-002 의 `layout.max_content_width_px`.
 * - **포털 채널(임베드)** — Host 가 준 마운트 슬롯(`.klid-authoring-slot`)이 **이미 자기 좌우
 *   여백 24px 를 갖고 있고**, 그 슬롯 자체가 Host 페이지 폭 안에서 이미 좁혀져 있다
 *   (실측: 뷰포트 1470 → 슬롯 안쪽 1261). 여기서 우리가 거터와 최대폭을 한 번 더 세우면
 *   여백이 **두 벌로 겹쳐** 쓸 수 있는 폭이 그만큼 깎인다(실측: 좌우 각 54.5px 추가 잠식 —
 *   최대폭 1200 이 만든 자동 여백 30.5 + 우리 거터 24).
 *   ⇒ 임베드에서는 **아무것도 세우지 않고 슬롯 안쪽 폭을 그대로 채운다.**
 *
 * ⚠ 최대폭을 임베드에서 유지하는 쪽으로 되돌리지 말 것 — Host 슬롯이 이미 읽기 폭을
 *   책임진다. 되돌리면 위 실측 그대로 여백이 다시 겹친다.
 *
 * @design SHELL-002
 * @design DS-002
 */
export const PORTAL_SHELL_ALIGN_STANDALONE = 'mx-auto w-full max-w-wrap px-4 md:px-column';

/** 임베드 — 거터도 최대폭도 두지 않는다. 위 주석의 "두 벌로 겹친다" 참조. */
export const PORTAL_SHELL_ALIGN_EMBED = 'w-full';

/**
 * 지금 산출물의 채널에 맞는 가로 정렬선 클래스.
 *
 * `isPortalEmbedChannel()` 과 같은 이유로 모듈 상수가 아니라 «함수»다 — 테스트가
 * `vi.stubEnv` 로 채널을 바꿔 가며 두 갈래를 모두 검증할 수 있어야 한다.
 */
export function portalShellAlign(): string {
  return isPortalEmbedChannel() ? PORTAL_SHELL_ALIGN_EMBED : PORTAL_SHELL_ALIGN_STANDALONE;
}
