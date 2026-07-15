package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.portal.service.PortalSam2Service;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 9 — 포털 채널 전용 SAM2 인터랙티브 추론 API (세그멘테이션 + 자동추적).
 *
 * <p>엔드포인트 (모두 PORTAL_USER 만):
 * <ul>
 *   <li>{@code POST /v1/portal/frames/{srcSn}/sam2-track}   : SAM2 자동추적 — 좌표만 반환(persist 없음)</li>
 *   <li>{@code POST /v1/portal/frames/{srcSn}/sam2-segment} : SAM2 클릭/박스 분할 — 좌표만 반환(persist 없음)</li>
 * </ul>
 *
 * <p><b>내부 경로와 분리 (CRITICAL)</b>: 내부 {@code /v1/frames/**} 는 SAM2 추적 결과를 서버에서
 * 즉시 {@code LS_DATA_LBL} 에 저장하므로 포털에 개방하지 않는다(데이터마트 오염 차단). 본 포털 전용 경로는
 * {@link PortalSam2Service} 를 통해 <b>DB 저장 없이 좌표만</b> 반환한다. 포털 사용자의 저장은
 * {@code POST /v1/portal/user-labels}(LS_PORTAL_USER_LABEL 단방향)로만 이뤄진다.
 *
 * <p><b>채널 격리</b>: SecurityConfig {@code /v1/portal/**} → CHANNEL_PORTAL + PORTAL_USER (내부 토큰 403).
 */
@Tag(name = "Portal SAM2", description = "포털 채널 SAM2 인터랙티브 추론 — PORTAL_USER 전용. persist 없이 좌표만 반환.")
@RestController
@RequestMapping("/v1/portal/frames")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalSam2Controller {

    private final PortalSam2Service portalSam2Service;

    @Operation(summary = "포털 SAM2 자동추적 (Phase 9)",
            description = "후속 프레임 폴리곤을 좌표로만 반환 — persist 없음(내부 LS_DATA_LBL 불변). "
                    + "APPROVED(데이터마트 노출) 영상만 허용(IDOR 재검증). path/body srcSn 불일치 400.")
    @PostMapping("/{srcSn}/sam2-track")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<Sam2TrackResponseDto> sam2Track(
            @PathVariable Long srcSn,
            @Valid @RequestBody Sam2TrackRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        requireMatchingSrcSn(srcSn, req.srcSn());
        return ApiResponse.ok(portalSam2Service.track(req, actor));
    }

    @Operation(summary = "포털 SAM2 클릭/박스 분할 (Phase 9)",
            description = "폴리곤 + 신뢰도를 좌표로만 반환 — persist 없음. points/box 정확히 하나(400). "
                    + "APPROVED(데이터마트 노출) 영상만 허용(IDOR 재검증). path/body srcSn 불일치 400.")
    @PostMapping("/{srcSn}/sam2-segment")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<Sam2SegmentResponse> sam2Segment(
            @PathVariable Long srcSn,
            @Valid @RequestBody Sam2SegmentRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        requireMatchingSrcSn(srcSn, req.srcSn());
        return ApiResponse.ok(portalSam2Service.segment(req, actor));
    }

    /** path 의 srcSn 과 body 의 srcSn 불일치 시 거부 (CWE-345). */
    private void requireMatchingSrcSn(Long pathSrcSn, Long bodySrcSn) {
        if (pathSrcSn == null || !pathSrcSn.equals(bodySrcSn)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "path 의 srcSn 과 body 의 srcSn 이 다릅니다.");
        }
    }
}
