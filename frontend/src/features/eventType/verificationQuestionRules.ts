// 검증 이벤트 질문 문구 — 화면 선판정 규칙 + 서버 거부 응답 해석.
// [@design SCREEN-038] [@design API-220] [@design AC-1129] [@design AC-1130]
//
// <h3>★왜 `verificationApi.ts` 가 아니라 별도 파일인가</h3>
// 그 모듈은 시험이 <b>모듈 단위로 자동 모의</b>한다(`vi.mock('../verificationApi')`). 자동 모의는
// HTTP 함수뿐 아니라 그 파일이 함께 내보내는 <b>판정 함수·상수까지</b> `undefined` 로 만든다.
// 판정을 거기 두면 화면은 예외 없이 「모르는 값」 분기로 조용히 떨어지고, 작성자는 통신만 막았다고
// 믿는다(이 저장소가 CO-014 에서 실제로 겪은 형태다). 그래서 판정은 여기 따로 둔다.
//
// <h3>★상한·허용문자의 단일 원천은 저장 창구다</h3>
// 값의 출처는 API-220 요청 스키마이며 그 서버측 진실원은 `LsVrfcEvntQstn` 한 곳이다. 이 파일은
// 그 계약을 프론트에 옮겨 놓은 <b>유일한 자리</b>이고, <b>화면 컴포넌트는 숫자를 따로 갖지 않는다</b> —
// 문구도 이 상수를 참조해 만든다. 양쪽에 적으면 한쪽만 고쳐진다.

/**
 * 질문 문구 길이 상한 — 저장 창구 [[API-220]] 요청 스키마의 `qstnCn.maxLength`.
 *
 * 서버 진실원은 `LsVrfcEvntQstn.QSTN_CN_MAX_LENGTH` 이며 저장 컬럼 폭(`QSTN_CN VARCHAR(4000)`)과 같다.
 * ⚠ 이 숫자를 화면·시험에 복제하지 말고 이 상수를 참조할 것.
 */
export const QSTN_CN_MAX_LENGTH = 4000;

/**
 * 허용하지 않는 문자 — C0 제어문자(`0x00~0x1F`, 개행 포함)와 DEL(`0x7F`).
 *
 * 서버 진실원은 `LsVrfcEvntQstn.QSTN_CN_ALLOWED_REGEX` 의 여집합이다. 이 문구는 <b>외부 사업자
 * 요청 본문과 로그에 그대로 실리므로</b> 개행이 섞이면 기록을 위조할 수 있고(CWE-117) 사업자 쪽
 * 파싱도 깨진다.
 */
// eslint-disable-next-line no-control-regex -- 제어문자 차단이 이 정규식의 목적이다(서버 규칙 미러).
const QSTN_CN_FORBIDDEN_CHAR = /[\x00-\x1F\x7F]/;

/** 질문 문구가 저장될 수 없는 사유. */
export type QuestionViolationKind = 'empty' | 'control' | 'tooLong';

export interface QuestionViolation {
  kind: QuestionViolationKind;
  /** 그 질문 행 옆에 그대로 보여줄 문구 — 순번·내부 표기를 담지 않는다(자리가 어느 질문인지 말한다). */
  message: string;
}

/**
 * 질문 문구 1건 선판정 — 저장 창구를 부르기 <b>전에</b> 화면이 먼저 막는다.
 *
 * <h3>★창구보다 느슨하지 않다</h3>
 * 서버는 두 겹으로 본다 — 컨트롤러의 Bean Validation 은 <b>원문</b>에(`@NotBlank`·`@Size`·`@Pattern`),
 * 서비스 2차 방어선은 <b>앞뒤 공백을 걷어낸 값</b>에 건다. 그래서 여기서도
 * 「빈 값 판정은 걷어낸 값으로, 길이 판정은 원문으로」 본다. 길이를 걷어낸 값으로만 재면
 * 「4000자 + 끝 공백」이 화면을 통과한 뒤 서버 `@Size` 에서 400 으로 되돌아온다(막을 수 있는 왕복).
 *
 * @returns 저장해도 되면 `null`, 아니면 사유
 */
