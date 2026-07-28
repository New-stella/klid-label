// BE 가 응답 본문에 담아 내려주는 이미지 API 경로(예: `/v1/frames/9002/deid-image`)를
// apiClient(baseURL `/api/v1`) 기준 상대 경로(`/frames/9002/deid-image`)로 변환한다.
//
// 보안(CWE-918 SSRF / CWE-22 Path Traversal):
// - 응답값이라도 그대로 요청 경로에 붙이지 않고 **화이트리스트 정규식**으로만 통과시킨다.
//   외부 호스트(`https://...`), 프로토콜 상대(`//host`), 상위 경로 순회(`..`), 쿼리스트링은 모두 거부.
// - 허용 대상은 프레임 이미지 서빙 2종뿐이다.
//   · `/v1/frames/{srcSn}/image`      (원본/비식별 — 역할별 서빙 판정은 BE)
//   · `/v1/frames/{srcSn}/deid-image` (비식별 전용)

const ALLOWED_IMAGE_PATH = /^\/v1\/frames\/[0-9]{1,19}\/(?:deid-)?image$/;

/**
 * 허용된 이미지 API 경로면 apiClient 상대 경로를 돌려주고, 아니면 null.
 *
 * @param url BE 응답이 내려준 경로 문자열
 */
export function toApiImagePath(url: string | undefined | null): string | null {
  if (typeof url !== 'string' || !ALLOWED_IMAGE_PATH.test(url)) return null;
  return url.slice('/v1'.length);
}
