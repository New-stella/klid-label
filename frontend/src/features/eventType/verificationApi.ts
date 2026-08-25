// 검증 이벤트 유형·질문 관리 API — BE: /api/v1/manage/verification-event-types (REVIEWER 전용).
// [@design API-219] [@design API-220] [@design SCREEN-038]
//
// apiClient 응답 인터셉터가 ApiResponse<T> 래퍼를 언랩하므로 `.data` 만 추출한다.
//
// ★ 관제 이벤트유형 관리(`/manage/event-types`, adminApi.ts)와 <b>코드 체계가 다른 별개 축</b>이다.
//   한 모듈로 합치거나 한 목록으로 섞지 말 것 — 관제는 채번 코드(`EV…`), 검증은 외부 시계열 분석
//   사업자가 정한 소문자 스네이크 코드(`fire`·`car_accident`…)다.

import { apiClient } from '@/lib/api/client';

/**
 * 검증 이벤트 질문 1건 — BE `VerificationEventQuestionResponse` 1:1 미러.
 *
 * ★ `sortSeq` 는 <b>서버가 배열 순서에서 매긴 값</b>이라 화면이 다시 계산하지 않는다.
 *   화면이 저장할 때 보내는 것은 순서(배열)뿐이며 이 값을 되돌려 보내지 않는다.
 */
export interface VerificationEventQuestion {
  /** 검증이벤트질문일련번호. 마킹이 고른 질문을 가리키는 값이기도 하다. */
  vrfcEvntQstnSn: number;
  /** 유형 안에서의 순서 — <b>첫 번째가 그 유형의 기본 질문</b>이다. */
  sortSeq: number;
  /** 질문 문구 — 이벤트 어노테이션의 질문 칸에 그대로 들어간다. */
  qstnCn: string;
}

/** 검증 이벤트 유형 1건 + 질문 목록 + 관제 유형 짝 — BE `VerificationEventTypeResponse` 1:1 미러. */
export interface VerificationEventType {
  vrfcEvntTypeCd: string;
  vrfcEvntTypeNm: string;
  /** 유형 설명. 없으면 null. */
  vrfcEvntTypeExpln?: string | null;
  /** 화면 표시 순서. 응답이 이미 이 값 오름차순으로 정렬돼 온다. */
  sortSeq?: number | null;
  /**
   * 이 검증 유형과 <b>짝지어 수신된</b> 관제 이벤트유형 코드 목록.
   *
   * ★ 인입 원장에서 읽은 값이며 <b>매핑표가 아니다</b> — 화면이 대응을 스스로 만들거나 보정하지
   * 않는다. 여러 건일 수 있고, 한 건도 없으면 빈 배열이다(짝 없음이 정상 상태다).
   */
  evntTypeCds: string[];
  /** 그 유형의 질문 목록 — 정렬순서 오름차순. 질문이 없으면 빈 배열(허용되는 상태). */
  questions: VerificationEventQuestion[];
}

/** 질문 목록 전체 교체 결과 — BE `VerificationEventQuestionsResponse` 1:1 미러. */
export interface VerificationEventQuestionsResult {
  vrfcEvntTypeCd: string;
  questions: VerificationEventQuestion[];
}

/** 등록된 검증 이벤트 유형 전량 + 유형별 질문 + 관제 짝. */
export function getVerificationEventTypes(): Promise<VerificationEventType[]> {
  return apiClient
    .get<VerificationEventType[]>('/manage/verification-event-types')
    .then((r) => (r.data ?? []).map(normalize));
}

/**
 * 한 유형의 질문 목록 <b>전체 교체</b>(PUT).
 *
 * ★ 항목 단위 조작 통로가 없다 — 배열 순서가 곧 정렬순서이고 첫 번째가 기본 질문이다.
 * 여러 요청으로 나누면 그 사이 중간 상태에서 「첫 번째」가 흔들려, 그때 조달되는 질문이
 * 운영자가 의도하지 않은 문구가 된다.
 *
 * 빈 배열은 그 유형의 질문을 없앤다(정상). 개행·제어문자·과대 길이·공백만은 서버가
 * <b>요청 전체를 거부</b>하므로 화면은 편집 내용을 지우지 않고 사유만 보여준다.
 */
export function replaceVerificationEventQuestions(
  vrfcEvntTypeCd: string,
  questions: { qstnCn: string }[],
): Promise<VerificationEventQuestionsResult> {
  return apiClient
    .put<VerificationEventQuestionsResult>(
      // 경로 세그먼트는 반드시 인코딩한다 — 서버가 형식을 다시 검증하지만, 인코딩을 빼면
      // 여기서 만든 URL 자체가 경로를 벗어날 수 있다(CWE-22).
      `/manage/verification-event-types/${encodeURIComponent(vrfcEvntTypeCd)}/questions`,
      { questions },
    )
    .then((r) => ({
      vrfcEvntTypeCd: r.data?.vrfcEvntTypeCd ?? vrfcEvntTypeCd,
      questions: r.data?.questions ?? [],
    }));
}

/**
 * 응답 정규화 — 목록 필드가 없는 응답을 빈 배열로 접어 화면 분기를 하나로 유지한다.
 *
 * ⚠ <b>정렬은 하지 않는다</b>. 서버가 정렬순서 오름차순으로 내려주며, 화면이 다시 정렬하면
 * 화면이 보여준 「기본(첫 번째)」과 서버가 교정에 쓰는 「첫 번째」가 조용히 어긋난다.
 */
function normalize(t: VerificationEventType): VerificationEventType {
  return {
    ...t,
    evntTypeCds: t.evntTypeCds ?? [],
    questions: t.questions ?? [],
  };
}
