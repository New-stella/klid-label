import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { AI_SERVER_KEYS } from '@/lib/queryKeys';

import {
  changeAiServerStatus,
  createAiServer,
  deleteAiServer,
  listAiServers,
  updateAiServer,
} from '../api';
import type { AiSrvrCreateRequest, AiSrvrStatus, AiSrvrType, AiSrvrUpdateRequest } from '../types';

/**
 * 유형별 장비 목록. [@design API-226]
 *
 * 컴포넌트가 `useQuery` 를 직접 부르지 않도록 감싼다(이 저장소의 관례).
 */
export function useAiServers(srvrTypeCd: AiSrvrType) {
  return useQuery({
    queryKey: AI_SERVER_KEYS.list(srvrTypeCd),
    queryFn: () => listAiServers(srvrTypeCd),
  });
}

/**
 * 쓰기 넷 — 모두 성공 시 원장 목록 전체를 무효화한다.
 *
 * ★ **자기 유형만 무효화하지 않는다.** 마지막 가용 장비 보호는 «그 유형에 가용이 몇 대 남았는가»로
 *   판정되므로, 한 유형의 변화가 다른 탭의 안내 문구를 바꾼다. 반쪽만 갱신하면 이미 사라진
 *   장비를 근거로 「내릴 수 있다」고 안내하게 된다.
 *
 * ⚠ `invalidateQueries` 로 충분하다 — 이 변화는 서버가 이후 요청을 거부하게 만드는(게이트가 닫히는)
 *   변화가 아니라 «최신값을 다시 받아야 하는» 변화다. 캐시에 남는 값도 장비 이름·주소·상태이지
 *   개인정보가 아니다.
 */
function useInvalidateAiServers() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: AI_SERVER_KEYS.all });
}

export function useCreateAiServer() {
  const invalidate = useInvalidateAiServers();
  return useMutation({
    mutationFn: (vars: { body: AiSrvrCreateRequest; adminSessionToken?: string }) =>
      createAiServer(vars.body, vars.adminSessionToken),
    onSuccess: invalidate,
  });
}

export function useUpdateAiServer() {
  const invalidate = useInvalidateAiServers();
  return useMutation({
    mutationFn: (vars: {
      srvrId: string;
      body: AiSrvrUpdateRequest;
      adminSessionToken?: string;
    }) => updateAiServer(vars.srvrId, vars.body, vars.adminSessionToken),
    onSuccess: invalidate,
  });
}

export function useChangeAiServerStatus() {
  const invalidate = useInvalidateAiServers();
  return useMutation({
    mutationFn: (vars: {
      srvrId: string;
      srvrSttsCd: AiSrvrStatus;
      adminSessionToken?: string;
    }) => changeAiServerStatus(vars.srvrId, vars.srvrSttsCd, vars.adminSessionToken),
    onSuccess: invalidate,
  });
}

export function useDeleteAiServer() {
  const invalidate = useInvalidateAiServers();
  return useMutation({
    mutationFn: (vars: { srvrId: string; adminSessionToken?: string }) =>
      deleteAiServer(vars.srvrId, vars.adminSessionToken),
    onSuccess: invalidate,
  });
}
