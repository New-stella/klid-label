import { useQuery } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import { getVideo } from '../api';
import type { VideoDetail } from '../types';

// 배치가 진행 중일 때만 상세를 재조회(폴링)할 간격(ms).
// 완료/실패/미진행이면 폴링을 중지해 self-DoS(무한 요청)를 방지한다.
export const BATCH_POLL_INTERVAL_MS = 5000;

/**
 * 배치 진행 중이면 폴링 간격(ms), 아니면 false(중지)를 반환한다.
 *
 * <p>실시간 가시성 요구("마킹 진행중에 뭘 처리하는지 모른다")를 위해 배치가 도는 동안만
 * 자동 갱신하고, 종료되면 반드시 폴링을 멈춘다.
 * <ul>
 *   <li>stages 없음/빈배열(배치 로그 없는 기존 영상) → false (폴링 안 함)</li>
 *   <li>단계 중 FAIL 존재(실패 종료) → false (무한 폴링 방지)</li>
 *   <li>영상이 최종 완료 상태(COMPLETED/APPROVED) → false</li>
 *   <li>단계 중 PROGRESS/PENDING 존재(진행 중) → 폴링 간격 반환</li>
 *   <li>그 외(전부 DONE) → false</li>
 * </ul>
 */
export function batchPollInterval(data: VideoDetail | undefined): number | false {
  const stages = data?.stages;
  if (!stages || stages.length === 0) return false;
  // 실패 단계가 하나라도 있으면 배치 종료로 간주 → 폴링 중지(잔여 PENDING 로 인한 무한 폴링 차단).
  if (stages.some((s) => s.status === 'FAIL')) return false;
  // 영상이 최종 완료 상태면 폴링 불필요.
  if (data?.status === 'COMPLETED' || data?.status === 'APPROVED') return false;
  // 진행/대기 단계가 하나라도 있으면 아직 처리 중 → 폴링.
  if (stages.some((s) => s.status === 'PROGRESS' || s.status === 'PENDING')) {
    return BATCH_POLL_INTERVAL_MS;
  }
  // 전부 DONE → 폴링 중지.
  return false;
}

export function useVideoDetail(id: number | null) {
  return useQuery({
    queryKey: VIDEO_KEYS.detail(id ?? -1),
    queryFn: () => getVideo(id as number),
    enabled: id !== null && id > 0,
    // 배치 진행 중에만 최신 단계를 폴링. 종료 시 false 반환으로 자동 중지.
    refetchInterval: (query) => batchPollInterval(query.state.data),
  });
}
