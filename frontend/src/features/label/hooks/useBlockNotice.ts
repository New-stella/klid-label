// Phase 3 §이월 P-1 — 차단 안내 dedupe **단일 정책**.
//
// 정책이 두 개로 갈려 있었다: OverlayLayer 는 **시각만** 보고 1,000ms 안의 안내를 문구와 무관하게
// 삼켰고(사유가 달라도 두 번째가 완전 무음), useBusyTask 는 message 키 + 300ms 였다.
// 여기 하나로 통일한다 — **문구 키 기반**: 같은 사유의 연타만 묶고, 서로 다른 사유는 항상 알린다.
// (사유가 다른 안내를 삼키면 사용자는 왜 막혔는지 알 방법이 없다.)
//
// DEV_FIX M1 — dedupe **상태**도 하나여야 한다. 훅 인스턴스별 ref 로 두면 같은 화면의 서로 다른
// 소비처(캔버스 / 속성 패널 / 배타 실행 래퍼)가 같은 사유로 연달아 거부될 때 문구가 중복된다.
// 상태는 화면 단위 저장소(useUiStore)가 소유하고, 이 훅은 발행기만 제공한다(모듈 싱글턴 아님 —
// 테스트는 `resetBlockNotice()` 로 격리하며 전역 setup 이 매 테스트마다 호출한다).

import { useCallback } from 'react';

import { BLOCK_NOTICE_BURST_MS, useUiStore } from '@/stores/useUiStore';

export { BLOCK_NOTICE_BURST_MS };

/**
 * 차단/거부 안내 토스트 발행기. 같은 문구가 버스트 창 안에서 반복되면 1회만 노출한다.
 * dedupe 판정과 상태는 {@link useUiStore.pushBlockNotice} 한 곳에 있다.
 */
export function useBlockNotice(): (message: string) => void {
  return useCallback((message: string) => {
    useUiStore.getState().pushBlockNotice(message);
  }, []);
}
