// [@design INT-013]
/**
 * 스타일 격리 앵커 클래스 — 「여기부터는 저작도구 영역」을 가리키는 **계약 어휘**.
 *
 * 포털 채널에서 우리 CSS 는 Host 문서 `<head>` 에 통째로 꽂힌다. 그래서 전역 리셋이 그대로
 * 나가면 Host(KRDS) UI 를 깨뜨린다 — 실제로 깨졌다(근거 전문은 `styles/global.css` 헤더).
 * 그 리셋의 적용 범위를 이 클래스 하위로 좁히는 것이 격리의 전부다.
 *
 * ★ **우리가 소유한 요소에만 붙인다.** Host 가 만든 요소(`.klid-authoring-slot`)에 기대면
 *   상대가 이름을 바꿀 때 «조용히» 깨지고 우리 시험으로는 잡히지 않는다.
 *
 * ⚠ **이 값을 아는 곳이 셋이다** — 이 파일 · `styles/global.css` · 포털 채널 후처리
 *   (`postcss/scope-portal-base-layer.js`, Node 에서 평가돼 TS 를 import 하지 못한다).
 *   셋이 갈리면 격리가 조용히 풀리므로 회귀 가드
 *   (`styles/__tests__/portalBaseLayerScoping.test.ts`)가 **값이 같다**를 단언한다.
 */
export const PORTAL_EMBED_ANCHOR_CLASS = 'klid-portal-embed';
