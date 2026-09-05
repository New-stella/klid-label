// 촬영환경(날씨·시간대·계절) <b>선택 어휘</b>의 단일 지점.
//
// 값 자체의 소유자는 서버다(BE `ShootingEnvironmentVocabulary` — 허용값 화이트리스트이며 자유
// 입력은 400 이다). 이 표는 그 어휘의 <b>화면 사본</b>이고, 코드↔한글 표시 대응을 함께 갖는다.
//
// <h3>왜 모았나</h3>
// 내부 라벨링 화면의 촬영환경 패널이 이 표를 가지고 있었는데 포털 채널이 같은 어휘를 필요로
// 하면서 사본이 둘이 될 참이었다. 어휘가 갈리면 한 화면에서는 고를 수 있는 값이 다른 화면에서는
// 없거나 다른 이름으로 보이고, 서버가 값을 늘려도 한쪽만 따라간다.
//
// ⚠ 값을 여기서 늘리지 말 것 — 서버 화이트리스트에 없는 값은 저장이 400 이다. 순서도 표시 순서라
//   임의로 바꾸지 않는다.

// @design SCREEN-005 @design SCREEN-029

/** 날씨 허용값 5종 — 코드와 표시가 같은 한글이다(자동 출처 없음·사람이 직접 고른다). */
export const WEATHER_OPTIONS = ['맑음', '흐림', '비', '눈', '안개'] as const;

/** 시간대 — 코드(BE)↔한글 표시. */
export const TIME_OF_DAY_OPTIONS = [
  { code: 'DAY', label: '주간' },
  { code: 'NGT', label: '야간' },
] as const;

/** 계절 — 코드(BE)↔한글 표시. */
export const SEASON_OPTIONS = [
  { code: 'SPRING', label: '봄' },
  { code: 'SUMMER', label: '여름' },
  { code: 'FALL', label: '가을' },
  { code: 'WINTER', label: '겨울' },
] as const;

/** 미선택(빈 값) 라벨 — 공통 Select 의 placeholder 와 달리 <b>선택 가능</b>해야 한다. */
export const UNSELECTED_LABEL = '선택 안 함';

/**
 * 코드 → 화면 표시 문구. 미선택(빈 값)이면 `null` 을 돌려 읽기 전용 행이 미입력 표식을 쓰게 한다.
 *
 * ★코드표에 없는 값이라도 <b>버리지 않고 원문을 보여준다</b> — 조용한 손실 금지. 서버가 어휘를
 * 늘렸는데 화면 사본이 아직 따라가지 못한 구간에서도 값이 사라지지 않아야 한다.
 */
export function displayOf(
  value: string,
  options: readonly { code: string; label: string }[] | readonly string[],
): string | null {
  if (value === '') return null;
  for (const opt of options) {
    if (typeof opt === 'string') {
      if (opt === value) return opt;
    } else if (opt.code === value) {
      return opt.label;
    }
  }
  return value;
}
