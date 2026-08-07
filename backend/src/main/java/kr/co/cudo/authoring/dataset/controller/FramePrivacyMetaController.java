package kr.co.cudo.authoring.dataset.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyBulkRequest;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyMetaResponse;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyMetaUpdateRequest;
import kr.co.cudo.authoring.dataset.service.FramePrivacyMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 3 — 프레임 개인정보 메타(익명/가명/개인정보 포함여부) 저장·조회 API.
 *
 * <p>인가는 서비스의 {@link FramePrivacyMetaService}(LabelAccessGuard 재사용)에서 처리한다:
 * REVIEWER 통과 / WORKER 본인 배정 프레임만 / 그 외 403(CWE-639 IDOR 방어) / 미존재 404. 포털 채널 토큰은
 * {@code SecurityConfig} 의 내부/포털 채널 격리로 차단된다.
 *
 * <p><b>★ export 반영 범위</b> (2026-08-03 DEV_FIX 2차 정정 — 구 "anonymity 는 export 를 덮지 않는다"는
 * 정책 반전 후 <b>거짓</b>): 저장한 3필드는 {@code DEIDENTIFIED} 산출물의 {@code image} 블록에
 * <b>모두 수동값 우선</b>으로 실린다(미입력 시 기본상수 Y/N/N). {@code ORIGINAL} 산출물은 3필드 모두
 * null 이다. 판정 단일 원천은 {@code ExportPrivacyPolicy} 다.
 *
 * <p><b>비식별 신고 게이트(412)</b>: 저장(PUT, 단건·벌크)은 신고 구간에서 차단된다 — 영상 축 PUT 과
 * 동일 정책이며 판정은 {@code LabelAccessGuard#requireNotUnderDeidentReport} 단일 원천을 쓴다
 * (컨트롤러가 판정을 복제 보유하지 않는다). 조회(GET)는 차단하지 않는다.
 */
@Tag(name = "FramePrivacyMeta",
        description = "프레임 개인정보 메타(익명/가명/개인정보 포함여부) 저장·조회 — REVIEWER/WORKER. "
                + "조회는 수동값 우선 파생 프리필, 저장은 전체 교체(PUT). 본인 배정 검증(IDOR 방어).")
@RestController
@RequestMapping("/v1/frames")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class FramePrivacyMetaController {

    private final FramePrivacyMetaService framePrivacyMetaService;

    @Operation(summary = "프레임 개인정보 메타 조회",
            description = "프레임의 익명/가명/개인정보 포함여부를 조회한다. 수동 저장값이 있으면 그 값(MANUAL), 없으면 비식별 "
                    + "산출물 기본상수(익명 Y / 가명 N / 개인정보포함 N)를 프리필(DERIVED)로 반환한다. "
                    + "WORKER 는 본인 배정 프레임만. "
                    + "<b>주의</b> — anonymitySource/pseudonymitySource/privacyIncludedSource(MANUAL/DERIVED)가 "
                    + "해당 값이 사람이 저장한 판정인지 시스템 기본상수인지를 구분하는 근거다. DERIVED 값을 그대로 "
                    + "PUT 으로 되돌려보내면 수동값으로 승격되므로, 프리필 상태로 유지할 필드는 PUT 시 null 로 전송한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (CWE-639 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    @GetMapping("/{srcSn}/privacy-meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<FramePrivacyMetaResponse> get(
            @Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(framePrivacyMetaService.get(srcSn, actor));
    }

    @Operation(summary = "프레임 개인정보 메타 저장/수정(전체 교체)",
            description = "익명/가명/개인정보 포함여부(각 Y/N)를 저장/수정한다. 전체 교체 — 생략(null)한 필드는 수동값이 "
                    + "삭제되어 파생값으로 폴백한다. path srcSn 과 body srcSn 불일치 시 400(CWE-345). "
                    + "허용값 외 문자열 시 400. 검수 완료(APPROVED) 후 수정 시 TASK_MODIFIED 통지. "
                    + "저장한 3필드는 비식별 산출물의 image 블록에 수동값 우선으로 반영된다(원천 산출물은 null). "
                    + "비식별 신고 구간(재비식별 대기)에는 412.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용값 외 / srcSn 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (CWE-639 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 재처리 대기 구간 (역할 무관)")
    })
    @PutMapping("/{srcSn}/privacy-meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<FramePrivacyMetaResponse> update(
            @Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @Valid @RequestBody FramePrivacyMetaUpdateRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        // path 의 srcSn 과 body 의 srcSn 불일치 시 거부 (CWE-345).
        if (!srcSn.equals(req.srcSn())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "path 의 srcSn 과 body 의 srcSn 이 다릅니다.");
        }
        return ApiResponse.ok(framePrivacyMetaService.update(srcSn, req, actor));
    }

    @Operation(summary = "프레임 개인정보 메타 벌크 저장(대량성)",
            description = "여러 프레임의 개인정보 메타를 한 요청으로 저장한다(영상당 수천 프레임 수용). 각 항목마다 본인 배정 "
                    + "검증(IDOR). 동일 영상 다건은 관제 TASK_MODIFIED 통지가 디바운스되어 1회로 코얼레스된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용값 외 / 항목 수 초과 / 빈 목록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (CWE-639 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 재처리 대기 구간 (역할 무관)")
    })
    @PutMapping("/privacy-meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<FramePrivacyMetaResponse>> updateBulk(
            @Valid @RequestBody FramePrivacyBulkRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(framePrivacyMetaService.updateBulk(req.items(), actor));
    }
}
