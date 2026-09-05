// AI 장비 노드 원장 API — BE: /api/v1/manage/ai-servers
//
// BE 시그니처 (kr.co.cudo.authoring.aiserver.controller.AiSrvrController):
//   GET    /v1/manage/ai-servers?srvrTypeCd=       → List<AiSrvrResponse>   (검수자 이상)
//   POST   /v1/manage/ai-servers                   → AiSrvrResponse   201   (관리자 + 유효창)
//   PATCH  /v1/manage/ai-servers/{srvrId}          → AiSrvrResponse   200   (관리자 + 유효창)
//   PATCH  /v1/manage/ai-servers/{srvrId}/status   → AiSrvrResponse   200   (관리자 + 유효창)
//   DELETE /v1/manage/ai-servers/{srvrId}          → 본문 없음        204   (관리자 + 유효창)
//
// [@design API-226] [@design API-227] [@design API-228] [@design API-229] [@design API-230]

import { adminSessionHeaders } from '@/features/adminSession/api';
import { apiClient } from '@/lib/api/client';

import type { AiSrvr, AiSrvrCreateRequest, AiSrvrStatus, AiSrvrType, AiSrvrUpdateRequest } from './types';

/**
 * 목록 조회 — 유형으로 거른다.
 *
 * ⚠ **조회에는 관리자 유효창 헤더를 붙이지 않는다.** 조회는 검수자 권한만으로 되는 창구이고,
 *   「일관성」을 이유로 조회에까지 요건을 얹으면 유효창이 닫힌 순간 목록이 통째로 사라진다
 *   (`adminSession/api.ts` 의 같은 경고와 동일 축).
 */
export function listAiServers(srvrTypeCd?: AiSrvrType) {
  return apiClient
    .get<AiSrvr[]>('/manage/ai-servers', {
      params: srvrTypeCd ? { srvrTypeCd } : undefined,
    })
    .then((r) => r.data);
}

/** 등록 — 상태는 서버가 가용으로 시작시킨다(요청으로 받지 않는다). */
export function createAiServer(body: AiSrvrCreateRequest, adminSessionToken?: string) {
  return apiClient
    .post<AiSrvr>('/manage/ai-servers', body, { headers: adminSessionHeaders(adminSessionToken) })
    .then((r) => r.data);
}

/** 이름·주소 수정 — 보내지 않은 항목은 서버가 그대로 둔다. */
export function updateAiServer(
  srvrId: string,
  body: AiSrvrUpdateRequest,
  adminSessionToken?: string,
) {
  return apiClient
    .patch<AiSrvr>(`/manage/ai-servers/${encodeURIComponent(srvrId)}`, body, {
      headers: adminSessionHeaders(adminSessionToken),
    })
    .then((r) => r.data);
}

/** 상태 전이 — 거부 셋(불가 전이·같은 상태·마지막 가용 장비)이 모두 409 이고 사유는 문구로 갈린다. */
export function changeAiServerStatus(
  srvrId: string,
  srvrSttsCd: AiSrvrStatus,
  adminSessionToken?: string,
) {
  return apiClient
    .patch<AiSrvr>(
      `/manage/ai-servers/${encodeURIComponent(srvrId)}/status`,
      { srvrSttsCd },
      { headers: adminSessionHeaders(adminSessionToken) },
    )
    .then((r) => r.data);
}

/** 삭제 — 204 라 본문이 없다. */
export function deleteAiServer(srvrId: string, adminSessionToken?: string) {
  return apiClient
    .delete<null>(`/manage/ai-servers/${encodeURIComponent(srvrId)}`, {
      headers: adminSessionHeaders(adminSessionToken),
    })
    .then(() => undefined);
}
