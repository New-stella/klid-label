package kr.co.cudo.authoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 부트스트랩 창구 개폐 조회 응답.
 *
 * <p>담는 사실은 <b>열림/닫힘 하나</b>다. 관리자 인원수는 담지 않는다 — 화면이 필요로 하는 것은
 * 개폐뿐이고, 인원수는 인가와 무관한 사용자에게 줄 정보가 아니다.
 *
 * <p>★ 이 값은 <b>순간의 상태</b>다. 조회와 제출 사이에 다른 사람이 최초 관리자가 되면 창은 그
 * 사이에 닫히고 제출은 거절된다. 그래서 이 조회는 화면을 미리 맞추는 수단일 뿐이며, 호출하는
 * 화면은 제출이 창 닫힘으로 거절되는 갈래(409)를 <b>그대로 유지해야 한다</b>. 이 조회로 그 갈래를
 * 대신할 수 없다.
 *
 * @design API-245
 */
@Schema(description = "관리자 부트스트랩 창구의 개폐 상태")
public record RoleClaimAvailabilityResponse(
        @Schema(description = "창구 개폐 여부. true=열림(시스템에 관리자가 한 명도 없다) / "
                + "false=닫힘(관리자가 한 명 이상 있다). 조회 시점의 순간 상태이며, 제출까지 유지된다는 "
                + "보장이 아니다.", example = "false")
        boolean available
) {
}
