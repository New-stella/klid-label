/**
 * 요청 파라미터에서 "비어 있는 값"의 키를 통째로 제거한다.
 *
 * axios 는 `undefined` 값을 쿼리스트링에서 생략하지만 `''`(빈 문자열)·`null` 은 **그대로 보낸다**.
 * BE 는 `status=` 같은 빈 값을 400 으로 거부하는 파라미터가 있어(예: `GET /v1/tasks/board`)
 * "필터를 비웠더니 화면이 통째로 에러" 가 된다. 파라미터가 늘어날 때마다 호출부에서 truthy 체크를
 * 반복하다 하나를 빠뜨리는 것이 이 결함의 재발 경로이므로, **조립을 이 유틸 한 곳으로 모은다**.
 *
 * 제거 대상: `undefined` / `null` / `''` / 공백만 있는 문자열 / 빈 배열.
 * 보존 대상: `0`, `false` (page=0 이 사라지면 안 된다).
 */
export function compactParams<T extends object>(params: T): Partial<T> {
  const out: Record<string, unknown> = {};
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null) return;
    if (typeof value === 'string' && value.trim() === '') return;
    if (Array.isArray(value) && value.length === 0) return;
    out[key] = value;
  });
  return out as Partial<T>;
}
