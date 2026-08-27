/**
 * CSS 부트스트랩 — 폰트·전역 스타일 로드의 단일 지점.
 *
 * 진입점이 둘(독립 앱 `main.tsx` / Remote `remote/AuthoringRemote.tsx`)이라 두 곳에 import 를
 * 복제하면 한쪽만 갱신될 때 **채널별로 스타일이 갈린다**. 그래서 한 모듈로 모으고 두 진입점이
 * 이것을 import 한다. Remote 도 반드시 import 해야 한다 — Host 는 우리 스타일을 모른다.
 *
 * 회귀 가드: `styles/__tests__/bootstrapSingleSource.test.ts`
 */

// 폰트 자가호스팅(self-host) — npm 패키지의 로컬 woff2 만 사용, 런타임 폰트 CDN 요청 0.
// 두 CSS 모두 @font-face src 가 상대경로 woff2 이며 Vite 가 해시 에셋으로 번들한다.
// dynamic-subset: unicode-range 로 필요한 서브셋만 로드(font-display:swap 내장).
//
// Pretendard **GOV**(공공 배포판) — 선언 family 명은 'Pretendard GOV' 로 일반판과 다르다.
// 한글 260자는 일반판과 아웃라인·자폭까지 동일하고, 실제로 갈리는 것은 숫자 0-9·문장부호·
// 라틴 I W i j l w 48자다(I/l/1 혼동을 줄인 판). 표에 빽빽한 영상 ID·촬영일시의 판독성이
// 이 교체의 실익이며, **한글이 그대로인 것은 회귀가 아니라 정상**이다.
import 'pretendard-gov/dist/web/static/pretendard-gov-dynamic-subset.css';
import 'd2coding/d2coding-subset.css';

import './global.css';
