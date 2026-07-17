package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.TrackDeleteResponse;
import kr.co.cudo.authoring.label.dto.TrackSplitRequest;
import kr.co.cudo.authoring.label.dto.TrackSplitResponse;
import kr.co.cudo.authoring.label.service.TrackEditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 3(트랙 관리 확장) — 트랙 삭제(R4) / 트랙 split(R5) API.
 *
 * <p><b>포털 차단(ADR-013, 이중 방벽)</b>: 역할 {@code @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")}
 * + 경로 {@code /v1/videos/**} 가 SecurityConfig 의 CHANNEL_INTERNAL 매처에 걸려 PORTAL 채널 토큰 물리
 * 차단({@code TrackMergeController} 와 동일 정책). IDOR(CWE-639)는 서비스 진입부 {@code verifyRawAccess} 방어.
 * 입력 검증(CWE-20): {@code trackId} 길이 상한(VARCHAR(30)) + {@code fromFrameNo}/{@code atFrameNo} 0 이상.
 */
@Tag(name = "TrackEdit", description = "트랙 삭제/분할 — REVIEWER/WORKER. 본인 배정 영상 검증(IDOR)·배타 락·재보간. 포털 미제공(ADR-013).")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class TrackEditController {

    private final TrackEditService trackEditService;

    @Operation(
            summary = "트랙 삭제(지정 프레임 이후)",
            description = "fromFrameNo 이후(포함) 프레임의 해당 트랙 라벨을 전부 삭제한다. 이전 프레임·타 트랙은 "
                    + "불변. 자식(AI_INFO)→부모(LBL) 순서 삭제로 FK 고아 방지 후 남은 키프레임을 재보간한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패(음수 프레임/길이 초과)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / 포털 채널 (CWE-639)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "트랙 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "편집·병합 중 잠금")
    })
    @DeleteMapping("/{rawSn}/tracks/{trackId}")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<TrackDeleteResponse> deleteTrack(
            @Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @Parameter(description = "삭제 대상 트랙 ID", required = true)
            @PathVariable @Size(max = 30, message = "trackId 는 30자 이하여야 합니다.") String trackId,
            @Parameter(description = "삭제 시작 프레임 번호(이상 삭제)", required = true, example = "10")
            @RequestParam @Min(value = 0, message = "fromFrameNo 는 0 이상이어야 합니다.") int fromFrameNo,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(trackEditService.deleteTrackFrom(rawSn, trackId, fromFrameNo, actor));
    }

    @Operation(
            summary = "트랙 분할(split)",
            description = "atFrameNo 이후(포함) 프레임의 트랙 키프레임을 새 트랙 ID(영상 내 max 정수 트랙ID+1)로 "
                    + "재지정한다. 좌표 불변. 분할 후 두 트랙을 각각 재보간한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패(음수 프레임/길이 초과)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / 포털 채널 (CWE-639)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "트랙 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "편집·병합 중 잠금")
    })
    @PostMapping("/{rawSn}/tracks/{trackId}/split")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<TrackSplitResponse> splitTrack(
            @Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @Parameter(description = "분할 대상 트랙 ID", required = true)
            @PathVariable @Size(max = 30, message = "trackId 는 30자 이하여야 합니다.") String trackId,
            @Valid @RequestBody TrackSplitRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(trackEditService.splitTrack(rawSn, trackId, req.atFrameNo(), actor));
    }
}
