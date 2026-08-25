package kr.co.cudo.authoring.sysconfig.dto;

import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntType;

import java.util.List;

/**
 * 검증 이벤트 유형 1건 + 그 유형의 질문 목록 + 관제 이벤트유형 짝. [design: API-219]
 *
 * @param vrfcEvntTypeCd    검증 이벤트 유형 코드 — 외부 시계열 위탁 요청에 싣는 값이자 질문 목록의 소유 키
 * @param vrfcEvntTypeNm    검증 이벤트 유형명 — 화면 표시용
 * @param vrfcEvntTypeExpln 검증 이벤트 유형 설명. 없으면 {@code null}
 * @param sortSeq           화면 표시 순서. 응답은 이 값 오름차순으로 정렬된다
 * @param evntTypeCds       이 검증 유형과 <b>짝지어 수신된</b> 관제 이벤트유형 코드 목록.
 *                          <b>인입 원장에서 읽은 값</b>이며 짝이 없으면 빈 배열이다 — 매핑표를 만들지 않는다
 * @param questions         그 유형의 질문 목록 — 정렬순서 오름차순. 질문이 없으면 빈 배열
 */
public record VerificationEventTypeResponse(
        String vrfcEvntTypeCd,
        String vrfcEvntTypeNm,
        String vrfcEvntTypeExpln,
        Integer sortSeq,
        List<String> evntTypeCds,
        List<VerificationEventQuestionResponse> questions
) {

    public static VerificationEventTypeResponse of(LsVrfcEvntType type,
                                                   List<String> evntTypeCds,
                                                   List<VerificationEventQuestionResponse> questions) {
        return new VerificationEventTypeResponse(
                type.getVrfcEvntTypeCd(),
                type.getVrfcEvntTypeNm(),
                type.getVrfcEvntTypeExpln(),
                type.getSortSeq(),
                evntTypeCds == null ? List.of() : List.copyOf(evntTypeCds),
                questions == null ? List.of() : List.copyOf(questions));
    }
}
