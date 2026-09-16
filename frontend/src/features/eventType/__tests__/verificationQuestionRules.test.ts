// 검증 이벤트 질문 문구 — 선판정 규칙 + 서버 거부 응답 해석. 회귀 가드.
// [@design SCREEN-038] [@design API-220] [@design AC-1129] [@design AC-1130]
//
// 고정하는 계약:
//  ① 화면 선판정이 <b>저장 창구보다 느슨하지 않다</b> — 창구가 거절할 값을 화면이 통과시키지 않는다.
//  ② 서버가 준 <b>진단 문자열</b>(필드 경로·배열 순번)과 <b>사람이 읽는 단일 안내</b>를 갈라낸다.
//     둘을 한 축으로 다루면 한쪽이 반드시 망가진다 — 전자를 그대로 띄우면 내부 구조가 새고,
//     후자를 감추면 사용자가 사유를 잃는다.
//
// ⚠ mutation 확인 절차: ①은 `validateQuestionText` 의 각 분기를 `return null` 로 바꾸면,
//   ②는 `parseFieldErrorRows` 가 늘 `null`(또는 늘 `[]`)을 돌려주게 만들면 각각 FAIL 해야 한다.

import { describe, expect, it } from 'vitest';

import {
  QSTN_CN_MAX_LENGTH,
  parseFieldErrorRows,
  validateQuestionText,
} from '../verificationQuestionRules';

