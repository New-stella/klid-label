// 날짜 표시 유틸 — Invalid Date 가드 (code-reviewer #4).
// BE regDt/createdAt 가 누락/비정상 문자열이어도 'Invalid Date' 가 렌더되지 않도록
// isNaN 검증 후 ko-KR 로케일 포맷, 실패 시 원문(또는 빈 문자열) 폴백.

/**
 * ISO 문자열을 ko-KR 로 포맷. 파싱 불가 시 원문 그대로 반환(빈 입력은 빈 문자열).
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('ko-KR');
}
