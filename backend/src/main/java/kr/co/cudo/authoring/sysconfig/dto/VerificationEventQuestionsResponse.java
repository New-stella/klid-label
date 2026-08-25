package kr.co.cudo.authoring.sysconfig.dto;

import java.util.List;

/**
 * 질문 목록 전체 교체 결과. [design: API-220]
 *
 * @param vrfcEvntTypeCd 교체 대상 검증 이벤트 유형 코드
 * @param questions      교체 후 질문 목록 — 정렬순서 오름차순.
 *                       빈 배열이면 그 유형에 질문이 없다(어노테이션 질문 칸을 비운 채로 둔다)
 */
public record VerificationEventQuestionsResponse(
        String vrfcEvntTypeCd,
        List<VerificationEventQuestionResponse> questions
) {

    public static VerificationEventQuestionsResponse of(
            String vrfcEvntTypeCd, List<VerificationEventQuestionResponse> questions) {
        return new VerificationEventQuestionsResponse(
                vrfcEvntTypeCd, questions == null ? List.of() : List.copyOf(questions));
    }
}
