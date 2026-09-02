package kr.co.cudo.authoring.portal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 포털 채널 증강 요청 본문 — {@code POST /v1/portal/uploads/{uldSn}/augments}.
 *
 * <h3>★ 생성 조건은 <b>불투명한 덩어리</b>다 — 항목·값역을 여기서 정하지 않는다</h3>
 * <p>{@code API-231} 이 <i>"조건을 이루는 개별 항목과 그 허용 값역은 이 산출물에서 정하지 않는다.
 * 내부 채널 증강 요청 창구의 조건 항목과 값역을 그대로 옮겨 오지 말 것 — 두 창구는 인가 주체와 대상
 * 계보가 달라 같은 규칙이 성립한다는 근거가 없다"</i> 고 <b>명시적으로</b> 적었다. 그래서 이 자리에
 * 관제 채널의 다섯 항목 enum({@code AugmentRequestRequest.Mtdt})을 복제하지 않는다 — 복제하면
 * 설계가 「정하지 않았다」고 적어 둔 값역을 구현이 지어내는 것이 된다.
 *
 * <p>대신 <b>받은 그대로 보관하고 되돌려준다</b>. 창구가 확정적으로 말하는 것은 그 두 가지뿐이며,
 * 같은 영상에 여러 요청이 공존할 때 결과를 구분하는 축이 이 값이다.
 *
 * <p>대상 영상은 경로가 정하므로 본문에 다시 싣지 않는다.
 *
 * @param generationCondition 증강 생성 조건. 비어 있지 않은 객체여야 한다
 * @design API-231
 */
public record PortalAugmentRequest(
        @Schema(description = "증강 생성 조건. 접수 시 그대로 보관되어 요청 현황 목록·단건 조회에서 "
                + "같은 값으로 되돌아온다. 조건을 이루는 개별 항목과 허용 값역은 이 창구가 정하지 않는다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "증강 생성 조건(generationCondition)은 필수입니다.")
        Map<String, Object> generationCondition
) {
}
