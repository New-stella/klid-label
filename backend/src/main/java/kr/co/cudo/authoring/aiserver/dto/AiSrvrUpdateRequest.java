package kr.co.cudo.authoring.aiserver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * AI 장비 이름·주소 수정 요청 — <b>부분 수정</b>. [@design API-228]
 *
 * <p>보내지 않은 항목은 <b>그대로 둔다</b>. 주소는 {@code NOT NULL} 이라 「비운다」가 성립하지 않고,
 * 이름도 이 창구로는 지울 수 없다 — 빈 문자열로 지우는 우회를 열면 표시 축에서 「빈 이름」과
 * 「모름」이 구분되지 않는다.
 *
 * <p>⚠ 유형과 상태는 여기서 받지 않는다. 유형이 바뀌면 그 장비를 고르던 축이 통째로 바뀌고
 * 이미 배정된 영상의 근거가 사라진다. 상태는 전용 창구가 따로 있다(전이 규칙과 마지막 가용 장비
 * 보호가 걸려야 하기 때문이다).
 */
@Schema(description = "AI 장비 이름·주소 수정 요청 (보내지 않은 항목은 유지)")
public record AiSrvrUpdateRequest(

        @Schema(description = "사람이 읽는 이름. 생략하면 유지된다.", example = "klid-ai-gpu-02")
        @Size(max = 100)
        String srvrNm,

        @Schema(description = "호출 기준 주소. 생략하면 유지된다.", example = "http://10.0.0.12:9300")
        @Size(max = 200)
        String srvrAddr) {
}
