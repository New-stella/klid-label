/**
 * 포털 업로드 영상 마킹 화면의 <b>진입 주소 조립</b> — 단일 지점.
 * [@design SCREEN-045] [@design NAV-002]
 *
 * ★ 마킹 화면으로 들어가는 자리는 <b>내 업로드 목록의 마킹 대기 행</b> 하나뿐이다.
 *   라벨링 화면에는 두지 않는다 — 라벨링을 하고 있다는 것은 이미 프레임 추출이 끝났다는 뜻이고
 *   그 시점의 재마킹은 제공하지 않으므로, 진입 자리를 두면 눌러 봐야 거절되는 자리가 되어
 *   회복 경로를 잘못 안내한다.
 * ★ 이 화면은 <b>목적지가 아니다</b> — 본문 상단 이동 탭에 넣지 않는다(특정 자산에 매인 몰입
 *   편집 화면이다). 탭 노출은 허용 목록으로 판정하므로 여기 주소를 더하지 않는 것으로 지켜진다.
 *
 * 주소 문자열을 목록·라우터·시험에 흩지 않으려고 이 자리에 모았다.
 */

/** 라우터에 등록되는 경로 패턴 — 포털 레이아웃 아래의 상대 경로다. */
export const PORTAL_UPLOAD_MARKING_ROUTE = 'uploads/:uldSn/marking';

/** 마킹 화면 진입 주소. 기준 경로(basename)는 라우터가 붙이므로 여기 적지 않는다. */
export function buildPortalUploadMarkingPath(uldSn: number): string {
  return `/portal/uploads/${uldSn}/marking`;
}