describe('질문 문구 선판정 (AC-1129)', () => {
  /*
   * ★값 고정 축 — 형식만 보는 검사는 값이 바뀌어도 통과한다.
   *
   * 아래 케이스들은 전부 상수를 참조해 길이를 만들므로, 상수 자체가 엉뚱한 값으로 바뀌면
   * 그 케이스들은 <b>그 엉뚱한 값에 맞춰</b> 함께 움직여 아무도 눈치채지 못한다. 그래서 상한
   * 값 자체를 저장 창구 계약에 한 번 못박는다.
   *
   * 출처: API-220 요청 스키마 `qstnCn.maxLength` = 서버 진실원 `LsVrfcEvntQstn.QSTN_CN_MAX_LENGTH`
   *       = 저장 컬럼 폭 `QSTN_CN VARCHAR(4000)`.
   */
  it('★길이_상한은_저장_창구_계약값과_같다', () => {
    expect(QSTN_CN_MAX_LENGTH).toBe(4000);
  });

  it('빈_문구는_막는다', () => {
    expect(validateQuestionText('')?.kind).toBe('empty');
  });

  it('공백만_있는_문구도_막는다', () => {
    // 서버도 앞뒤 공백을 걷어낸 뒤 판정하므로(`normalizeQstnCn`) 화면도 같은 자리에서 막는다.
    expect(validateQuestionText('   ')?.kind).toBe('empty');
    expect(validateQuestionText('\t\t')?.kind).toBe('empty');
  });

  it('개행이_섞이면_막는다', () => {
    // 이 문구는 외부 사업자 요청 본문과 로그에 그대로 실린다 — 개행이 섞이면 로그 한 줄에 여러
    // 줄이 들어가 기록을 위조할 수 있다(CWE-117).
    expect(validateQuestionText('앞줄\n뒷줄')?.kind).toBe('control');
    expect(validateQuestionText('앞줄\r\n뒷줄')?.kind).toBe('control');
  });

  it('제어문자와_DEL_도_막는다', () => {
    expect(validateQuestionText('탭\t섞임')?.kind).toBe('control');
    expect(validateQuestionText('널\x00섞임')?.kind).toBe('control');
    expect(validateQuestionText('삭제\x7F섞임')?.kind).toBe('control');
  });

  it('상한을_넘으면_막고_상한_자체는_통과한다', () => {
    expect(validateQuestionText('가'.repeat(QSTN_CN_MAX_LENGTH + 1))?.kind).toBe('tooLong');
    // 경계 자체는 정상값이다 — 한 칸 좁게 막으면 정당한 입력을 거절한다.
    expect(validateQuestionText('가'.repeat(QSTN_CN_MAX_LENGTH))).toBeNull();
  });

  it('★끝_공백_때문에_원문이_상한을_넘으면_막는다_창구보다_느슨하지_않다', () => {
    // 서버 컨트롤러의 `@Size` 는 <b>원문</b>에 걸린다 — 걷어낸 값으로만 재면 이 값이 화면을
    // 통과한 뒤 서버에서 400 으로 되돌아온다(막을 수 있는 왕복).
    const raw = '가'.repeat(QSTN_CN_MAX_LENGTH) + ' ';
    expect(raw.trim().length).toBe(QSTN_CN_MAX_LENGTH); // 걷어내면 상한 이내인데도
    expect(validateQuestionText(raw)?.kind).toBe('tooLong'); // 원문 기준으로 막는다
  });

  it('정상_문구는_통과한다', () => {
    expect(
      validateQuestionText("영상에서 '화재' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?"),
    ).toBeNull();
    // 앞뒤 공백만 있는 정상 문구는 서버가 걷어내고 저장하므로 화면도 막지 않는다.
    expect(validateQuestionText('  정상 문구  ')).toBeNull();
  });

  it('사유마다_사람이_읽는_문구를_준다_순번이나_내부표기를_담지_않는다', () => {
    for (const raw of ['', '앞\n뒤', '가'.repeat(QSTN_CN_MAX_LENGTH + 1)]) {
      const message = validateQuestionText(raw)?.message ?? '';
      expect(message.length).toBeGreaterThan(0);
      // 어느 질문인지는 <b>자리</b>가 말한다 — 문구가 순번이나 필드 경로를 적으면 안 된다.
      expect(message).not.toMatch(/questions\[|qstnCn|번째/);
    }
  });
});

describe('서버 거부 응답 해석 (AC-1130)', () => {
  /*
   * 발주처 오류 증적에 실제로 화면에 떴던 문자열이다. 서버 `GlobalExceptionHandler` 가
   * Bean Validation 필드 오류를 `필드경로: 문구` 로 이어 붙여 만든다.
   */
  const 증적_진단문자열 =
    'questions[16].qstnCn: 질문 문구는 비어 있을 수 없습니다., ' +
    'questions[18].qstnCn: 질문 문구에는 개행·제어문자를 넣을 수 없습니다.';

  it('★진단_문자열에서_대상_줄의_자리만_뽑는다', () => {
    // 0부터 세는 배열 색인 — 화면이 사람이 읽는 위치(17번째·19번째)로 바꿔 쓴다.
    expect(parseFieldErrorRows(증적_진단문자열)).toEqual([16, 18]);
  });

  it('두_번째_증적_문자열도_해석한다', () => {
    expect(parseFieldErrorRows('questions[1].qstnCn: 질문 문구는 4000자 이하여야 합니다')).toEqual([1]);
  });

  it('자리가_겹치거나_뒤섞여_와도_정리해서_준다', () => {
    const message =
      'questions[5].qstnCn: 질문 문구는 비어 있을 수 없습니다., ' +
      'questions[2].qstnCn: 질문 문구는 비어 있을 수 없습니다., ' +
      'questions[5].qstnCn: 질문 문구에는 개행·제어문자를 넣을 수 없습니다.';
    expect(parseFieldErrorRows(message)).toEqual([2, 5]);
  });

  it('★배열_순번이_없는_필드_오류도_진단_문자열로_본다', () => {
    // 목록 자체가 거부되면(`@NotNull`) 색인이 없다. 이것을 「사람이 읽는 안내」로 흘려보내면
    // 필드 이름이 그대로 화면에 뜬다 — 짚을 줄이 없을 뿐 진단 문자열인 것은 같다.
    expect(parseFieldErrorRows('questions: 질문 목록은 필수입니다.')).toEqual([]);
  });

  // ── 갈라내는 쪽 — 사람이 읽는 단일 안내는 진단 문자열이 아니다 ──────────────────

  it('★서비스_2차_방어선의_안내는_진단_문자열로_보지_않는다', () => {
    // 이 문장은 이미 사람이 읽는 위치 표기(3번째)를 담고 있다. 감추면 사용자가 사유를 잃는다.
    const message =
      '3번째 질문 문구가 올바르지 않습니다. 빈 값·개행·제어문자를 넣을 수 없고 4000자 이하여야 합니다.';
    expect(parseFieldErrorRows(message)).toBeNull();
  });

  it('★저장_창구가_규정한_400_예시_문구도_그대로_통과시킨다', () => {
    // API-220 의 400 예시는 필드 경로 없는 단일 문장이다.
    expect(parseFieldErrorRows('질문 문구에는 개행·제어문자를 넣을 수 없습니다.')).toBeNull();
  });

  it('일반_실패_문구와_업무_안내는_진단_문자열이_아니다', () => {
    expect(parseFieldErrorRows('저장에 실패했습니다.')).toBeNull();
    expect(parseFieldErrorRows('검증 이벤트 유형을 찾을 수 없습니다.')).toBeNull();
    // 콜론이 들어 있어도 앞이 ASCII 식별자가 아니면 진단 문자열이 아니다.
    expect(parseFieldErrorRows('안내: 잠시 후 다시 시도하세요.')).toBeNull();
  });
});
