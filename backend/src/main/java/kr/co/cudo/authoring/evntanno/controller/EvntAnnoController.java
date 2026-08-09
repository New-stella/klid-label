package kr.co.cudo.authoring.evntanno.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationInfo;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.dto.EvntAnnoRejectRequest;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2 — event_annotation(외부 VLM VQA/CoT) 입력·검수 API.
 *
 * <ul>
 *   <li>조회/저장: WORKER(본인 배정)·REVIEWER.</li>
 *   <li>승인/반려: REVIEWER 전용.</li>
 * </ul>
 * 인가는 {@code @PreAuthorize} + 서비스단 {@code verifyRawAccess}(WORKER 본인 배정) 이중 방어.
 * Entity 직접 노출 없이 {@link EventAnnotationInfo} 로 반환한다.
 */
@Tag(name = "EventAnnotation", description = "event_annotation(외부 VLM VQA/CoT) 입력·검수 — WORKER 입력 / REVIEWER 검수. 영상(RAW_SN) 단위.")
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class EvntAnnoController {

    private final EvntAnnoService evntAnnoService;
    private final EvntAnnoReviewService evntAnnoReviewService;

    @Operation(summary = "event_annotation 조회", description = "영상 단위 event_annotation 을 조회한다. WORKER 는 본인 배정 영상만.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "event_annotation 없음")
    })
    @GetMapping("/v1/videos/{rawSn}/event-annotation")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<EventAnnotationInfo> get(
            @Parameter(description = "영상 PK(RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(evntAnnoService.get(rawSn, actor));
    }

    @Operation(summary = "event_annotation 저장/수정", description = "영상 단위 event_annotation payload 를 저장하거나 수정한다(upsert). WORKER 는 본인 배정 영상만.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "payload 검증 실패(event_class 등 필수키 누락)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 누락 신고 구간(재비식별 대기) — event_annotation 저장 차단")
    })
    @PutMapping("/v1/videos/{rawSn}/event-annotation")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<EventAnnotationInfo> upsert(
            @Parameter(description = "영상 PK(RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody EventAnnotationPayload payload,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(evntAnnoService.upsert(rawSn, payload, actor));
    }

    @Operation(summary = "event_annotation 검토 승인", description = "REVIEWER 가 영상의 event_annotation 검토를 승인한다. PENDING/AUTO_GENERATED 에서만.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "event_annotation/검토 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 검토 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 누락 신고 구간(재비식별 대기) — 검토 승인 차단")
    })
    @PostMapping("/v1/videos/{rawSn}/event-annotation/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Void> approve(
            @Parameter(description = "영상 PK(RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        evntAnnoReviewService.approve(rawSn, actor);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "event_annotation 검토 반려", description = "REVIEWER 가 영상의 event_annotation 검토를 반려한다. 사유 필수.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "사유 누락 등 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "event_annotation/검토 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 검토 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 누락 신고 구간(재비식별 대기) — 검토 반려 차단")
    })
    @PostMapping("/v1/videos/{rawSn}/event-annotation/reject")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Void> reject(
            @Parameter(description = "영상 PK(RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody EvntAnnoRejectRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        evntAnnoReviewService.reject(rawSn, req.reason(), actor);
        return ApiResponse.ok(null);
    }
}
