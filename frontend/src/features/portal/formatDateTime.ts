/**
 * 포털 채널 목록의 일시 표기 — **분까지**.
 *
 * <h3>왜 채널 전용 포맷터를 두나</h3>
 * 관제 공용 포맷터(`features/review/formatDateTime`)는 브라우저 로케일에 맡겨
 * `2026. 9. 8. 오후 3:09:22` 같은 문자열을 만든다. 목록 표에서 이 형태는 두 가지로 나쁘다.
 *
 * <ul>
 *   <li><b>초는 목록에서 의미가 없고 열 폭만 먹는다.</b> 그 자리는 언제 일어났는지를 훑는
 *       자리이지 초를 대조하는 자리가 아니다.</li>
 *   <li><b>자리폭이 값마다 달라져 세로로 훑을 수 없다.</b> 오전/오후·한 자리 월·한 자리 시가
 *       섞이면 같은 열의 값들이 서로 다른 자리에서 시작한다. 목록의 일시는 **행끼리 비교**하는
 *       값이라 이 성질이 곧 읽기 비용이다.</li>
 * </ul>
 *
 * 그래서 포털 목록 셋(내 작업 · 내 업로드 · 증강)은 이 하나를 함께 쓴다 — 같은 채널에서 같은
 * 성격의 값이 화면마다 다른 모양으로 뜨지 않게. 만료 예정일(`expiry`)이 이미 `YYYY-MM-DD` 라
 * 이 표기가 그것과 자릿수까지 맞는다.
 *
 * ⚠ **파싱되지 않으면 원문을 그대로 보인다** — 지어낸 `-` 로 덮으면 서버가 준 값이 사라져,
 *   값이 이상하다는 사실 자체를 아무도 볼 수 없게 된다.
 *
 * ⚠ 이것은 **표시 축**이다. 정렬·판정은 서버가 하고 화면은 받은 순서를 그대로 그린다.
 *
 * @design SCREEN-028
 * @design SCREEN-033
 * @design SCREEN-044
 */

const pad = (n: number): string => String(n).padStart(2, '0');

export function formatPortalDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}`
  );
}
