// 사용자 표시 이름 — 화면 선판정 규칙.
// [@design SCREEN-024] [@design API-004] [@design AC-1018] [@design AC-1019]
//
// <h3>★왜 `api.ts` 가 아니라 별도 파일인가</h3>
// 그 모듈은 시험이 <b>모듈 단위로 자동 모의</b>할 수 있다(`vi.mock('../api')`). 자동 모의는 HTTP
// 함수뿐 아니라 그 파일이 함께 내보내는 <b>판정 함수·상수까지</b> `undefined` 로 만든다. 판정을
// 거기 두면 화면은 예외 없이 「모르는 값」 분기로 조용히 떨어지고, 작성자는 통신만 막았다고 믿는다
// (이 저장소가 CO-014 에서 실제로 겪은 형태이며, 같은 이유로 `verificationQuestionRules` 도
// 창구 모듈 밖에 있다).
//
// <h3>★상한의 단일 원천은 저장하는 자리다</h3>
// 창구 [[API-004]] 는 폭을 <b>숫자로 적지 않고</b> "저장하는 자리가 정한다"고만 규정한다. 서버
// 진실원은 `UserDisplayNames.MAX_USER_NM_LENGTH` 이며 저장 컬럼 폭(`USER_NM VARCHAR(100)`)과 같다.
// 이 파일이 그 계약을 프론트에 옮겨 놓은 <b>유일한 자리</b>다 — <b>화면 컴포넌트는 숫자를 따로
// 갖지 않고</b> 안내 문구도 이 상수를 참조해 만든다. 양쪽에 적으면 한쪽만 고쳐진다.

/**
 * 표시 이름 길이 상한 — 저장 컬럼 폭 `LS_ACNT_USER.USER_NM VARCHAR(100)`.
 *
 * 서버 진실원은 `UserDisplayNames.MAX_USER_NM_LENGTH` 다.
 * ⚠ 이 숫자를 화면·시험에 복제하지 말고 이 상수를 참조할 것.
 */
export const USER_NM_MAX_LENGTH = 100;

/**
 * 저장 전에 걷어내는 문자 — 제어문자(`\p{Cc}`)와 줄·문단 구분자(`\p{Zl}`·`\p{Zp}`).
 * 그 뒤 JS `trim()` 이 <b>앞뒤</b> 공백(유니코드 `Zs` 와 U+FEFF 포함)을 턴다.
 *
 * ★<b>서버와 같은 집합이 아니다 — 서버가 더 넓게 턴다.</b> 서버
 * (`UserDisplayNames.normalize` → `VisibleTextNormalizer`)는 여기에 더해 서식문자(`\p{Cf}`)를
 * <b>제거</b>하고 유니코드 공백(`\p{Zs}`)을 일반 공백으로 <b>치환</b>한다. 화면은 그 부분집합만
 * 선판정하므로 `Cf` 만으로 된 입력(U+200B ZWSP · U+2060 · U+202E RLO · U+200F RLM)은 화면을
 * 통과하고 <b>서버에서 400</b> 으로 걸리며, "상한 + U+200B" 는 반대로 화면이 `tooLong` 으로
 * 막지만 서버는 상한 이내로 보고 받아들인다(양쪽 함수를 구동해 실측).
 *
 * 방향이 <b>fail-closed</b>(서버가 더 엄격)라 계약·보안 위반은 아니다 — 막을 수 있는 왕복이
 * 남아 있을 뿐이다. 집합을 맞추려면 이 정규식에 `\p{Cf}` 를 더하고 `Zs` → 일반 공백 치환을
 * 두어야 하는데, 그것은 <b>동작 변경이라 시험·변이 검증이 따라와야 한다</b>(잔여 사안).
 *
 * 이 값은 화면 표시뿐 아니라 JWT `name` 클레임과 다른 코드의 로그로 흘러가므로, 개행·구분자가
 * 섞이면 기록을 위조할 수 있다(CWE-117).
 *
 * ⚠ 제어문자를 <b>실제 바이트</b>로 적지 않고 유니코드 속성으로 표기한다 — 소스에 진짜 제어
 * 바이트가 들어가면 git 이 그 파일을 바이너리로 판정해 diff 가 사라지고 `grep` 이 파일을 통째로
 * 건너뛴다(전수 점검의 「0건」이 「없다」가 아니라 「안 봤다」가 된다).
 */
const USER_NM_STRIPPED_CHAR = /[\p{Cc}\p{Zl}\p{Zp}]/gu;

/**
 * 서버가 저장 직전에 하는 정규화를 <b>부분집합으로</b> 재현한다 — 걷어낸 <b>뒤</b> 앞뒤 공백을
 * 턴다. 걷어내는 범위가 서버와 어떻게 다른지는 위 `USER_NM_STRIPPED_CHAR` 주석에 적었다.
 *
 * 화면이 이 값을 쓰는 곳은 둘이다. ①판정(빈 값·폭 초과) ②<b>바뀌었는지</b> 비교 —
 * 서버가 정규화한 값으로 저장하므로, 비교도 정규화한 값끼리 해야 「공백만 덧붙인 재저장」이
 * 변경으로 오인되지 않는다([[AC-1018]] "정규화한 표시 이름이 이미 저장된 값과 같으면 아무것도
 * 바뀌지 않고 수정일시도 밀리지 않는다").
 */
export function normalizeUserNm(raw: string | null | undefined): string {
  if (raw == null) {
    return '';
  }
  return raw.replace(USER_NM_STRIPPED_CHAR, '').trim();
}

/** 표시 이름이 저장될 수 없는 사유. */
export type UserNmViolationKind = 'empty' | 'tooLong';

export interface UserNmViolation {
  kind: UserNmViolationKind;
  /** 입력칸 옆에 그대로 보여줄 문구 — 필드 경로·항목 순번을 담지 않는다(자리가 어느 칸인지 말한다). */
  message: string;
}

/**
 * 표시 이름 선판정 — 저장 창구를 부르기 <b>전에</b> 화면이 먼저 막는다([[AC-1019]]).
 *
 * 판정 대상은 <b>정규화한 값</b>이다. 창구가 "저장 직전에 공백과 제어문자를 걷어낸 값으로
 * 판정한다"고 규정하므로 화면도 같은 값을 본다 — 원문으로 재면 「제어문자를 섞어 상한을 넘긴
 * 입력」이 화면에서만 막히고 서버는 통과시키는, 두 입구의 판정이 갈린 상태가 된다.
 *
 * ⚠ 폭을 넘는 값을 <b>잘라서</b> 통과시키지 않는다. 이름은 사람을 알아보는 값이라 조용히 잘리면
 * 다른 사람으로 보인다 — 그래서 입력칸에도 `maxLength` 를 걸지 않는다(브라우저가 붙여넣기를
 * 말없이 잘라 같은 일이 벌어진다).
 *
 * @returns 저장해도 되면 `null`, 아니면 사유
 */
export function validateUserNm(raw: string): UserNmViolation | null {
  const normalized = normalizeUserNm(raw);
  if (normalized === '') {
    return { kind: 'empty', message: '표시 이름을 입력하세요.' };
  }
  if (normalized.length > USER_NM_MAX_LENGTH) {
    return {
      kind: 'tooLong',
      message: `표시 이름은 ${USER_NM_MAX_LENGTH}자 이하여야 합니다. 지금 ${normalized.length}자입니다.`,
    };
  }
  return null;
}
