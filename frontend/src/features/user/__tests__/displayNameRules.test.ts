// 사용자 표시 이름 — 화면 선판정 규칙. 회귀 가드.
// [@design SCREEN-024] [@design API-004] [@design AC-1018] [@design AC-1019]
//
// 고정하는 계약:
//  ① 화면 선판정이 <b>저장 창구보다 느슨하지 않다</b> — 창구가 거절할 값을 화면이 통과시키지 않는다.
//  ② 정규화는 서버(`UserDisplayNames.normalize`)가 걷어내는 집합의 <b>부분집합</b>을 걷어낸다 —
//     서버가 더 넓게 턴다(서식문자 `\p{Cf}` 제거 · 유니코드 공백 치환). 방향이 <b>fail-closed</b>
//     (서버가 더 엄격)라 계약·보안 위반은 아니지만, 화면을 통과한 값이 서버에서 400 으로
//     되돌아오는 <b>막을 수 있는 왕복</b>이 남는다. 그 비대칭은 잔여 사안이며 아래 정규화
//     describe 의 마지막 케이스가 <b>현재 상태를 사실로</b> 결박한다(집합을 맞추면 RED 가 된다).
//  ③ 사용자에게 보일 문구에 필드 경로·항목 순번이 들어가지 않는다.
//
// ⚠ mutation 확인 절차: `validateUserNm` 의 각 분기를 `return null` 로 바꾸거나 `normalizeUserNm`
//    에서 제어문자 제거·trim 을 빼면 각각 FAIL 해야 한다.
//
// ⚠ 제어문자는 <b>16진 이스케이프</b>로 적는다(`\x00` 꼴). 실제 바이트를 넣으면 git 이 이 파일을
//    바이너리로 판정해 diff 가 사라지고 `grep` 이 파일을 통째로 건너뛴다.

import { describe, expect, it } from 'vitest';

import {
  USER_NM_MAX_LENGTH,
  normalizeUserNm,
  validateUserNm,
} from '../displayNameRules';

