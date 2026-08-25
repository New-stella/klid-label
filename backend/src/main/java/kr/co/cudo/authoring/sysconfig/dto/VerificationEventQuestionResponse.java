package kr.co.cudo.authoring.sysconfig.dto;

import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntQstn;

/**
 * 검증 이벤트 유형 질문 1건. [design: API-219 · API-220]
 *
 * <p>관리 화면 응답(조회·전체 교체)과 <b>조달 판정기</b>({@code VerificationEventQuestionResolver})의
 * 반환형을 <b>겸한다</b>. 같은 것을 두 모양으로 두면 매퍼가 둘로 갈라지고, 한쪽만 고쳐져 화면이 보는
 * 질문과 어노테이션에 실리는 질문이 어긋난다.
 *
 * <p>엔티티를 도메인 밖으로 내보내지 않기 위한 경계이기도 하다 — 소비자(마킹·배치·어노테이션)는
 * 이 불변 레코드만 본다.
 *
 * @param vrfcEvntQstnSn 검증이벤트질문일련번호 — 마킹에서 고른 질문을 가리키는 값
 * @param sortSeq        유형 안에서의 순서. <b>첫 번째가 그 유형의 기본 질문</b>이다
 * @param qstnCn         질문 문구 — 이벤트 어노테이션의 질문 칸에 그대로 들어간다
 */
public record VerificationEventQuestionResponse(
        Long vrfcEvntQstnSn,
        Integer sortSeq,
        String qstnCn
) {

    public static VerificationEventQuestionResponse from(LsVrfcEvntQstn entity) {
        return new VerificationEventQuestionResponse(
                entity.getVrfcEvntQstnSn(), entity.getSortSeq(), entity.getQstnCn());
    }
}