export function validateQuestionText(raw: string): QuestionViolation | null {
  const trimmed = raw.trim();
  if (trimmed === '') {
    return { kind: 'empty', message: '질문 문구를 입력하세요.' };
  }
  if (QSTN_CN_FORBIDDEN_CHAR.test(raw)) {
    return { kind: 'control', message: '줄바꿈과 특수 제어문자는 넣을 수 없습니다. 한 줄로 입력하세요.' };
  }
  if (raw.length > QSTN_CN_MAX_LENGTH) {
    return {
      kind: 'tooLong',
      message: `질문 문구는 ${QSTN_CN_MAX_LENGTH}자 이하여야 합니다. 지금 ${raw.length}자입니다.`,
    };
  }
  return null;
}

/**
 * 서버 거부 응답이 <b>필드 단위 검증 실패 모음</b>인지 판정하고, 그렇다면 대상 행을 집어낸다.
 *
 * <h3>★왜 필요한가 (증적 2·3 의 본체)</h3>
 * 컨트롤러의 Bean Validation 이 걸리면 서버는 필드 오류를 이어 붙인 <b>진단 문자열</b>을 돌려준다:
 *
 * ```
 * questions[16].qstnCn: 질문 문구는 비어 있을 수 없습니다., questions[18].qstnCn: 질문 문구에는 …
 * ```
 *
 * 이 문자열을 그대로 띄우면 사용자는 자기가 <b>몇 번째 질문의 무엇을</b> 고쳐야 하는지 알 수 없고
 * 대신 내부 구조(필드 경로·배열 순번)만 본다. 그래서 여기서 대상 행만 뽑아내고 문구는 버린다 —
 * 사용자에게 보일 말은 화면이 자기 규칙으로 다시 만든다.
 *
 * <h3>★업무 충돌 안내와 섞지 않는다</h3>
 * 같은 400 이라도 서비스 2차 방어선은 <b>사람이 읽는 단일 문장</b>을 준다
 * (예: `3번째 질문 문구가 올바르지 않습니다. …`). 그런 응답은 그대로 보여주는 것이 맞다 —
 * 이미 사람이 읽는 위치 표기이고, 감추면 사용자가 사유를 잃는다. 판정 기준은 <b>모양</b>이다:
 * `식별자[숫자].식별자:` 꼴로 시작하는 조각이 하나라도 있으면 진단 문자열로 본다.
 * 한글 안내문은 이 모양이 될 수 없다(경로는 ASCII 식별자로만 이루어진다).
 *
 * @returns 진단 문자열이면 대상 행의 <b>0부터 세는</b> 색인 배열(중복 제거·오름차순),
 *          진단 문자열이 아니면 `null`
 */
export function parseFieldErrorRows(message: string): number[] | null {
  // 조각 하나 = `경로: 문구`. 경로는 ASCII 식별자에 대괄호 색인과 점이 붙은 꼴이다.
  //
  // ⚠ 색인이 <b>없는</b> 경로도 진단 문자열로 본다 — 목록 자체가 거부되면(`@NotNull`) 서버는
  //   `questions: 질문 목록은 필수입니다.` 를 준다. 색인이 있는 것만 잡으면 이 응답이 「사람이
  //   읽는 안내」로 새어 나가 필드 이름이 그대로 화면에 뜬다. 그때는 짚을 줄이 없으므로 빈 배열을
  //   돌려주고, 호출부가 일반 안내로 내린다.
  // ⚠ 한글 안내문은 이 모양이 될 수 없고(경로는 ASCII 로만 이루어진다) 서비스 2차 방어선 문구는
  //   숫자로 시작하므로(`3번째 …`) 여기에 걸리지 않는다.
  const ENTRY =
    /(?:^|,\s*)([A-Za-z_][A-Za-z0-9_]*(?:\[\d+\])?(?:\.[A-Za-z_][A-Za-z0-9_]*(?:\[\d+\])?)*)\s*:/g;

  const rows = new Set<number>();
  let sawEntry = false;
  for (const match of message.matchAll(ENTRY)) {
    sawEntry = true;
    const index = /\[(\d+)\]/.exec(match[1] ?? '');
    if (index) rows.add(Number(index[1]));
  }
  if (!sawEntry) return null;
  return [...rows].sort((a, b) => a - b);
}