describe('표시 이름 선판정 (AC-1019)', () => {
  /*
   * ★값 고정 축 — 형식만 보는 검사는 값이 바뀌어도 통과한다.
   *
   * 아래 케이스들은 전부 상수를 참조해 길이를 만들므로, 상수 자체가 엉뚱한 값으로 바뀌면 그
   * 케이스들은 <b>그 엉뚱한 값에 맞춰</b> 함께 움직여 아무도 눈치채지 못한다. 그래서 상한 값
   * 자체를 저장하는 자리의 계약에 한 번 못박는다.
   *
   * 출처: 서버 진실원 `UserDisplayNames.MAX_USER_NM_LENGTH` = 저장 컬럼 폭
   *       `LS_ACNT_USER.USER_NM VARCHAR(100)`.
   */
  it('★길이_상한은_저장_컬럼_폭과_같다', () => {
    expect(USER_NM_MAX_LENGTH).toBe(100);
  });

  it('빈_이름은_막는다', () => {
    expect(validateUserNm('')?.kind).toBe('empty');
  });

  it('공백만_있는_이름도_막는다', () => {
    // 서버도 걷어낸 값으로 판정하므로(`UserDisplayNames.normalize`) 화면도 같은 자리에서 막는다.
    expect(validateUserNm('   ')?.kind).toBe('empty');
    expect(validateUserNm('\t\t')?.kind).toBe('empty');
  });

  it('★제어문자만_있는_이름도_막는다_걷어내면_남는_것이_없다', () => {
    // 서버는 걷어낸 뒤 비면 "값을 보내지 않음"과 같게 보고 400 이다. 화면이 이것을 통과시키면
    // 사용자는 저장을 누른 뒤에야 거부를 본다.
    expect(validateUserNm('\x00\x1F\x7F')?.kind).toBe('empty');
  });

  it('상한을_넘으면_막고_상한_자체는_통과한다', () => {
    expect(validateUserNm('가'.repeat(USER_NM_MAX_LENGTH + 1))?.kind).toBe('tooLong');
    // 경계 자체는 정상값이다 — 한 칸 좁게 막으면 정당한 입력을 거절한다.
    expect(validateUserNm('가'.repeat(USER_NM_MAX_LENGTH))).toBeNull();
  });

  it('★제어문자를_섞어_길이를_불린_값은_걷어낸_뒤_판정한다', () => {
    // 창구가 "걷어낸 값으로 판정"을 규정하므로 원문 길이로 재면 안 된다 — 원문으로 재면 서버가
    // 받아들일 값을 화면이 먼저 거절한다(두 입구의 판정이 갈린다).
    const raw = '가'.repeat(USER_NM_MAX_LENGTH) + '\x07\x07\x07';
    expect(raw.length).toBeGreaterThan(USER_NM_MAX_LENGTH); // 원문은 상한을 넘는데
    expect(validateUserNm(raw)).toBeNull(); // 걷어내면 상한 이내라 통과한다
  });

  it('정상_이름은_통과한다', () => {
    expect(validateUserNm('홍길동')).toBeNull();
    // 앞뒤 공백만 있는 정상 이름은 서버가 걷어내고 저장하므로 화면도 막지 않는다.
    expect(validateUserNm('  홍길동  ')).toBeNull();
  });

  it('사유마다_사람이_읽는_문구를_준다_필드경로나_순번을_담지_않는다', () => {
    for (const raw of ['', '가'.repeat(USER_NM_MAX_LENGTH + 1)]) {
      const message = validateUserNm(raw)?.message ?? '';
      expect(message.length).toBeGreaterThan(0);
      // 어느 칸인지는 <b>자리</b>가 말한다 — 문구가 필드 경로나 항목 순번을 적으면 안 된다.
      expect(message).not.toMatch(/userNm|users\[|번째/);
    }
  });
});

describe('표시 이름 정규화 — 서버가 걷어내는 집합의 부분집합이다 (AC-1018)', () => {
  it('앞뒤_공백을_턴다', () => {
    expect(normalizeUserNm('  홍길동  ')).toBe('홍길동');
  });

  it('제어문자와_DEL_을_걷어낸다', () => {
    // 이 값은 화면 표시뿐 아니라 JWT name 클레임과 로그로 흘러간다 — 개행이 섞이면 기록을
    // 위조할 수 있다(CWE-117).
    expect(normalizeUserNm('홍\x00길\x1F동\x7F')).toBe('홍길동');
    expect(normalizeUserNm('앞줄\r\n뒷줄')).toBe('앞줄뒷줄');
  });

  it('★걷어낸_뒤_공백을_턴다_순서가_뒤바뀌면_안_된다', () => {
    // 공백을 먼저 털면 제어문자에 가려진 바깥 공백이 남는다.
    expect(normalizeUserNm('\x00  홍길동  \x00')).toBe('홍길동');
  });

  it('값이_없으면_빈_문자열이다', () => {
    // 화면은 이 값을 「바뀌었는가」 비교의 기준점으로 쓰므로 null/undefined 를 한 축으로 좁힌다.
    expect(normalizeUserNm(null)).toBe('');
    expect(normalizeUserNm(undefined)).toBe('');
  });

  it('★같은_이름의_재저장을_「바뀜」으로_보지_않게_한다', () => {
    // 창구: "같은 이름을 다시 보내면 아무것도 바꾸지 않는다 — 수정일시도 밀지 않는다".
    // 화면이 그 왕복 자체를 없애려면 정규화한 값끼리 비교해야 한다.
    expect(normalizeUserNm('  홍길동  ')).toBe(normalizeUserNm('홍길동'));
  });

  it('★서식문자는_걷어내지_않는다_서버가_더_넓게_턴다', () => {
    // ⚠ 이 케이스는 「이것이 옳다」가 아니라 <b>지금 이렇다</b>를 적는다.
    //
    // 서버(`UserDisplayNames.normalize` → `VisibleTextNormalizer`)는 서식문자(`\p{Cf}`)를 제거하므로
    // 아래 값은 서버에서 빈 값이 되어 400 이고, 화면은 그대로 통과시켜 사용자는 저장을 누른 뒤에야
    // 거부를 본다(막을 수 있는 왕복). 방향이 <b>fail-closed</b>(서버가 더 엄격)라 계약·보안 위반은
    // 아니어서 <b>잔여로 남긴</b> 사안이며, 이 케이스가 그 상태를 코드에 기록한다.
    //
    // ★집합을 맞추려고 화면 정규식에 `\p{Cf}` 를 더하면 이 케이스가 RED 로 신호한다. 그때는 이
    //   케이스를 「걷어낸다」로 뒤집고 선판정 기대(`toBeNull` → `empty`)도 함께 옮긴다. 지금은
    //   그 변경에 아무 신호도 없다는 것이 이 케이스를 두는 이유다.
    //
    // ⚠ 서식문자는 <b>보이지 않아</b> 소스에 그대로 넣으면 눈으로도 리뷰로도 잡히지 않는다.
    //    유니코드 이스케이프 표기조차 이 환경에서는 도구를 거치며 실제 문자로 변환돼 박히므로
    //    (이 주석을 쓰다가 실제로 한 번 박혔고 아래 바이트 검사가 그것을 잡았다)
    //    16진 코드포인트로 <b>조립</b>한다 — 파일에는 ASCII 만 남는다.
    const cfOnly = String.fromCharCode(0x200b, 0x2060, 0x202e, 0x200f); // ZWSP · WJ · RLO · RLM

    expect(normalizeUserNm(cfOnly)).toBe(cfOnly); // 화면은 한 글자도 걷어내지 않고
    expect(validateUserNm(cfOnly)).toBeNull(); // 선판정도 통과시킨다 — 거절은 서버 몫이다
  });
});
