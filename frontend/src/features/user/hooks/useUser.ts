import { useQuery } from '@tanstack/react-query';

import { USER_KEYS } from '@/lib/queryKeys';

import { getUser } from '../api';

export function useUser(id: number | null | undefined) {
  return useQuery({
    queryKey: USER_KEYS.detail(id ?? 0),
    queryFn: () => getUser(id as number),
    enabled: typeof id === 'number' && id > 0,
  });
}
