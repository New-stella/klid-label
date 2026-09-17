/**
 * 포털 모습 — 포털 채널 화면이 쓰는 KRDS 킷과 부모 포털의 토큰 한 벌. [@design INT-013] [@design DS-002]
 *
 * ★ 차례가 곧 규칙이다 — 부모 포털 진입점(KLID_Portal `src/main.tsx`)과 **같은 목록·같은 차례**다.
 *   뒤에 온 테마가 이겨야 하고, 시각보정(`klid-optical`)이 빠지면 테마의 계산식이 통째로 무효가 된다.
 *   차례를 바꾸면 창 안 여백·컨트롤 높이가 조용히 킷 기본값으로 돌아간다.
 *
 * ★★ **포털 채널에서만 불러온다.** 관제 화면에 실리면 안 된다 — 킷 CSS 가
 *   `html { font-size: 62.5% }`(1rem = 10px)를 전역에 깔아 관제 축의 Tailwind 치수를 전부 줄인다.
 *   그래서 이 모듈은 정적 import 로 어디에도 매달지 않고, 포털 라우트 트리가 **지연 로드**한다
 *   (`styles/loadPortalLook.ts`). 관제 산출물에는 이 청크가 실리지 않는다.
 *
 * ⚠ 포털 Host 문서에는 이미 같은 CSS 가 깔려 있다 — 그 위에 한 벌 더 얹는 것은 **같은 값의
 *   덮어쓰기라 무해**하고, 대신 단독 실행(개발 서버 · 스토리북 · 시험)에서도 화면이 같은 모습으로
 *   선다. 그것이 이 한 벌을 우리가 들고 있는 까닭이다(부모 저장소를 가리키면 배포에서 깨진다).
 */
import 'krds-react/dist/index.css';

import './portal/krds-theme.css';
import './portal/krds-focus.css';
import './portal/portal-base.css';
import './portal/klid-optical.css';

/* ★ 저작도구 화면의 짜임 — 부모 포털 `pages/workspace/authoring` 의 CSS 를 옮긴 것이다.
 *   **화면마다 부르지 않고 여기서 한 번** 싣는다 — 화면이 저마다 끌어오면 한쪽만 갱신될 때
 *   같은 클래스가 화면마다 달라진다(이 채널의 CSS 를 한 자리에 모으는 까닭).
 *   토큰(`--krds-*`)을 쓰므로 **테마 뒤**여야 한다. 클래스 이름이 전부 `klid-` 로 좁혀져 있어
 *   전역에 실려도 남의 화면에 닿지 않는다. */
import './portal/authoring-layout.css';
import './portal/augment-view.css';
import './portal/marking-view.css';
import './portal/labeling-view.css';
