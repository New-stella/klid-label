package kr.co.cudo.authoring.eventtype.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 이벤트유형 관리 수정 요청 (PATCH — <b>부분 수정</b>).
 *
 * <p>두 필드 모두 <b>선택</b>이며 null 은 "바꾸지 않음"을 뜻한다. 둘 다 null 이면 400 이다
 * (아무것도 안 바꾸는 요청은 오작동 신호이므로 조용히 200 을 주지 않는다).
 *
 * <p><b>Mass Assignment 방어</b>(CWE-915): 엔티티를 직접 바인딩하지 않고 이 DTO 로 <b>수정 허용
 * 필드만</b> 받는다. {@code evntTypeCd}(PK)·{@code evntNm}(<b>관제 수신 칸</b>)·{@code evntClsfCd}·
 * {@code evntCtgryCd}·{@code regDt} 는 요청으로 받지 않으며, 알 수 없는 JSON 필드는 무시한다.
 * 관제 칸을 화면에서 쓰게 하면 다음 인입이 덮어써 "저장했는데 사라지는" 동작이 된다.
 *
 * @param optrIndctNm 운영자 표시명. <b>빈 문자열이면 해제</b>(관제 수신명으로 복귀 — 되돌리기 경로).
 *                    상한은 컬럼 길이(명V200)와 동일한 200자
 * @param clctYn      수집여부 — {@code Y} 또는 {@code N} 만 허용
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventTypeUpdateRequest(

        @Size(max = 200, message = "표시명은 200자 이하여야 합니다.")
        String optrIndctNm,

        @Pattern(regexp = "^[YN]$", message = "수집여부는 Y 또는 N 이어야 합니다.")
        String clctYn
) {

    /** 수정할 항목이 하나라도 있는가 — 서비스가 400 판정에 쓴다. */
    public boolean isEmpty() {
        return optrIndctNm == null && clctYn == null;
    }
}
