// 마킹 산출물 검사·적재·진행 조회 훅 — 컴포넌트가 useQuery/useMutation 을 직접 부르지 않도록 감싼다.
//
// @design SCREEN-039
// @design API-216 API-217 API-218

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { IMPORT_KEYS } from '@/lib/queryKeys';

import { createMarkingImport, getMarkingProgress, scanMarkingFolder } from '../markingApi';
import {
  MarkingJobStatus,
  type MarkingCreateRequest,
  type MarkingCreateResult,
  type MarkingItemStatus,
  type MarkingProgress,
  type MarkingScanRequest,
  type MarkingScanResult,
} from '../markingTypes';

/** 진행을 다시 묻는 간격(ms). 작업이 종결되면 되풀이를 멈춘다. */
export const MARKING_PROGRESS_POLL_MS = 3000;

/**
 * 마킹 산출물 폴더 검사.
 *
 * 아무것도 저장하지 않으므로 성공해도 캐시를 무효화하지 않는다 — 서버 상태가 달라지지 않는다.
 */
export function useMarkingScan(options?: {
  onSuccess?: (result: MarkingScanResult) => void;
  onError?: (e: unknown) => void;
}) {
  return useMutation({
    mutationFn: (body: MarkingScanRequest) => scanMarkingFolder(body),
    onSuccess: (result) => options?.onSuccess?.(result),
    onError: (e) => options?.onError?.(e),
  });
}

/**
 * 마킹 산출물 일괄 적재 등록.
 *
 * 가져온 내역은 두 갈래 결과가 함께 남는 자리이므로(SCREEN-039) 작업을 등록하면 그 목록을 다시
 * 받는다. 게이트가 닫히는 변화가 아니라 최신값을 다시 받으면 되는 변화이므로
 * `invalidateQueries` 다.
 */
export function useCreateMarkingImport(options?: {
  onSuccess?: (result: MarkingCreateResult) => void;
  onError?: (e: unknown) => void;
}) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: MarkingCreateRequest) => createMarkingImport(body),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: IMPORT_KEYS.historyLists() });
      options?.onSuccess?.(result);
    },
    onError: (e) => options?.onError?.(e),
  });
}

/** 작업이 종결됐는지 — 종결이면 되풀이를 멈춘다. */
export function isMarkingJobFinished(progress: MarkingProgress | undefined): boolean {
  return progress !== undefined && progress.status !== MarkingJobStatus.RUNNING;
}

/**
 * 일괄 적재 진행 조회.
 *
 * 적재 요청이 곧바로 반환하므로 끝나는 시점을 응답으로 알 수 없고, 이 조회가 그 자리를 대신한다.
 * 작업 식별번호가 없으면(아직 등록하지 않았으면) 조회 자체를 하지 않는다.
 *
 * ★상태 거르기는 쿼리 키에 들어간다 — 조건이 다르면 담기는 목록이 달라 캐시를 갈라야 한다.
 */
export function useMarkingProgress(jobSn: number | null, status: MarkingItemStatus | null) {
  return useQuery({
    queryKey: IMPORT_KEYS.markingProgress(jobSn ?? 0, status),
    queryFn: () => getMarkingProgress(jobSn as number, status),
    enabled: jobSn !== null,
    // 종결된 작업은 더 물어도 값이 달라지지 않는다. 되풀이를 멈추지 않으면 화면을 열어 둔
    // 동안 끝난 작업에 계속 요청이 나간다.
    refetchInterval: (query) =>
      isMarkingJobFinished(query.state.data) ? false : MARKING_PROGRESS_POLL_MS,
  });
}
