/**
 * 사람 표시명 판정 — **이름 우선, 없으면 원값(사번·사용자 번호) 폴백**.
 *
 * 왜 필요한가: BE 응답은 사람 축을 항상 두 개로 내린다 — 원값(`regId` / `reporterNo` /
 * `reportedUserNo` = 내부 사용자 번호)과 표시명(`writerName` / `reporterName` /
 * `reportedUserName` = `MNG_ACCT_USER.USER_NM`). 화면은 **표시명을 보여주고** 그것이 없을 때만
 * 원값으로 폴백해야 한다. 폴백을 빼면 이름 해석이 실패하는 행(레거시 `REG_ID`·탈퇴·관제 계정 삭제)에서
 * 작성자·신고자가 통째로 사라진다. 반대로 표시명을 안 보면 사람 이름 자리에 내부 번호가 그대로 찍힌다.
 *
 * `null` 만 확인하면 안 되는 이유: 표시명이 빈 문자열이나 공백뿐일 수 있고, 그대로 쓰면 화면에
 * "작성자: " 처럼 빈칸이 남아 폴백이 무력화된다. 따라서 **공백만 있는 값도 "없음"으로 취급**한다.
 *
 * 이 판정은 `IssueThreadPanel`(스레드 작성자)·`issueAuthorLabel`(이슈 댓글 작성자)에 이미 있던
 * `name?.trim() || userNo?.trim()` 규칙과 동일하다 — 같은 판정이 화면마다 복붙되면 한쪽만 고쳐져
 * 갈라지므로 여기로 단일화한다.
 */

/**
 * @param name     표시명 (BE 가 사용자 마스터에서 해석한 이름 — 해석 실패 시 null)
 * @param fallback 원값 (사용자 번호·사번). 숫자 타입도 그대로 받는다(`reporterNo` 는 number).
 * @returns 표시할 문자열. 둘 다 비어 있으면 `null` — 호출부가 미표시/대시 등 기존 동작을 정한다.
 */
export function resolveDisplayName(
  name: string | null | undefined,
  fallback: string | number | null | undefined,
): string | null {
  const trimmedName = typeof name === 'string' ? name.trim() : '';
  if (trimmedName) {
    return trimmedName;
  }
  if (typeof fallback === 'number') {
    // NaN·Infinity 를 화면에 찍지 않는다.
    return Number.isFinite(fallback) ? String(fallback) : null;
  }
  const trimmedFallback = typeof fallback === 'string' ? fallback.trim() : '';
  return trimmedFallback || null;
}
