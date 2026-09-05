/**
 * 포털 채널 내비게이션 선언 — **본문 상단 이동 탭의 단일 진실원**.
 *
 * <h3>왜 이 파일이 있나</h3>
 * 포털 채널의 저작도구 화면은 Host 화면 안에서 실행된다. **Host 가 머리 영역과 좌측 주 메뉴를
 * 둘 다 소유**하고 저작도구는 그 아래 **본문만** 그린다. 그래서 우리는 자기 좌측 레일을 두지
 * 않고(두면 왼쪽 레일이 둘이 되어 Host 화면과 부딪힌다) 목적지를 **본문 상단 가로 탭**으로
 * 오간다. 그 탭 영역이 셸 사양의 `portal-content-tabs`(자리 `inline_tabs` · 표시 `tabs` ·
 * 가로 · 전 기기 · 단계 1→1)이고, 항목의 출처가 포털 내비게이션 정의다.
 *
 * ★**목록은 여기에만 적는다.** 화면 쪽에 다시 나열하면 두 곳이 되고, 목적지가 늘거나 라벨이
 *   바뀔 때 한쪽만 갱신돼 조용히 어긋난다. 내부 채널이 같은 이유로 `routeAccess.ts` 한 곳에서
 *   메뉴와 라우트 인가를 함께 파생시키는 것과 같은 관례다.
 *
 * ★★**Host 메뉴에 우리 하위 목적지를 등재하지 않는다** — 등재하면 이동 수단이 둘이 되고,
 *   목적지가 늘 때마다 **상대 배포가 필요**해진다. 본문 상단 탭은 Host 자신도 쓰는 패턴이다.
 *
 * <h3>여기 적지 않는 것 — 탭에 뜨지 않는 화면</h3>
 * 아래 둘은 목적지가 아니라 **목록에서 행을 눌러 들어가는 자리**이고 몰입 편집 화면이라
 * 이동 탭을 두지 않는다. 그래서 이 목록에 **없다**.
 *
 * <ul>
 *   <li>포털 라벨링(`/portal/label/:id`) — 목록 행에서 진입한다. 편집 중 이탈을 부른다.</li>
 *   <li>업로드 영상 마킹(`/portal/uploads/:uldSn/marking`) — 특정 자산에 매인 화면이고
 *       영상 재생이 세로를 차지한다.</li>
 * </ul>
 *
 * ⚠ `/portal/uploads/:uldSn/label`(업로드 자산 라벨링)은 **폐기된 목적지**다 — 포털 라벨링
 *   화면으로 합쳐진다(별도 단계). 여기서는 **탭에 넣지 않는 것**까지만 지킨다.
 *
 * ⚠⚠ 노출 판정은 **허용 목록**이다 — 이 목록에 있는 경로에서만 탭이 뜬다. 「뜨지 않을 곳」을
 *   따로 나열하면 그것이 두 번째 목록이 되어, 화면이 늘 때 그쪽을 빠뜨리는 순간 몰입 편집
 *   화면에 탭이 새어 나온다. 새 목적지는 **여기 추가해야만** 탭에 뜬다(fail-closed).
 *
 * @design SHELL-002
 * @design NAV-002
 */

export interface PortalContentTab {
  /** 내비게이션 정의의 노드 키 — 화면·설계와 이어 보는 결합 키다. */
  key: string;
  /** 탭에 보이는 라벨. */
  label: string;
  /** 절대 경로. 기준 경로(basename)는 라우터가 붙이므로 여기 적지 않는다. */
  path: string;
}

/** 본문 상단 이동 탭에 뜨는 목적지 — 순서가 곧 탭 순서다. [@design NAV-002] */
export const PORTAL_CONTENT_TABS: readonly PortalContentTab[] = Object.freeze([
  // 내 작업 — 데이터마트 영상 목록. [@design SCREEN-028]
  Object.freeze({ key: 'portal-home', label: '내 작업', path: '/portal' }),
  // 내 업로드 — 본인 자산 업로드·목록. [@design SCREEN-033]
  Object.freeze({ key: 'portal-uploads', label: '내 업로드', path: '/portal/uploads' }),
  // 증강 — 업로드 영상 증강 요청·결과. [@design SCREEN-044]
  Object.freeze({ key: 'portal-augment', label: '증강', path: '/portal/augment' }),
]);

/** 경로 비교용 정규화 — 끝의 `/` 하나만 걷어낸다(`/portal/` 과 `/portal` 은 같은 자리다). */
function normalize(pathname: string): string {
  if (pathname.length > 1 && pathname.endsWith('/')) return pathname.slice(0, -1);
  return pathname;
}

/**
 * 지금 있는 자리의 목적지 — **정확히 일치할 때만** 돌려준다. 없으면 `undefined`.
 *
 * 이 함수 하나가 두 가지를 함께 정한다: ①탭 영역을 그리는가(목적지가 아니면 그리지 않는다)
 * ②어느 탭이 현재 위치인가. 판정을 둘로 나누면 「탭은 떴는데 아무것도 현재가 아니다」처럼
 * 서로 어긋난 상태가 생긴다(끝 슬래시 한 글자에서 실제로 갈렸다).
 *
 * ⚠ 접두 일치를 쓰면 `/portal/uploads/7/label` 이 `/portal/uploads` 에 걸려 몰입 편집 화면에
 *   탭이 새어 나온다.
 */
export function resolveActivePortalTab(pathname: string): PortalContentTab | undefined {
  const p = normalize(pathname);
  return PORTAL_CONTENT_TABS.find((t) => t.path === p);
}
