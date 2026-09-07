import { useQuery } from '@tanstack/react-query';

import { AUTH_KEYS } from '@/lib/queryKeys';

import { getRoleClaimAvailability, type RoleClaimAvailability } from '../api';

/**
 * 관리자 부트스트랩 창구 개폐 조회. [@design API-245] [@design SCREEN-002] [@design AC-1098]
 *
 * 화면에 들어서는 시점에 미리 물어, 열림·닫힘 두 모습 중 하나를 <b>처음부터</b> 보이게 한다.
 *
 * <h3>왜 `staleTime` 을 0 으로 두는가</h3>
 * 개폐는 <b>다른 사람의 행동으로</b> 바뀐다(누군가 최초 관리자가 되면 닫힌다). 캐시를 오래
 * 붙들면 화면이 남의 변화를 못 보고 「열림」을 계속 그린다 — 이 화면이 고치려는 결함이 바로
 * 「화면이 사실과 다른 말을 한다」이므로 그 방향으로 되돌아갈 여지를 두지 않는다.
 *
 * <h3>왜 재시도하지 않는가</h3>
 * 실패를 갈라서 다르게 처리해야 하는데(401=상위 로그인으로 / 그 밖=오류 표시) 자동 재시도는
 * 그 갈림을 늦추기만 한다. 401 은 재시도해도 401 이다.
 *
 * ⚠ 이 조회는 <b>순간의 상태</b>만 알려 준다. 조회가 「열림」이라 답한 뒤 제출 전에 창이 닫힐 수
 * 있으므로, 호출하는 화면은 제출이 409 로 거절되는 갈래를 그대로 유지해야 한다.
 */
export function useRoleClaimAvailability() {
  return useQuery<RoleClaimAvailability>({
    queryKey: AUTH_KEYS.roleClaimAvailability(),
    queryFn: getRoleClaimAvailability,
    staleTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  });
}
