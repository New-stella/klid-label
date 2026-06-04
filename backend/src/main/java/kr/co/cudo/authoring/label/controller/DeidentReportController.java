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
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2 (R1 v1.14) — 비식별 누락 신고 API.
 *
 * <p>경로:
 * <ul>
 *   <li>{@code POST /v1/labels/{srcSn}/deident-report} — 신고 등록.</li>
 *   <li>{@code POST /v1/deident-reports/{rprtSn}/resolve} — 외부 솔루션 수동 비식별화 완료 후 신고 해소.</li>
 * </ul>
 * <p>권한: REVIEWER, WORKER (WORKER 는 본인 배정 영상만 — LabelAccessGuard).
 */
@Tag(name = "DeidentReport",
        description = "Phase 2(R1 v1.14) — 라벨링 중 비식별 미흡(얼굴/번호판 미블러 등) 신고. " +
                "신고 즉시 영상이 잠기고(LOCKED_FOR_REDEIDENT) 현재 작업(영상 전체 라벨)을 " +
                "복원 가능 스냅샷 기록 후 삭제한다. 재처리는 외부 솔루션 수동 비식별화 흐름으로 진행되며, " +
                "완료 시 resolve 로 OPEN→RESOLVED 전이 + 작업락을 해제한다.")
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class DeidentReportController {

    private final DeidentReportService deidentReportService;

    @Operation(
            summary = "비식별 누락 신고",
            description = "프레임 srcSn 에 해당하는 영상에 대해 비식별 누락 신고를 등록. " +
                    "신고 시 해당 영상 전체 라벨을 복원 가능 스냅샷으로 기록 후 삭제하고 영상을 잠근다. " +
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
    @PostMapping("/v1/labels/{srcSn}/deident-report")
    @PreAuthorize("hasAnyRole('WORKER', 'REVIEWER')")
    public ResponseEntity<ApiResponse<Long>> report(
            @Parameter(description = "프레임 PK (SRC_SN)", required = true, example = "1") @PathVariable Long srcSn,
            @Valid @RequestBody DeidentReportRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        Long rprtSn = deidentReportService.report(srcSn, request.reason(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(rprtSn));
    }

    @Operation(
            summary = "비식별 신고 수동 해소",
            description = "외부 솔루션으로 수동 비식별화를 완료한 뒤 호출. 신고를 OPEN→RESOLVED 로 전이하고 " +
                    "작업락을 해제한다. WORKER 는 본인 배정 영상만 가능 (CWE-639 방어). " +
                    "이미 처리(RESOLVED/DISMISSED)된 신고는 409."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해소 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (WORKER 인 경우)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "신고 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 처리된 신고 (OPEN 아님)")
    })
    @PostMapping("/v1/deident-reports/{rprtSn}/resolve")
    @PreAuthorize("hasAnyRole('WORKER', 'REVIEWER')")
    public ResponseEntity<ApiResponse<Void>> resolve(
            @Parameter(description = "신고 PK (DEIDENT_REPORT_SN)", required = true, example = "1") @PathVariable Long rprtSn,
            @AuthenticationPrincipal TokenClaims actor) {
        deidentReportService.resolveManually(rprtSn, actor);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
