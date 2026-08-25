package kr.co.cudo.authoring.sysconfig.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntQstn;

import java.util.List;

/**
 * 검증 이벤트 유형의 질문 목록 <b>전체 교체</b> 요청. [design: API-220]
 *
 * <p><b>받은 배열의 순서가 곧 정렬순서</b>이며 첫 번째가 그 유형의 기본 질문이다. 추가·수정·삭제·순서
 * 변경이 이 하나로 처리된다 — 항목 단위 조작 통로를 따로 두면 여러 요청 사이의 <b>중간 상태에서
 * 「첫 번째」가 흔들려</b> 그 사이에 조달되는 질문이 운영자가 의도하지 않은 문구가 된다.
 *
 * <p><b>빈 배열은 그 유형의 질문을 없앤다</b>(정상). 그 유형은 어노테이션 질문 칸을 비운 채로 둔다 —
 * 값을 지어내지 않는다.
 *
 * <p><b>Mass Assignment 방어</b>(CWE-915): 엔티티를 직접 바인딩하지 않고 이 DTO 로 <b>문구만</b>
 * 받는다. 일련번호({@code vrfcEvntQstnSn})·정렬순서·감사 컬럼은 요청으로 받지 않으며(정렬순서는 배열
 * 순서에서 서버가 매긴다), 알 수 없는 JSON 필드는 무시한다.
 *
 * <p><b>검증의 단일 진실원은 {@link LsVrfcEvntQstn} 이다</b> — 상한·허용 문자 상수를 여기에 리터럴로
 * 복제하지 말 것. 서비스 2차 방어선도 같은 상수·같은 함수를 본다.
 *
 * @param questions 교체할 질문 목록 전체. {@code null} 은 거부하고 빈 배열은 허용한다 —
 *                  "보내지 않았다"와 "비우겠다"는 다른 의도이며, 전자를 후자로 읽으면 실수로 전량 삭제된다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationEventQuestionsUpdateRequest(

        @NotNull(message = "질문 목록은 필수입니다.")
        @Valid
        List<Item> questions
) {

    /**
     * 질문 1건.
     *
     * <p><b>왜 이렇게 좁게 받는가</b>: 이 문구는 <b>외부 사업자 요청 바디와 로그에 그대로 실린다</b>.
     * 개행·제어문자가 섞이면 로그 한 줄에 여러 줄이 들어가 기록을 위조할 수 있고(CWE-117) 사업자 쪽
     * 파싱도 깨진다. 길이 상한은 저장 컬럼 폭({@code QSTN_CN VARCHAR(4000)})과 같다 — 입구에서 400 을
     * 주지 않으면 INSERT 시점 DB 오류(500)가 된다.
     *
     * @param qstnCn 질문 문구. 공백만일 수 없고, 개행·제어문자를 넣을 수 없으며, 4000자 이하다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(

            @NotBlank(message = "질문 문구는 비어 있을 수 없습니다.")
            @Size(max = LsVrfcEvntQstn.QSTN_CN_MAX_LENGTH,
                    message = "질문 문구는 4000자 이하여야 합니다.")
            @Pattern(regexp = LsVrfcEvntQstn.QSTN_CN_ALLOWED_REGEX,
                    message = "질문 문구에는 개행·제어문자를 넣을 수 없습니다.")
            String qstnCn
    ) {
    }

    /** 목록 — {@code null} 방어(Bean Validation 이 이미 막지만 서비스가 다시 부를 수 있다). */
    public List<Item> questionsOrEmpty() {
        return questions == null ? List.of() : questions;
    }
}
