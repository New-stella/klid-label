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
 * 일치도(외부 위탁 결과가 원본 서술과 얼마나 일치하는지) 키 — 읽기 전용.
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

/**
 * 읽기 전용 메타의 화면 라벨. 미지의 키가 늘어도 깨지지 않게 폴백을 둔다
 * (BE 가 fail-closed 로 {@code readOnlyMeta} 를 늘릴 수 있다).
 *
 * 폴백은 내부 네임스페이스 접두({@code vlm.})만 떼고 남은 이름을 그대로 보여준다 — 화면 문구에
 * 모델명을 노출하지 않기 위함이며, 값·의미를 재해석하지는 않는다.
 */
export function readOnlyMetaLabel(metaKey: string): string {
  if (metaKey === ACCURACY_META_KEY) {
    return '일치도';
  }
  const stripped = metaKey.startsWith('vlm.') ? metaKey.slice('vlm.'.length) : metaKey;
  return stripped.trim() === '' ? metaKey : stripped;
}

/**
 * 읽기 전용 메타 값의 표시 문자열.
 *
 * 일치도는 0~1 문자열이라 그대로 보여주면 사람이 신뢰도로 읽기 어렵다 → 백분율로 환산한다
 * (소수 첫째 자리까지, {@code 0.923} → {@code 92.3%}). 숫자가 아니거나 0~1 범위를 벗어나면
 * <b>지어내지 않고</b> 원문을 그대로 보여준다.
 */
export function formatReadOnlyMetaValue(metaKey: string, metaVal: string): string {
  if (metaKey !== ACCURACY_META_KEY) {
    return metaVal;
  }
  const raw = (metaVal ?? '').trim();
  const n = Number(raw);
  if (raw === '' || !Number.isFinite(n) || n < 0 || n > 1) {
    return metaVal;
  }
  return `${Math.round(n * 1000) / 10}%`;
}
