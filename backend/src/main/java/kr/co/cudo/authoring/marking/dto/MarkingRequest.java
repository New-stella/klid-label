package kr.co.cudo.authoring.marking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 마킹 생성 요청 DTO. [design: API-047]
 *
 * <p>이벤트명은 더 이상 요청으로 받지 않는다(API-047 계약 변경). 마킹의 이벤트명은
 * 영상의 이벤트 유형({@code LS_DATA_RAW.EVNT_TYPE_CD})에서 서버가 자동 소싱한다.
 *
 * @param mode           마킹 모드 — "AUTO" 또는 "MANUAL" (필수)
 * @param intervalFrames 자동 모드 시 프레임 간격(프레임 수) — AUTO 모드일 때 필수, 1 이상
 * @param marks          수동 모드 시 marks 배열 — MANUAL 모드일 때 필수
 * @param vrfcEvntQstnSn 작업자가 고른 검증 이벤트 질문의 일련번호 — <b>선택</b>. 없어도 되고, 그 영상의
 *                       검증 이벤트 유형에 속하지 않는 값이어도 <b>400 으로 거부하지 않는다</b>
 * @see kr.co.cudo.authoring.sysconfig.service.VerificationEventQuestionResolver
 */
public record MarkingRequest(
        @NotBlank(message = "mode 는 필수입니다.")
        String mode,

        Integer intervalFrames,

        /*
         * C-ISSUE-01 — @Valid 누락으로 MarkItem 의 제약(@NotNull/@Min/@Pattern)이 <b>전혀 발화하지
         * 않던</b> 결함을 수정한다(중첩 검증은 @Valid 가 있어야 전파된다). @Size 는 과대 요청 DoS 방어
         * (CWE-770) — 30fps·10분 영상의 전 프레임 마킹(18,000)을 넉넉히 수용하는 상한.
         */
        @Valid
        @Size(max = 20000, message = "한 번에 처리 가능한 마킹 수 초과 (최대 20000)")
        List<MarkItem> marks,

        /*
         * 검증 이벤트 질문 선택값 — 검증 어노테이션을 달지 않는 것이 의도다. 확정 정책상 이 값은
         * 「화면이 보낸 참고값」이라 서버가 그 영상의 검증 이벤트 유형 소속을 다시 판정해 어긋나면
         * 첫 번째 질문으로 <b>교정</b>한다(400 아님). 입구에서 거르면 그 교정 계약이 깨진다.
         */
        Long vrfcEvntQstnSn
) {

    /**
     * 질문 선택값 없는 기존 3-인자 형태 — 하위호환 편의 생성자.
     *
     * <p>레코드의 정규 생성자(4-인자)는 그대로이며 JSON 역직렬화도 그쪽을 쓴다. 이 생성자는
     * 질문 선택을 하지 않는 호출자(미선택 = 그 유형의 첫 번째 질문으로 폴백)를 위한 것이다.
     */
    public MarkingRequest(String mode, Integer intervalFrames, List<MarkItem> marks) {
        this(mode, intervalFrames, marks, null);
    }
}
