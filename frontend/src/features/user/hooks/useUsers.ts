import { useQuery } from '@tanstack/react-query';

import { USER_KEYS } from '@/lib/queryKeys';

import { listUsers } from '../api';
import type { UserListParams } from '../types';

export function useUsers(params: UserListParams) {
  return useQuery({
    queryKey: USER_KEYS.list(params as Record<string, unknown>),
    queryFn: () => listUsers(params),
  });
}
