package kr.co.cudo.authoring.aiserver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 장비 응답 — 관리 화면 한 행. [@design API-226] [@design API-227] [@design API-228] [@design API-229]
 *
 * <h3>왜 점검 카운터를 함께 내리는가</h3>
 * <p>연속 실패·연속 성공은 「지금 상태」로는 안 보이는 <b>움직임</b>이다. 아직 이용불가로 내려가지
 * 않았지만 두 번 연속 실패한 장비와, 방금 복귀한 장비를 구분할 수 있어야 운영자가 손을 쓸 수 있다.
 *
 * <p>⚠ 엔티티를 그대로 노출하지 않는다 — 지연 로딩·양방향 참조가 응답 직렬화에 끌려 들어가는
 * 경로를 애초에 만들지 않기 위해서다.
 */
@Schema(description = "AI 장비")
public record AiSrvrResponse(

        @Schema(description = "장비 식별자.", example = "gpu02")
        String srvrId,

        @Schema(description = "사람이 읽는 이름. 지정하지 않았으면 비어 있다.", example = "klid-ai-gpu-02")
        String srvrNm,

        @Schema(description = "호출 기준 주소.", example = "http://10.0.0.12:9300")
        String srvrAddr,

        @Schema(description = "장비 유형 — INFERENCE(추론) 또는 TIMESERIES(외부 시계열 분석).")
        LsAiSrvr.SrvrType srvrTypeCd,

        @Schema(description = "상태 — AVAILABLE · UNAVAILABLE · DRAINING · DISABLED.")
        AiSrvrStatus srvrSttsCd,

        @Schema(description = "최근 상태점검 시각. 한 번도 점검하지 않았으면 비어 있다.")
        LocalDateTime chckDt,

        @Schema(description = "상태점검 연속 실패 횟수.", example = "0")
        int chckFailNocs,

        @Schema(description = "상태점검 연속 성공 횟수.", example = "12")
        int chckScsNocs,

        @Schema(description = "원장 등록 시각.")
        LocalDateTime regDt,

        @Schema(description = "마지막으로 손댄 사람.")
        String mdfrId,

        @Schema(description = "마지막 수정 시각. 등록 뒤 손대지 않았으면 비어 있다.")
        LocalDateTime mdfcnDt,

        @Schema(description = "용도별 부하. 관측된 적 없는 용도는 목록에 없다(0이 아니라 「모름」이다).")
        List<AiSrvrLoadResponse> loads) {

    /**
     * @param loads 그 노드의 용도별 부하. 관측 이력이 없으면 빈 목록을 넘긴다(값을 지어내지 않는다)
     */
    public static AiSrvrResponse of(LsAiSrvr server, List<AiSrvrLoadResponse> loads) {
        return new AiSrvrResponse(
                server.getSrvrId(),
                server.getSrvrNm(),
                server.getSrvrAddr(),
                server.getSrvrTypeCd(),
                server.getSrvrSttsCd(),
                server.getChckDt(),
                server.getChckFailNocs() == null ? 0 : server.getChckFailNocs(),
                server.getChckScsNocs() == null ? 0 : server.getChckScsNocs(),
                server.getRegDt(),
                server.getMdfrId(),
                server.getMdfcnDt(),
                loads == null ? List.of() : loads);
    }
}
