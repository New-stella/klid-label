// 이슈 타입/상태 한글 라벨 — IssueCard / IssueThreadPanel 공용 단일 정의.
// (중복 정의 제거 — code-reviewer #3)

import { resolveDisplayName } from '@/lib/displayName';

import { ISSUE_STATUS, ISSUE_TYPE, type IssueStatus, type IssueType } from './types';

/**
 * 이슈 <b>종류</b>의 화면 낱말 — 「반려 / 문의」.
 *
 * ★저장 코드값(REJECTION·INQUIRY)과 전이 규칙은 <b>바뀌지 않았다</b>. 바뀐 것은 사람이 읽는
 * 낱말뿐이다.
 *
 * ★왜 고쳤나 — 사양은 이 축을 어디서나 「문의」로 부른다(유형 배지 「반려/문의」 · 「문의 스레드」 ·
 * 「미해결 {n}건」 · 「문의 등록」). 구 낱말 「검수자 확인 요청」은 사양 어느 자리에도 없던 구현
 * 고유 문구였고, 같은 것을 가리키는 낱말이 한 화면 안에서 둘로 갈려 있었다.
 */
export const ISSUE_TYPE_LABEL: Record<IssueType, string> = {
  [ISSUE_TYPE.REJECTION]: '반려',
  [ISSUE_TYPE.INQUIRY]: '문의',
};

/**
 * 진행 상태의 <b>화면 낱말</b> — 「미해결 / 답변완료 / 해결」.
 *
 * ★저장 코드값(OPEN·ANSWERED·RESOLVED)과 전이 규칙은 <b>바뀌지 않았다</b>. 바뀐 것은 사람이
 * 읽는 낱말뿐이다.
 *
 * ★왜 고쳤나 — 같은 상태를 부르는 낱말이 <b>셋</b>으로 갈려 있었다: 화면 사양 두 벌
 * (「대기/답변완료/해소」·「미해결/…/해결」)과 이 코드(「열림/답변됨/해소됨」). 사양이 한 벌로
 * 통일되면서 이 표를 그쪽에 맞춘다. 건수 배지가 「미해결 {n}건」이므로 짝이 되는 낱말은
 * 「미해결」이고, 그 짝의 반대는 「해결」이다(동작 버튼은 「해결 처리」).
 */
export const ISSUE_STATUS_LABEL: Record<IssueStatus, string> = {
  [ISSUE_STATUS.OPEN]: '미해결',
  [ISSUE_STATUS.ANSWERED]: '답변완료',
  [ISSUE_STATUS.RESOLVED]: '해결',
};

/** 해결 처리 동작의 버튼 이름 — 상태 낱말(「해결」)과 동작을 구분한다. */
export const ISSUE_RESOLVE_ACTION_LABEL = '해결 처리';

// 작성자 역할 한글 라벨 — 화면에 코드값(WORKER/REVIEWER)을 그대로 노출하지 않는다.
// UI 호칭은 '검수자'로 통일(프로젝트 역할 정의).
const ISSUE_AUTHOR_ROLE_LABEL: Record<string, string> = {
  WORKER: '작업자',
  REVIEWER: '검수자',
  // 관리자도 문의·댓글을 쓴다 — 응답 계약의 작성자 역할 값에 포함돼 있다. 빠뜨리면 아래 폴백이
  // 코드값을 그대로 노출해 「홍길동 (ADMIN)」이 된다(이 표의 존재 이유가 그것을 막는 것이다).
  ADMIN: '관리자',
};

/** 매핑에 없는 코드는 원문 폴백 — 새 역할이 생겨도 빈칸이 되지 않게 한다. */
export function issueAuthorRoleLabel(roleCd: string): string {
  return ISSUE_AUTHOR_ROLE_LABEL[roleCd] ?? roleCd;
}

/**
 * 작성자 표기 — "{이름} ({역할})". 댓글 작성자와 스레드 작성자가 **같은 표기**를 쓴다.
 *
 * 폴백 순서:
 * - 이름이 없으면 사번으로 폴백한다(빈칸 금지).
 * - 사번마저 없으면 역할만 표기한다.
 * - **역할을 해석할 수 없으면(`null`/공백) 이름만** 표기한다 — 스레드 축은 BE 가 사용자 역할 매핑에서
 *   역할을 읽으므로 퇴사·미배정·비숫자 사번에서 `null` 이 온다. 그때 `"홍길동 ()"` 처럼 빈 괄호를
 *   남기면 값이 유실된 것처럼 보인다. 댓글 축은 작성 시점 역할이 컬럼에 박혀 있어 이 분기에 걸리지
 *   않지만, 두 축이 같은 함수를 쓰도록 여기서 함께 처리한다(표기 로직을 복제하면 한쪽만 갱신된다).
 * - 둘 다 없으면 빈 문자열 — 호출부가 falsy 로 판단해 미표시한다.
 */
export function issueAuthorLabel(
  name: string | null | undefined,
  userNo: string | null | undefined,
  roleCd: string | null | undefined,
): string {
  const who = resolveDisplayName(name, userNo) ?? '';
  const trimmedRole = typeof roleCd === 'string' ? roleCd.trim() : '';
  if (!trimmedRole) {
    return who;
  }
  const role = issueAuthorRoleLabel(trimmedRole);
  return who ? `${who} (${role})` : role;
}
