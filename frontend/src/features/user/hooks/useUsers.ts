import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { USER_KEYS } from '@/lib/queryKeys';

import { listUsers } from '../api';
import type { UserListParams } from '../types';

interface UseUsersOptions {
  /**
   * react-query 의 enabled 와 동일. 기본값 true.
   * REVIEWER 전용 API 라 WORKER 화면에서는 false 로 호출 자체를 막아 403 스팸을 방지한다.
   */
  enabled?: boolean;
}

export function useUsers(params: UserListParams, options: UseUsersOptions = {}) {
  const { enabled = true } = options;
  return useQuery({
    queryKey: USER_KEYS.list(params as Record<string, unknown>),
    queryFn: () => listUsers(params),
    enabled,
    // 페이지 전환 시 이전 목록 유지(빈 상태 깜빡임 방지).
    placeholderData: keepPreviousData,
  });
}
