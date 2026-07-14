package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.TrackMergeRequest;
import kr.co.cudo.authoring.label.dto.TrackMergeResponse;
import kr.co.cudo.authoring.label.service.TrackMergeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 4 — 트랙 병합 API. 두 트랙(fromTrackId → toTrackId)을 하나로 합치고 재보간한다.
 *
 * <p><b>포털 차단(ADR-013, 이중 방벽)</b>:
 * <ul>
 *   <li>역할: {@code @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")} 로 PORTAL_USER 차단(수직 권한).</li>
 *   <li>채널: 경로 {@code /v1/videos/**} 가 SecurityConfig 의 {@code /v1/**} → CHANNEL_INTERNAL 매처에
 *       걸려 PORTAL 채널 토큰 물리 차단(AutolabelController 와 동일 정책).</li>
 * </ul>
 *
 * <p>IDOR(CWE-639)는 서비스 진입부 {@code LabelAccessGuard.verifyRawAccess} 로 방어한다.
 */
@Tag(name = "TrackMerge", description = "트랙 병합 — REVIEWER/WORKER. 본인 배정 영상 검증(IDOR)·배타 락·재보간. 포털 미제공(ADR-013).")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class TrackMergeController {

    private final TrackMergeService trackMergeService;

    @Operation(
            summary = "트랙 병합",
            description = "fromTrackId 의 원 키프레임(보간 산출물 제외)을 toTrackId 로 이관한 뒤 영상 전체를 "
                    + "재보간한다. 겹치는 프레임이 있으면 409. 수동/SEGMENT/SKELETON 트랙은 "
                    + "interpolationApplied=false 로 응답."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "from==to / 입력 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / 포털 채널 (CWE-639)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "원본 트랙 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "겹치는 프레임 / 편집·병합 중 잠금")
    })
    @PostMapping("/{rawSn}/tracks/merge")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<TrackMergeResponse> mergeTracks(
            @Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody TrackMergeRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(
                trackMergeService.merge(rawSn, req.fromTrackId(), req.toTrackId(), actor));
    }
}
