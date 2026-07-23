// AI 탐지 후보(활성 라벨 마스터 + COCO 매핑 여부) 조회 훅 (TanStack Query).
//
// 라벨링 화면 'AI 탐지' 팝업(AiToolModal)의 후보 소스. 매핑된 라벨만 선택 가능하며, 실제 검출
// 대상 재구성·재검증은 BE(신뢰 경계)가 수행한다. staleTime 1분 — 라벨 매핑은 변경 빈도 낮음.

import { useQuery } from '@tanstack/react-query';

import { fetchDetectCandidates, type DetectCandidate } from '../api/labelMaster';

export const DETECT_CANDIDATE_KEYS = {
  all: ['detect-candidates'] as const,
  list: () => [...DETECT_CANDIDATE_KEYS.all, 'list'] as const,
};

export function useDetectCandidates(enabled = true) {
  return useQuery<DetectCandidate[]>({
    queryKey: DETECT_CANDIDATE_KEYS.list(),
    queryFn: fetchDetectCandidates,
    enabled,
    staleTime: 60 * 1000,
  });
}
