// 시계열 메타 키(metaKey) 판정·표시 규칙 단일 원천.
//
// 어댑터(api.ts)·라벨링 편집 패널(TimeseriesSidePanel)·검수 읽기 패널(ReviewMetaPanel) 이 모두
// 이 모듈만 참조한다. 판정을 각 화면이 복제하면 한쪽만 갱신돼 조용히 어긋난다(이 저장소의 실사고 패턴).

/**
 * 외부 위탁(verify) 결과 서술 전문 키 — BE {@code VlmResultService.META_KEY_DESCRIPTION} 미러.
 * 화면에서 <b>편집 가능한 유일한 자동 생성 키</b>다.
 */
export const DESCRIPTION_META_KEY = 'vlm.description';

/**
 * 수동 시계열 메타의 표준 metaKey (BE↔FE 공유 상수).
 *
 * 편집 가능한 항목이 하나도 없는 영상(메타 0건 / 레거시 구간만 존재)에서 신규 등록 시 쓰는 결정적 키.
 * 레거시 구간 키({@code start_sec-end_sec})와 충돌하지 않는다.
 */
export const MANUAL_TIMESERIES_META_KEY = 'manual-timeseries';

/**
 * 일치도 키 — <b>과거 적재분 전용이며 화면에 표시하지 않는다.</b>
 *
 * 판정 창구를 연동하지 않게 되면서 이 값은 새로 생기지 않고, 남은 것은 과거 위탁분뿐이다.
 * 그래서 표시 로직을 걷어냈다. 상수를 남기는 이유는 <b>편집 대상이 아님을 고정하는 대조군</b>
 * 이기 때문이다 — BE 가 이 키의 수정 요청을 400 으로 거부하며, 그 계약은 표시 여부와 무관하게
 * 유효하다. 되살리려면 화면 사양(SCREEN-005·SCREEN-019)부터 고쳐야 한다.
 *
 * BE {@code VlmResultService.META_KEY_ACCURACY} 미러. 값은 0~1 문자열.
 */
export const ACCURACY_META_KEY = 'vlm.accuracy';

/**
 * <b>편집 허용 키 화이트리스트</b>. 여집합은 전부 읽기 전용으로 취급한다(fail-closed).
 *
 * BE {@code MetaService.EDITABLE_VLM_KEYS} 와 같은 방향의 판정이다 — "편집 허용한 것만 통과"라
 * 새 자동 생성 키가 늘어도 편집 슬롯·저장 payload 로 새지 않는다. 접두 문자열 파싱으로 판정하지 않는다.
 * [req: R8]
 */
export const EDITABLE_META_KEYS = [DESCRIPTION_META_KEY, MANUAL_TIMESERIES_META_KEY] as const;

export type EditableMetaKey = (typeof EDITABLE_META_KEYS)[number];

/** 편집 슬롯 대상 키인지. 여집합(레거시 구간 키 등)은 읽기 전용으로 병기한다. [req: R8][req: R9] */
export function isEditableMetaKey(metaKey: string | null | undefined): boolean {
  return (
    typeof metaKey === 'string' &&
    (EDITABLE_META_KEYS as readonly string[]).includes(metaKey)
  );
}

/**
 * 레거시 구간 키({@code "{start_sec}-{end_sec}"})의 {@code start_sec}. 구간형이 아니면 null.
 *
 * 구 describe 산출물의 키이며 {@code 0001} 같은 순번 키도 숫자로 읽는다.
 */
export function startSecOf(metaKey: string | null | undefined): number | null {
  if (typeof metaKey !== 'string') {
    return null;
  }
  const m = /^(\d+)(?:-\d+)?$/.exec(metaKey.trim());
  if (m === null) {
    return null;
  }
  const n = Number(m[1]);
  return Number.isFinite(n) ? n : null;
}

/**
 * 레거시 구간 정렬 비교자 — {@code start_sec} <b>숫자</b> 오름차순. [req: R9]
 *
 * 문자열 정렬({@code localeCompare})이면 구간이 10개를 넘는 순간 {@code "10-18"} 이 {@code "8-16"}
 * 앞으로 와 시간순이 깨진다. 구간형이 아닌 키({@code manual-timeseries} 등)가 섞여도 깨지지 않도록
 * 숫자 키를 앞에, 나머지는 뒤에 사전순으로 둔다(전순서 — 정렬 결과가 실행마다 흔들리지 않는다).
 */
export function compareByStartSec(a: string, b: string): number {
  const sa = startSecOf(a);
  const sb = startSecOf(b);
  if (sa !== null && sb !== null) {
    return sa !== sb ? sa - sb : a.localeCompare(b);
  }
  if (sa !== null) {
    return -1;
  }
  if (sb !== null) {
    return 1;
  }
  return a.localeCompare(b);
}

/**
 * 편집 슬롯의 화면 라벨. 기술 용어·모델명을 노출하지 않는다(FE 문구 규칙).
 */
export function editableMetaLabel(metaKey: string): string {
  return metaKey === DESCRIPTION_META_KEY ? '시계열 서술' : '시계열 메타';
}

/*
 * ★ 읽기 전용 메타의 화면 라벨·값 포맷터(구 readOnlyMetaLabel · formatReadOnlyMetaValue)는 폐기됐다.
 *   유일한 대상이던 일치도를 화면에서 빼기로 확정했기 때문이다(2026-08-24 사용자 확정).
 *   BE 응답의 readOnlyMeta 목록은 계약대로 남아 있고 어댑터도 계속 실어 나르지만, 화면은
 *   그것을 그리지 않는다 — 되살리려면 화면 사양(SCREEN-005·SCREEN-019)부터 고쳐야 한다.
 */
