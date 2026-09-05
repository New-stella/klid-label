/**
 * 프리셋 화면의 이벤트유형 표기 — `이벤트명 (유형코드)`. [@design SCREEN-026]
 *
 * 프리셋에는 이름이 없고 식별 축이 이벤트유형 하나이므로, 카드 제목과 편집 모달 옵션이
 * 같은 형태로 읽혀야 한다. 두 곳이 각자 문자열을 조립하면 한쪽만 바뀌어 갈린다.
 *
 * ★표시명 자체를 여기서 <b>해석하지 않는다</b>. 4단 폴백(운영자 표시명 → 관제 수신 유형명 →
 * 카테고리명 → 유형코드)의 판정은 서버 한 곳에 있고, 화면은 서버가 준 값을 받아 코드와
 * 나란히 놓을 뿐이다. 화면이 이벤트 목록으로 코드를 역해석하면 그 목록에 없는 유형(제외
 * 대분류 등)이 코드로만 노출된다 — 실제로 그 결함이 있었다.
 *
 * 유형코드를 함께 보이는 이유는 <b>같은 표시명을 가진 서로 다른 유형</b>이 존재하기 때문이다
 * (예: 화재 EV02000101 / 일반화재 EV02000102). 이름만으로는 어느 유형인지 특정되지 않는다.
 *
 * @param name 서버가 해석해 준 이벤트 표시명. 비어 있으면 코드만 보인다.
 * @param code 이벤트유형코드.
 * @returns `이벤트명 (유형코드)` — 단 이름이 없거나 코드와 같으면 코드만(`X (X)` 방지).
 */
export function formatEventTypeDisplay(
  name: string | null | undefined,
  code: string | null | undefined,
): string {
  const safeCode = (code ?? '').trim();
  const safeName = (name ?? '').trim();
  if (!safeName || safeName === safeCode) return safeCode;
  if (!safeCode) return safeName;
  return `${safeName} (${safeCode})`;
}
