import { augTypeLabel } from './augTypeLabel';
import {
  AUGMENT_DECISION_LABEL,
  AugmentDecision,
  type AugmentResult,
  type AugmentResultState,
} from './types';

/**
 * 비교 이미지가 0장일 때의 폴백 문구.
 *
 * 구 문구 "프레임별 비교 결과는 **외부 연동 이후** 표시됩니다" 는 외부 연동이 끝난 지금 **사실이
 * 아니다**. 상태를 모를 때는 원인을 지어내지 않고 관측된 사실만 말한다.
 */
const EMPTY_PAIRS_FALLBACK = '표시할 비교 이미지가 없습니다.';

/**
 * 상태별 문구 — `READY` 는 정상 경로에서 그리드를 그리므로 항목이 없다(폴백을 쓴다).
 * `Partial` 이라 BE 가 값을 추가해도 컴파일·런타임 모두 폴백으로 흡수된다.
 */
const EMPTY_PAIRS_MESSAGE: Partial<Record<AugmentResultState, string>> = {
  GENERATING: '생성이 진행 중입니다. 완료되면 비교 이미지가 표시됩니다.',
  PREPARING_FRAMES: '생성이 완료되어 비교 이미지를 반입하고 있습니다.',
  WITHHELD: '비식별 누락 신고가 접수되어 비교 이미지를 표시하지 않습니다.',
  GENERATION_FAILED: '생성에 실패해 비교 이미지가 만들어지지 않았습니다.',
  CANCELED: '취소되어 비교 이미지가 만들어지지 않았습니다.',
  PURGED: '유예 기간이 지나 삭제되어 비교 이미지가 없습니다.',
  // 내부 식별자(NEW_RAW_SN 등)를 쓰지 않고 사용자가 이해할 수 있는 사실만 말한다.
  // "곧 표시됩니다" 류의 기대를 주지 않는 것이 이 문구의 핵심이다 — 영영 오지 않는다.
  DERIVATIVE_UNLINKED: '이전에 생성된 결과물이라 비교 이미지를 제공할 수 없습니다.',
};

/**
 * 비교 이미지가 0장인 **이유**를 문구로 옮긴다.
 *
 * ⚠ **exhaustive switch 를 쓰지 않는다** — BE 가 8번째 상태를 추가할 수 있고, 그때 화면이 런타임
 * 예외로 죽는 것보다 중립 폴백이 낫다. 구 응답(필드 없음)도 같은 폴백을 탄다.
 */
export function emptyPairsMessage(state: string | null | undefined): string {
  if (state == null) return EMPTY_PAIRS_FALLBACK;
  return EMPTY_PAIRS_MESSAGE[state as AugmentResultState] ?? EMPTY_PAIRS_FALLBACK;
}

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
