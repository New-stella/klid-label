package kr.co.cudo.authoring.aiserver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.service.AiSrvrIdPolicy;

/**
 * AI 장비 등록 요청. [@design API-227]
 *
 * <h3>받는 것만 적는다</h3>
 * <p>상태·점검 카운터·등록일시는 <b>요청으로 받지 않는다</b>(CWE-915). 상태는 언제나 가용으로
 * 시작한다 — 등록 시점에는 상태점검을 한 번도 하지 않아 우리가 아는 것이 없기 때문이다.
 *
 * <h3>식별자 정규식을 여기 다시 쓰지 않는다</h3>
 * <p>같은 규칙이 DB 체크 제약·기동 가드·이 DTO 세 곳에 걸리지만 <b>문자열은 하나</b>다
 * ({@link AiSrvrIdPolicy#SRVR_ID_REGEX}). 복제하면 그 사본이 두 번째 진실원이 되어 한쪽만
 * 통과하는 값이 조용히 생긴다.
 */
@Schema(description = "AI 장비 등록 요청")
public record AiSrvrCreateRequest(

        @Schema(description = "장비 식별자 — 소문자·숫자만 20자 이내. 서킷브레이커 이름과 메트릭 라벨로 조립되는 기계용 값이다.",
                example = "gpu02", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Pattern(regexp = AiSrvrIdPolicy.SRVR_ID_REGEX,
                message = "장비 식별자는 소문자와 숫자만 20자 이내로 사용할 수 있습니다.")
        String srvrId,

        @Schema(description = "사람이 읽는 이름(실제 장비 호스트명 등). 형식 제약이 없는 표시 축이며 모르면 비워 둔다.",
                example = "klid-ai-gpu-02")
        @Size(max = 100)
        String srvrNm,

        @Schema(description = "호출 기준 주소. 평문 http 와 사설 대역은 통과가 정상이다.",
                example = "http://10.0.0.12:9300", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 200)
        String srvrAddr,

        @Schema(description = "장비 유형 — INFERENCE(추론) 또는 TIMESERIES(외부 시계열 분석).",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        LsAiSrvr.SrvrType srvrTypeCd) {
}
