/**
 * 포털 업로드 영상 마킹 창구 — 통신만 담당한다.
 * [@design API-239] [@design API-240] [@design API-241]
 *
 * 계산·판정은 `markingPlan` 이 갖는다. 한 파일에 섞으면 화면 시험이 이 모듈을 통째로 모의할 때
 * 판정 함수까지 `undefined` 가 되어, 화면이 조용히 「모르는 값」 분기로 떨어진다.
 *
 * 보안: `apiClient`(baseURL `/api/v1`)가 인증 헤더를 붙이고 표준 응답 감싸개를 벗긴다. 경로는
 * axios 가 인코딩하며 문자열로 조립하지 않는다. 소유자 판정(CWE-639)은 서버가 한다.
 *
 * ⚠ 재생 창구(API-238)를 부르는 함수는 여기 없다 — 그 주소는 재생 요소가 직접 받아 간다.
 *   `getUploadStreamUrl` 이 돌려준 주소를 `<video src>` 에 물리는 것이 그 창구의 호출이다.
 */
import { apiClient } from '@/lib/api/client';

import type {
  PortalMarkingList,
  PortalMarkingSaveRequest,
  PortalMarkingSaveResult,
  PortalStreamUrl,
} from './markingTypes';

/**
 * 재생용 단기 서명 주소 발급(API-239).
 *
 * 서명은 짧게 살고 재발급이 정상 동선이다. 발급 시점에 소유자와 자산 종류를 서버가 판정해
 * 서명에 묶으므로, 이 주소로 하는 재생과 토큰을 실은 직접 재생의 접근 가능 주체가 같다.
 */
export function getUploadStreamUrl(uldSn: number): Promise<PortalStreamUrl> {
  return apiClient
    .get<PortalStreamUrl>(`/portal/uploads/${uldSn}/stream-url`)
    .then((r) => r.data);
}

/**
 * 저장된 마킹 조회(API-241).
 *
 * 저장이 자산 상태를 조건으로 거는 것과 달리 <b>조회는 걸지 않는다</b> — 다시 저장할 수 없는
 * 자산일수록 무엇이 저장돼 있는지 확인할 필요가 커진다. 저장된 것이 없으면 빈 목록이다.
 */
export function listUploadMarkings(uldSn: number): Promise<PortalMarkingList> {
  return apiClient
    .get<PortalMarkingList>(`/portal/uploads/${uldSn}/markings`)
    .then((r) => r.data);
}

/**
 * 마킹 저장(API-240) — <b>저장이 곧 프레임 추출의 시작</b>이다.
 *
 * ★ 되돌릴 수 없다. 화면은 이 함수를 부르기 전에 반드시 확인 단계를 거친다.
 * ★ 본문은 `buildMarkingSaveRequest` 가 조립한 것을 그대로 보낸다(여기서 다시 손대지 않는다).
 */
export function saveUploadMarking(
  uldSn: number,
  body: PortalMarkingSaveRequest,
): Promise<PortalMarkingSaveResult> {
  return apiClient
    .post<PortalMarkingSaveResult>(`/portal/uploads/${uldSn}/markings`, body)
    .then((r) => r.data);
}
