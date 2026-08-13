import { useQuery } from '@tanstack/react-query';

import { USER_KEYS } from '@/lib/queryKeys';

import { getUser } from '../api';

/**
 * 사용자 프로필 단건 조회 (REVIEWER 전용) — `GET /v1/users/{userNo}`.
 *
 * ⚠ 반환 데이터는 목록의 `User` 가 아니라 `UserProfile` 이다(필드명이 `userNo`/`userNm`/
 * `userEmail`). 목록에서 이미 받은 행을 다시 조회하려고 이 훅을 쓰지 말 것 — 필드가 달라
 * 화면 코드가 갈린다. 목록에 없는 정보가 필요할 때만 쓴다.
 */
export function useUser(userNo: number | null | undefined) {
  return useQuery({
    queryKey: USER_KEYS.detail(userNo ?? 0),
    queryFn: () => getUser(userNo as number),
    enabled: typeof userNo === 'number' && userNo > 0,
  });
}
