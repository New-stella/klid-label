import { useQuery } from '@tanstack/react-query';

import { ASSIGNMENT_KEYS } from '@/lib/queryKeys';

import { listTasks } from '../api';
import type { TaskListParams } from '../types';

export function useTasks(params: TaskListParams) {
  return useQuery({
    queryKey: ASSIGNMENT_KEYS.list(params as Record<string, unknown>),
    queryFn: () => listTasks(params),
  });
}
