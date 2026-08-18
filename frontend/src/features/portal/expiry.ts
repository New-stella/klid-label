// 포털 보존기간 만료 예정일 표기 — 데이터마트 영상 축과 업로드 자산 축이 함께 쓴다.
// @design SCREEN-028, SCREEN-033
//
// ★ 만료 시각은 **저장되지 않는 파생값**이다. 서버가 조회 시점의 보존기간 설정으로 매번 다시
//   계산하므로 화면은 받은 값을 그대로 보여줄 뿐 따로 보관하거나 스스로 계산하지 않는다.
//   (설정이 바뀌면 다음 조회부터 값이 달라진다 — 화면이 캐시하면 그 순간 어긋난다.)
//
// ★ 만료가 **없는 상태**가 있다. 저장 라벨이 없는 영상, 아직 처리 중인 업로드 자산은 삭제 대상이
//   아니라 만료가 없다. 그때는 자리를 비운다 — `-`·`없음` 같은 문구를 지어내면 "만료가 정해졌는데
//   표기만 비었다"로 읽힌다.

/** 표기 형식 — 날짜까지만. 시각은 쓰지 않는다(사양 SCREEN-028·SCREEN-033). */
const YMD_LENGTH = 10;
const YMD_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/**
 * 만료 예정 시각(ISO `yyyy-MM-ddTHH:mm:ss`) → `yyyy-MM-dd`.
 *
 * 값이 없거나 날짜로 읽히지 않으면 `null` 을 돌려준다 — 호출측은 그 경우 표기를 통째로 생략한다.
 * 시간대 변환을 하지 않는 이유: 서버가 주는 값은 오프셋이 없는 로컬 일시라 `Date` 로 파싱하면
 * 실행 환경 시간대에 따라 하루가 밀린다.
 */
export function formatExpiryDate(value: string | null | undefined): string | null {
  if (typeof value !== 'string') return null;
  const ymd = value.slice(0, YMD_LENGTH);
  return YMD_PATTERN.test(ymd) ? ymd : null;
}
