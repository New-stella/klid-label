import { augTypeLabel } from './augTypeLabel';
import {
  AUGMENT_DECISION_LABEL,
  AugmentDecision,
  type AugmentResult,
} from './types';

/** 페이징 전 전체 프레임 쌍 수 — 구 응답(totalFramePairs 없음)은 로드된 개수로 폴백. */
export function totalPairsOf(result: AugmentResult): number {
  return result.totalFramePairs ?? result.framePairs.length;
}

/**
 * 결정 상태 정규화 — BE 가 union 밖 코드를 보내도 화면이 깨지지 않게 한다.
 * (`CANCELED` 처럼 나중에 추가된 값이 실제로 흘러온 전례가 있다.)
 */
export function normalizeDecision(value: string | null | undefined): AugmentDecision {
  return value != null && value in AUGMENT_DECISION_LABEL
    ? (value as AugmentDecision)
    : AugmentDecision.PENDING;
}

/**
 * 항목 탭 라벨 — **같은 종류가 여러 건일 때 사용자가 구분할 수 있어야 한다**.
 *
 * 같은 (영상 × 종류) 재요청이 허용된 뒤로 한 종류에 여러 항목이 존재한다. 여기에 결정 상태를
 * 덧붙여 "어느 것이 지금 결정 대상인지"를 탭만 보고 알 수 있게 한다. 종류가 1건뿐이면 기존처럼
 * 종류명만 쓴다(불필요한 잡음 제거).
 *
 * <h3>`#n` 은 **잡 전체 항목 순번**이다 (Critical — 페이지마다 1로 되돌아가지 않는다)</h3>
 * 항목 축은 페이징되므로(`itemPage`/`itemSize`) 이 함수는 잡 전체가 아니라 **현재 페이지의 항목**만
 * 받는다. 구 구현은 그 안에서 종류별로 1부터 다시 셌기 때문에 2페이지의 첫 탭도 `#1` 이었고,
 * "왼쪽일수록 최근 요청" 안내와 함께 읽히면 **21번째로 최신인 항목이 잡 전체 최신으로 보였다**.
 *
 * 그래서 순번은 호출부가 넘겨준 `ordinals`(항목 id → 잡 전체 순번)로만 매긴다. 이 값의 출처는
 * **응답의 `itemPage`/`itemSize`** 이며 화면이 추정하지 않는다. `ordinals` 가 없으면(구 서버 응답:
 * 항목 축 페이징 자체가 없어 한 응답에 전 항목이 실린다) 페이지 안 순번으로 매기고, 화면 안내가
 * 그 사실을 그대로 말한다(`AugmentVideoSection`).
 *
 * ⚠ **종류별 n번째가 아니다.** 종류별 총 개수는 잡 전체 기준으로 알 수 없어(현재 페이지만 받는다)
 * 계산 자체가 불가능하다 — 없는 값을 지어내는 대신 계산 가능한 축(항목 순번)으로 번호를 매긴다.
 *
 * 한편 **정렬 순서는 신뢰할 수 있다** — BE(`AugmentResultViewService#externalAugs`)가 외부 위탁
 * 항목을 `regDt DESC, dataAugSn DESC` 로 정렬해 내려주므로 "왼쪽일수록 최근 요청" 은 페이지를
 * 넘어서도 참이다.
 *
 * @param results 현재 항목 페이지에 실린 항목(영상 단위로 걸러진 부분집합일 수 있다)
 * @param ordinals 항목 id → 잡 전체 순번(1-based). 없으면 페이지 안 순번으로 대체한다.
 */
export function buildItemTabLabels(
  results: AugmentResult[],
  ordinals?: ReadonlyMap<number, number> | null,
): string[] {
  const counts = new Map<string, number>();
  for (const r of results) {
    counts.set(r.type, (counts.get(r.type) ?? 0) + 1);
  }
  return results.map((r, index) => {
    const base = augTypeLabel(r.type);
    if ((counts.get(r.type) ?? 0) <= 1) return base;
    const ordinal = ordinals?.get(r.id) ?? index + 1;
    return `${base} #${ordinal} · ${AUGMENT_DECISION_LABEL[normalizeDecision(r.decision)]}`;
  });
}
