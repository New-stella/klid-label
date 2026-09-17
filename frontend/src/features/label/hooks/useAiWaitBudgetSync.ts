// 서버가 준 AI 대기 예산을 화면의 판정기(`aiBudget`)로 흘려보내는 배선.
//
// ★ 왜 별도 훅인가 — 실제로 요청을 보내는 곳(`api.ts` 의 평범한 함수들)은 React 컨텍스트 밖이라
//   훅으로 값을 읽을 수 없다. 그렇다고 함수 시그니처마다 예산을 인자로 흘려보내면 호출부가 한 곳만
//   빠뜨려도 그 경로가 조용히 폴백으로 돌아간다(이 저장소가 반복해 겪은 «판정이 여러 곳에 흩어져
//   한쪽만 갱신되는» 결함 계열). 그래서 발행 지점을 이 훅 하나로 두고, 판정기는 그것을 읽는다.
//
// ★ 왜 `useAiDefaults`(sysconfig) 안에서 발행하지 않는가 — 그 훅은 시스템 설정 도메인이고 예산
//   판정기는 라벨링 도메인이다. 조회 훅에 다른 도메인의 부작용을 숨기면, 그 훅을 쓰는 다른 화면이
//   의도치 않게 라벨링 판정기를 건드리게 된다. 대신 라벨링 화면이 이 훅을 명시적으로 마운트한다.

import { useEffect } from 'react';

import { useAiDefaults } from '@/features/sysconfig/hooks/useAiDefaults';

import { publishAiWaitBudgets } from '../aiBudget';

/**
 * AI 정밀도 기본값을 조회하고, 그중 **대기 예산**을 판정기에 발행한다.
 *
 * 반환값은 `useAiDefaults` 그대로다 — 같은 응답의 다른 값(민감도·세밀함 초기값)을 쓰는 화면이
 * 조회를 두 번 하지 않게 한다.
 *
 * @param enabled 조회할지. 끄면 발행도 하지 않아 <b>판정기가 폴백 예산을 그대로 쓴다</b> —
 *   AI 도구가 없는 화면에서는 그것이 옳다(쓰지 않는 값을 받으려고 403 을 쌓지 않는다).
 * @param portal  포털 채널 창구로 조회할지. 포털 채널도 AI 보조를 쓰므로(2026-09-15) 끄는 것이 아니라
 *   창구를 갈라 부른다 — 내부 창구는 포털 토큰으로 403 이다.
 *
 * ⚠ 조회 실패·응답에 예산 없음이면 **아무 것도 발행하지 않는다**(폴백 유지). 여기서 빈 값을
 *   발행하면 «서버가 0 을 줬다» 와 «못 받았다» 가 구분되지 않아 제한시간이 0(=즉시 끊김)이 될 수
 *   있다 — 판정기가 필드 단위로 막고 있지만, 발행 자체를 하지 않는 편이 의도가 분명하다.
 */
export function useAiWaitBudgetSync(enabled = true, portal = false) {
  const query = useAiDefaults(enabled, portal);
  const waitBudgets = query.data?.waitBudgets;

  useEffect(() => {
    if (waitBudgets === undefined) return;
    publishAiWaitBudgets(waitBudgets);
  }, [waitBudgets]);

  return query;
}
