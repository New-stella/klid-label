/**
 * 포털 업로드 영상 마킹 훅 — 서명 주소 발급 · 저장된 마킹 조회 · 마킹 저장.
 * [@design SCREEN-045] [@design API-239] [@design API-240] [@design API-241]
 *
 * 화면이 `useQuery` 를 직접 부르지 않고 이 훅들을 거친다(도메인별 훅 관례).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { getUploadStreamUrl, listUploadMarkings, saveUploadMarking } from '../markingApi';
import type { PortalMarkingSaveRequest, PortalMarkingSaveResult } from '../markingTypes';

/**
 * 재생용 단기 서명 주소.
 *
 * ★ <b>스스로 다시 받지 않는다</b>(`staleTime: Infinity` · 창 복귀 시 재조회 안 함). 주소가 바뀌면
 *   재생 요소의 `src` 가 갈려 <b>재생이 처음으로 되돌아간다</b> — 몇 분짜리 영상을 훑으며 지점을
 *   찍는 화면에서 그것은 작업을 통째로 날린다. 그래서 재발급은 <b>재생이 실제로 끊겼을 때</b>
 *   화면이 `refetch` 로 부를 때만 일어난다.
 * ★ `gcTime: 0` 이라 화면을 떠나면 캐시가 남지 않는다 — 다음에 들어오면 만료된 주소를 물지 않고
 *   새로 받는다.
 */
export function useUploadStreamUrl(uldSn: number | undefined) {
  return useQuery({
    queryKey: PORTAL_KEYS.uploadStreamUrl(uldSn ?? -1),
    queryFn: () => getUploadStreamUrl(uldSn as number),
    enabled: uldSn !== undefined && uldSn > 0,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    retry: false,
  });
}

/**
 * 저장된 마킹 — 화면에 다시 들어왔을 때 저장한 지점을 그대로 보여 주기 위한 조회.
 *
 * 저장이 자산 상태를 조건으로 거는 것과 달리 조회는 걸지 않으므로, 다시 저장할 수 없는 자산에서도
 * 무엇이 저장돼 있는지 확인할 수 있다.
 */
export function useUploadMarkings(uldSn: number | undefined) {
  return useQuery({
    queryKey: PORTAL_KEYS.uploadMarkings(uldSn ?? -1),
    queryFn: () => listUploadMarkings(uldSn as number),
    enabled: uldSn !== undefined && uldSn > 0,
  });
}

interface SaveOptions {
  onSuccess?: (result: PortalMarkingSaveResult) => void;
  onError?: (error: unknown) => void;
}

/**
 * 마킹 저장 — <b>되돌릴 수 없는 조작</b>이다. 화면은 확인 단계를 거친 뒤에만 이 훅을 부른다.
 *
 * <h3>성공 뒤에 캐시를 왜 무효화하는가</h3>
 * 저장은 자산을 추출 중 상태로 넘긴다 — 목록의 상태 배지, 자산 상세, 저장된 마킹이 모두 옛 값이
 * 된다. 그대로 두면 화면이 「아직 마킹 대기」로 보여 사용자가 한 번 더 저장하려 든다.
 *
 * ⚠ 여기서는 <b>버리지 않고 다시 받는다</b>(`invalidate`). 이 변화로 가려야 할 내용이 생기는 것이
 *   아니라 <b>같은 자리의 값이 새로워질 뿐</b>이기 때문이다. 캐시를 통째로 버려야 하는 것은 그 변화로
 *   서버가 이후 조회를 거부하게 되는(=게이트가 닫히는) 경우이며, 여기 저장된 마킹과 자산 상세는
 *   저장 뒤에도 그대로 조회된다.
 */
export function useSaveUploadMarking(uldSn: number | undefined, options: SaveOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: PortalMarkingSaveRequest) => saveUploadMarking(uldSn as number, body),
    onSuccess: (result) => {
      void qc.invalidateQueries({ queryKey: PORTAL_KEYS.all });
      options.onSuccess?.(result);
    },
    onError: (error) => options.onError?.(error),
  });
}
