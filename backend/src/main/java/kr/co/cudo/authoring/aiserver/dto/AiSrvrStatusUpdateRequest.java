package kr.co.cudo.authoring.aiserver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;

/**
 * AI 장비 상태 전이 요청. [@design API-229]
 *
 * <p>알 수 없는 상태값은 역직렬화 단계에서 걸러 400 이 된다 — 서비스까지 내려보내 판정하지 않는다.
 */
@Schema(description = "AI 장비 상태 전이 요청")
public record AiSrvrStatusUpdateRequest(

        @Schema(description = "전이 목표 상태 — AVAILABLE(가용) · UNAVAILABLE(이용불가) · DRAINING(정비중) · DISABLED(비활성).",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        AiSrvrStatus srvrSttsCd) {
}
