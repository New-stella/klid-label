package kr.co.cudo.authoring.aiserver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg;

import java.time.LocalDateTime;

/**
 * 노드 x 용도별 부하 관측값 응답. [@design API-226]
 *
 * <p>두 용도를 하나로 합쳐 보이지 않는다 — 장비 안에서 실행이 갈려 있어, 합치면 일괄 처리가 밀린
 * 장비를 화면 요청이 <b>피할 이유가 없는데도 피하게</b> 된다. 화면도 같은 축으로 읽어야 판단이 맞다.
 *
 * <p>⚠ 관측된 적 없는 용도는 <b>목록에 없다</b>. 「부하 0」이 아니라 「모름」이며, 0으로 채워 내리면
 * 화면이 가장 한가한 장비로 오해한다.
 */
@Schema(description = "용도별 부하 관측값")
public record AiSrvrLoadResponse(

        @Schema(description = "용도 — BATCH(일괄 처리) 또는 INTERACTIVE(화면에서 쓰는 요청).")
        AiSrvrUsageType usgTypeCd,

        @Schema(description = "그 용도가 지금 처리 중인 건수.", example = "1")
        int prcsNocs,

        @Schema(description = "그 용도의 큐 길이 — 상대가 잠그지 않고 세는 근사값이다.", example = "2")
        int wtngNocs,

        @Schema(description = "실효 부하 = 처리중 + 대기. 노드를 고를 때 실제로 보는 값이다.", example = "3")
        int effectiveLoad,

        @Schema(description = "우리가 관측한 시각(상대 장비의 시계가 아니다).")
        LocalDateTime chckDt) {

    /**
     * 시계열 축의 부하 — <b>우리 원장의 미결 위탁 수</b>. [@design API-226]
     *
     * <p>★이 축은 폴러가 채우는 용도별 관측표에서 오지 않는다. 시계열은 위탁 후 콜백이라 상대의
     * 큐를 볼 수 없어, 우리가 <b>결과를 기다리고 있는 건수</b>를 그 장비의 부하로 본다. 노드를 고르는
     * 쪽도 같은 값을 보므로 <b>화면과 라우팅이 같은 수치를 말한다</b> — 다른 원천을 쓰면 화면이
     * 라우팅과 다른 것을 보여 주고, 그 어긋남은 아무 오류도 내지 않아 드러나지 않는다.
     *
     * <p>용도는 {@code BATCH} 로 적는다. 시계열 위탁은 일괄 처리 경로에서만 나가고 화면에서 직접
     * 부르는 경로가 없다. 처리중/대기를 나누지 않는 것도 그 성질 때문이다 — 우리가 아는 것은
     * <b>아직 안 돌아왔다</b> 하나뿐이라, 그것을 대기로 적고 처리중은 0 으로 둔다.
     *
     * <p>관측 시각을 비워 두는 것은 <b>상대를 찌른 적이 없다</b>는 사실 그대로다. 지금 시각을 적으면
     * 방금 확인한 것처럼 보인다.
     */
    public static AiSrvrLoadResponse outstandingSubmits(int outstanding) {
        return new AiSrvrLoadResponse(AiSrvrUsageType.BATCH, 0, outstanding, outstanding, null);
    }

    public static AiSrvrLoadResponse from(LsAiSrvrUsg usage) {
        return new AiSrvrLoadResponse(
                usage.getUsgTypeCd(),
                usage.getPrcsNocs() == null ? 0 : usage.getPrcsNocs(),
                usage.getWtngNocs() == null ? 0 : usage.getWtngNocs(),
                usage.effectiveLoad(),
                usage.getChckDt());
    }
}
