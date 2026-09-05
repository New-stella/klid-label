// 이벤트유형 관리 훅 — TanStack Query v5 (커스텀 훅으로 감싼다, state-management 규칙).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { EVENT_TYPE_KEYS } from '@/lib/queryKeys';

import {
  getEventTypeAdminList,
  updateEventTypeAdmin,
  type EventTypeAdminItem,
  type EventTypeAdminUpdate,
} from './adminApi';
import type { PresetLinkStatusFilter } from './presetLinkStatus';

/**
 * 관리 목록 캐시의 <b>뿌리</b>. 실제 캐시 키는 여기에 거르기 값이 한 칸 더 붙는다.
 *
 * 거르기가 다르면 <b>모집단이 다른 별개의 목록</b>이라 같은 키를 쓸 수 없다 — 같은 키를 쓰면
 * 조건을 켠 결과가 전체 목록 자리에 눌러앉아, 프리셋 편집 모달의 이벤트유형 옵션이 보류된
 * 유형만 남는다(그 모달은 <등록된 전체>가 옵션 원천이다).
 *
 * 프리셋을 등록·수정·삭제하면 연결 상태가 바뀌므로 프리셋 도메인도 이 뿌리를 무효화한다.
 */
export const EVENT_TYPE_ADMIN_KEY = [...EVENT_TYPE_KEYS.all, 'admin'] as const;

/**
 * 관리 화면 목록 — 필터 옵션과 달리 비수집·제외 대분류도 포함한다.
 *
 * @param presetLinkStatus 프리셋 연결 상태 거르기(선택). 미지정이면 전체.
 *   ★거르기는 <b>서버가 전체를 대상으로</b> 수행한다 — 받아 온 목록을 화면에서 다시 걸러내면
 *   아직 받지 않은 보류 유형이 빠진다.
 *
 * @design SCREEN-038, API-185
 */
export function useEventTypeAdminList(presetLinkStatus?: PresetLinkStatusFilter) {
  return useQuery<EventTypeAdminItem[]>({
    // 미지정은 'all' 이라는 <명시적 토큰>으로 굳힌다 — undefined 를 그대로 두면 키 배열 길이가
    // 갈려 접두 무효화의 대상 범위가 조건마다 달라진다.
    queryKey: [...EVENT_TYPE_ADMIN_KEY, presetLinkStatus ?? 'all'],
    queryFn: () => getEventTypeAdminList(presetLinkStatus),
    staleTime: 30 * 1000,
  });
}

/**
 * 이벤트유형 수정.
 *
 * <h3>즉시 갱신 + 재조회를 <b>함께</b> 한다</h3>
 * <ul>
 *   <li><b>즉시 갱신</b> — 성공 응답에 갱신된 표시명과 그 <b>출처</b>가 함께 실려 오므로 그 행을
 *       응답값으로 바로 바꾼다(깜빡임 없음).</li>
 *   <li><b>재조회</b> — 응답의 <b>프리셋 연결 상태는 null</b> 이다(서버가 의도적으로 비운다:
 *       표시명을 바꾸면 그룹이 쪼개져 대표코드가 바뀌는데 그룹 캐시 무효화가 커밋 이후라 그
 *       트랜잭션은 수정 전 그룹으로 판정한다). 게다가 표시명 변경은 그 행 하나가 아니라
 *       <b>같은 그룹의 다른 행들</b>의 연결 상태도 함께 바꾼다 — 그래서 목록을 다시 부른다.</li>
 * </ul>
 *
 * ⚠ 구 동작(2026-08 이전)은 <b>재조회하지 않는 것</b>이었고 그 근거는 "같은 사실을 두 번
 * 받아오는 왕복"이었다. 연결 상태가 응답에 실리지 않는 값이 되면서 그 전제가 깨졌다 —
 * 재조회를 다시 걷어내면 프리셋 칸이 저장 이후 영영 '-' 로 남는다.
 *
 * 필터 옵션·라벨맵도 무효화한다 — 표시명이 갈리면 옵션의 접기 결과가 달라지고 수집여부는
 * 드롭다운 노출을 가른다(BE 캐시는 커밋 후 evict 된다).
 *
 * @design SCREEN-038, API-186
 */
export function useUpdateEventTypeAdmin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ evntTypeCd, body }: { evntTypeCd: string; body: EventTypeAdminUpdate }) =>
      updateEventTypeAdmin(evntTypeCd, body),
    onSuccess: (updated) => {
      // 거르기별로 캐시가 갈려 있으므로 <b>접두 일치 전부</b>에 반영한다(setQueryData 단건이면
      // 지금 보고 있는 목록만 바뀌고 다른 조건의 캐시는 옛 이름으로 남는다).
      queryClient.setQueriesData<EventTypeAdminItem[]>(
        { queryKey: EVENT_TYPE_ADMIN_KEY },
        (prev) =>
          // 캐시가 비어 있으면(직접 진입 전) 만들어 두지 않는다 — 목록 전체를 알 수 없다.
          prev?.map((row) => (row.evntTypeCd === updated.evntTypeCd ? updated : row)),
      );
      void queryClient.invalidateQueries({ queryKey: EVENT_TYPE_ADMIN_KEY });
      void queryClient.invalidateQueries({ queryKey: EVENT_TYPE_KEYS.options() });
      void queryClient.invalidateQueries({ queryKey: EVENT_TYPE_KEYS.labels() });
    },
  });
}
