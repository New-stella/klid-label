// 서버가 내려준 <b>API 기준 경로</b>에 이 배포의 접두(컨텍스트 경로)를 결합한다.
// [@design API-114] [@design SCREEN-006] [@design SEQ-036]
//
// 왜 필요한가 — 서명 주소를 발급하는 쪽은 자신이 어느 컨텍스트 아래에 배포되는지 알 수 없다.
// 그래서 발급 응답의 `url` 은 접두를 뺀 API 기준 경로(`/api/v1/...`)이고, 최종 요청 주소를 만드는
// 것은 <b>소비 측 책임</b>이다. 이 결합을 빠뜨리면 요청이 우리 창구의 경로 공간을 벗어나 같은
// 오리진에 놓인 다른 시스템의 경로로 나가고, 영상이 오지 않는다.
//
// ★ 이 결함은 <b>앱이 루트에 서비스되는 배포에서는 드러나지 않는다</b> — `/api/v1` 이 그대로
//   맞아떨어지기 때문이다. 베이스 경로 아래에 놓인 배포에서만 나타나므로, 이 축을 검증하는
//   시험은 반드시 접두가 있는 조건을 재현해야 한다.
//
// 왜 대부분의 화면은 이 함수를 쓰지 않는가 — 다른 요청은 전부 axios(`apiClient`)를 거치고
// axios 가 `baseURL` 을 붙여 준다. 인증 헤더를 실을 수 없는 재생 요소(`<video src>`)만 서명
// 주소를 그대로 물기 때문에 axios 바깥으로 나가고, 그래서 이 결합을 스스로 해야 한다.
// (프레임 이미지는 axios 로 받아 blob URL 로 바꿔 쓰므로 이 축과 무관하다.)
//
// ★ 판정은 여기 한 곳이 소유한다 — 호출부가 접두를 다시 계산하면 두 번째 진실원이 되어
//   한쪽만 고쳐진 채 갈라진다.
import { apiClient } from './client';

/**
 * axios `baseURL` 의 꼬리 — 이 문자열을 떼어낸 나머지가 곧 배포 접두다.
 *
 * <p>서버가 주는 API 기준 경로도 같은 접두사로 시작하므로(`/api/v1/videos/...`), 이 값을 두 축의
 * 이음매로 쓴다. 값 자체는 `client.ts` 의 기본 `baseURL` 과 배포 설정(`VITE_API_BASE_URL`)이
 * 공유하는 관례다.
 */
const API_BASE_SUFFIX = '/api/v1';

/** 스킴 있는 절대 URL(`https://host/...`)과 프로토콜 상대(`//host/...`) 둘 다 잡는다. */
const ABSOLUTE_URL = /^(?:[a-z][a-z0-9+.-]*:)?\/\//i;

/**
 * 이 배포의 접두(컨텍스트 경로)를 구한다.
 *
 * <pre>
 *   '/api/v1'               → ''              (로컬·루트 배포)
 *   '/label-studio/api/v1'  → '/label-studio'
 *   'https://host/api/v1'   → 'https://host'
 * </pre>
 *
 * <p><b>fail-safe</b> — `baseURL` 이 그 꼴이 아니면 빈 문자열을 돌려준다. 접두를 알 수 없는
 * 상태에서 무언가를 지어내 붙이면 지금 동작하던 배포까지 깨뜨린다. 결합하지 않는 쪽이
 * 현행 유지이며, 그 경우 소비 측은 서버가 준 값을 그대로 쓴다.
 *
 * @param baseUrl 판정 대상. 기본값은 실제 요청이 쓰는 `apiClient` 의 `baseURL` 이다 —
 *                다른 데서 다시 읽으면 요청 주소와 재생 주소가 갈릴 수 있다.
 */
export function deployBasePath(
  baseUrl: string | undefined = apiClient.defaults.baseURL,
): string {
  if (typeof baseUrl !== 'string') return '';
  const trimmed = baseUrl.replace(/\/+$/, '');
  if (!trimmed.endsWith(API_BASE_SUFFIX)) return '';
  return trimmed.slice(0, trimmed.length - API_BASE_SUFFIX.length);
}

/**
 * 서버가 준 API 기준 경로 → 이 배포에서 실제로 요청할 주소.
 *
 * <p>이미 절대 URL 로 온 값은 그대로 둔다 — 접두를 두 번 붙이지 않는다.
 * 빈 값·비문자열은 빈 문자열로 정규화해, 호출부가 `src=""` 로 빈 요청을 내지 않게 한다.
 */
export function toDeployedApiUrl(apiPath: string | null | undefined): string {
  if (typeof apiPath !== 'string' || apiPath === '') return '';
  if (ABSOLUTE_URL.test(apiPath)) return apiPath;

  const prefix = deployBasePath();
  if (prefix === '') return apiPath;

  return apiPath.startsWith('/') ? `${prefix}${apiPath}` : `${prefix}/${apiPath}`;
}
