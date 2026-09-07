package kr.co.cudo.authoring.marking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.video.entity.LsDataIngest;

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
 * @param vrfcEvntTypeCd 작업자가 고른 검증 이벤트 유형 코드 — <b>선택</b>. 관제 인입이 유형을 보내지
 *                       않은 영상에서만 화면이 이 선택을 노출한다
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
        Long vrfcEvntQstnSn,

        /*
         * 검증 이벤트 유형 선택값 — 형식 어노테이션(@Pattern/@Size)을 <b>리터럴로 복제하지 않는다</b>.
         * 판정(문자 집합 + 컬럼 폭)의 단일 진실원은 LsDataIngest 이며, 아래 @AssertTrue 가 그 함수를
         * 부른다. 리터럴을 여기 베끼면 인입 원장의 규칙과 갈라진다.
         */
        String vrfcEvntTypeCd
) {

    /**
     * 유형 선택값 없는 기존 4-인자 형태 — 하위호환 편의 생성자.
     *
     * <p>레코드의 정규 생성자(5-인자)는 그대로이며 JSON 역직렬화도 그쪽을 쓴다.
     */
    public MarkingRequest(String mode, Integer intervalFrames, List<MarkItem> marks, Long vrfcEvntQstnSn) {
        this(mode, intervalFrames, marks, vrfcEvntQstnSn, null);
    }

    /**
     * 질문·유형 선택값 없는 기존 3-인자 형태 — 하위호환 편의 생성자.
     *
     * <p>질문 선택을 하지 않는 호출자(미선택 = 그 유형의 첫 번째 질문으로 폴백)를 위한 것이다.
     */
    public MarkingRequest(String mode, Integer intervalFrames, List<MarkItem> marks) {
        this(mode, intervalFrames, marks, null, null);
    }

    /**
     * 검증 이벤트 유형 — <b>정규화된 값</b> 또는 미지정({@code null}). [design: API-047]
     *
     * <p>정규화(trim + 소문자)는 {@link LsDataIngest#normalizeVrfcEvntType} <b>한 곳</b>이 소유한다 —
     * 이 값은 관제 인입이 싣는 값과 <b>같은 값 공간</b>이므로 규칙을 복제하면 인입이 실어 보낸 표기가
     * 우리 통로에서만 거부된다. 검증도 저장도 이 결과에 대해 하므로 표기 변형으로 검증을 우회할 수 없다.
     *
     * <p>빈 문자열이 {@code null} 인 것은 계약이다 — 그대로 저장하면 소비 시점(위탁 조립)이 「미지정」과
     * 구분하지 못한다.
     */
    public String vrfcEvntTypeCdOrNull() {
        return LsDataIngest.normalizeVrfcEvntType(vrfcEvntTypeCd);
    }

    /**
     * 검증 이벤트 유형 <b>형식</b> 검증 — 판정 단일 진실원은
     * {@link LsDataIngest#isVrfcEvntTypeFormatValid(String)} 다.
     *
     * <h3>목록으로 막지 않는다 — 형식만 본다</h3>
     * <p>우리가 벤더 목록의 사본을 들면 벤더가 값을 넓힐 때 <b>정상 값을 우리가 먼저 막는다</b>
     * (이 저장소가 {@code event_type} allowlist 를 폐기한 것과 같은 축). 그래도 형식은 막는데 이유가 둘이다 —
     * ①컬럼이 {@code VARCHAR(20)} 이라 입구에서 400 을 주지 않으면 INSERT 시점 DB 오류(500)가 되고
     * ②이 값은 <b>외부 벤더 요청 본문과 로그에 그대로 실려</b> 공백·개행·제어문자가 섞이면 로그
     * 인젝션(CWE-117)·벤더측 파싱 오류가 된다.
     *
     * <p><b>미지정은 통과</b>한다 — 요청 스키마에서는 선택 필드다. 「화면에서 필수」와 「스키마에서
     * 필수」는 다른 축이며, 여기서 필수로 올리면 관제 값이 있어 이 필드를 아예 보내지 않는 영상의
     * 마킹이 <b>전량 거부</b>된다.
     *
     * <p>위반 메시지에 <b>입력 원문을 담지 않는다</b> — 응답·로그로 흘러가므로 원문을 실으면 로그
     * 인젝션·정보 노출이 된다(CWE-117/209).
     */
    @AssertTrue(message = "검증이벤트유형은 영문 소문자·숫자·밑줄 20자 이내여야 합니다.")
    public boolean isVrfcEvntTypeAllowed() {
        return LsDataIngest.isVrfcEvntTypeFormatValid(vrfcEvntTypeCdOrNull());
    }
}
