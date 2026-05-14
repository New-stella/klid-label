package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.DeidentReportRequest;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 3 — 비식별 누락 신고 API.
 *
 * <p>경로: {@code POST /v1/labels/{srcSn}/deident-report}
 * <p>권한: REVIEWER, WORKER (WORKER 는 본인 배정 영상만 — LabelAccessGuard).
 */
@Tag(name = "DeidentReport",
        description = "Phase 3 — 라벨링 중 비식별 미흡(얼굴/번호판 미블러 등) 신고. " +
                "신고 즉시 영상이 잠기고(LOCKED_FOR_REDEIDENT) 재비식별 큐에 적재된다.")
@RestController
@RequestMapping("/v1/labels")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class DeidentReportController {

    private final DeidentReportService deidentReportService;

    @Operation(
            summary = "비식별 누락 신고",
            description = "프레임 srcSn 에 해당하는 영상에 대해 비식별 누락 신고를 등록. " +
                    "WORKER 는 본인 배정 영상에 한해서만 가능 (CWE-639 방어). " +
                    "이미 잠금 상태(LOCKED_FOR_REDEIDENT) 인 영상은 409."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신고 등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 (reason 누락/1000자 초과)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (WORKER 인 경우)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임/영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 재비식별 진행 중")
    })
    @PostMapping("/{srcSn}/deident-report")
    @PreAuthorize("hasAnyRole('WORKER', 'REVIEWER')")
    public ResponseEntity<ApiResponse<Long>> report(
            @Parameter(description = "프레임 PK (SRC_SN)", required = true, example = "1") @PathVariable Long srcSn,
            @Valid @RequestBody DeidentReportRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        Long rprtSn = deidentReportService.report(srcSn, request.reason(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(rprtSn));
    }
}
