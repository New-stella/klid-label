package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalStreamUrlResponse;
import kr.co.cudo.authoring.portal.service.PortalUploadStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포털 업로드 영상 재생 창구 (PORTAL_USER 전용).
 *
 * <p>내부 채널 영상 스트리밍 창구를 재사용하지 않는다 — 인가 주체가 다르고, 그 창구가 전제하는
 * 「가려진 사본만 서빙한다」는 규칙이 이 경로에는 성립하지 않는다(비식별 단계가 없다).
 *
 * @design API-238
 * @design API-239
 * @design SCREEN-045
 */
@Tag(name = "Portal Upload Stream", description = "포털 업로드 영상 재생 — 본인 자산 원본을 구간 요청으로 서빙.")
@RestController
@RequestMapping("/v1/portal/uploads/{uldSn}")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalUploadStreamController {

    private final PortalUploadStreamService streamService;

    @Operation(summary = "재생 단기 서명 주소 발급",
            description = "재생 요소가 인증 헤더를 싣지 못하는 제약을 우회한다. 발급 시점에 소유자와 자산 종류를 "
                    + "판정해 서명에 묶으므로 최종 접근 가능 주체가 토큰 직접 호출과 같다. "
                    + "유효 시간이 짧아 재생 도중 만료되는 것이 정상 동선이며, 화면은 다시 받아 이어 재생한다.")
    @GetMapping("/stream-url")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalStreamUrlResponse> streamUrl(
            @PathVariable Long uldSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(streamService.issueSignedUrl(uldSn, requireActor(actor)));
    }

    /**
     * 영상 스트리밍 — 구간 요청 지원.
     *
     * <p>인가는 두 갈래다: 포털 토큰을 실은 직접 호출과, 발급 창구를 거친 <b>단기 서명</b> 호출.
     * 후자는 역할 권한을 갖지 않고 전용 서명 권한만 갖는데, 그 권한을 받아들이는 자리는 여기 하나뿐이라
     * 서명 컨텍스트가 다른 포털 창구로 확대되지 않는다.
     */
    @Operation(summary = "영상 스트리밍(구간 요청)",
            description = "본인이 올린 원본 영상을 서빙한다 — 이 경로의 자산에는 비식별 단계가 없다. "
                    + "구간 헤더가 없으면 200 전체, 있으면 206 부분 응답이며 되감기·건너뛰기·배속 재생이 성립한다. "
                    + "추출이 끝났는지를 묻지 않는다 — 마킹이 추출보다 앞서기 때문이다. "
                    + "응답은 캐시하지 않도록 내려보낸다.")
    @GetMapping("/stream")
    @PreAuthorize("hasRole('PORTAL_USER') or hasAuthority('PORTAL_STREAM_SIGNED')")
    public ResponseEntity<ResourceRegion> stream(
            @PathVariable Long uldSn,
            @RequestHeader HttpHeaders headers,
            @AuthenticationPrincipal TokenClaims actor) {
        return streamService.stream(uldSn, requireActor(actor), headers);
    }

    private String requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return actor.sub();
    }
}
