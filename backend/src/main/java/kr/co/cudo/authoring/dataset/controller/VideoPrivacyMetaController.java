package kr.co.cudo.authoring.dataset.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaResponse;
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaUpdateRequest;
import kr.co.cudo.authoring.dataset.service.VideoPrivacyMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영상 단위 개인정보(익명·가명·개인정보 포함여부) 메타 API (V163).
 *
 * <p>인가는 {@code @PreAuthorize}(역할) + 서비스단 {@code verifyRawAccess}(WORKER 본인 배정) 이중 방어이며,
 * 포털 채널 토큰은 {@code SecurityConfig} 의 내부/포털 채널 격리로 차단된다. Entity 직접 노출 없이
 * {@link VideoPrivacyMetaResponse} 로만 반환한다.
 *
 * <p>프레임 단위 대응 API 는 {@code /v1/frames/{srcSn}/privacy-meta}(FramePrivacyMetaController)이며
 * <b>입도가 다른 별개 축</b>이다 — export JSON 의 {@code video} 블록은 여기(영상 단위), {@code image}
 * 블록은 프레임 단위 값을 읽는다.
 */
@Tag(name = "VideoPrivacyMeta",
        description = "영상 개인정보(익명·가명·개인정보 포함여부) 메타 — 조회는 수동값 우선 프리필, 저장은 전체 교체(PUT).")
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class VideoPrivacyMetaController {

    private final VideoPrivacyMetaService videoPrivacyMetaService;

    @Operation(summary = "영상 개인정보 메타 조회",
            description = "영상 단위 개인정보 판정을 조회한다. 수동 저장값이 있으면 그 값(MANUAL), 없으면 비식별 산출물 "
                    + "기본상수(익명 Y / 가명 N / 개인정보포함 N)를 프리필(DERIVED)로 반환한다. WORKER 는 본인 배정 영상만. "
                    + "<b>주의</b> — anonymitySource/pseudonymitySource/privacyIncludedSource(MANUAL/DERIVED)가 "
                    + "해당 값이 사람이 저장한 판정인지 시스템 기본상수인지를 구분하는 근거다. DERIVED 값을 그대로 "
                    + "PUT 으로 되돌려보내면 수동값으로 승격되므로, 프리필 상태로 유지할 필드는 PUT 시 null 로 전송한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님/채널 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/v1/videos/{rawSn}/privacy-meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<VideoPrivacyMetaResponse> get(
            @Parameter(description = "영상 PK(RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(videoPrivacyMetaService.get(rawSn, actor));
    }

    @Operation(summary = "영상 개인정보 메타 저장(전체 교체)",
            description = "영상 단위 개인정보 판정을 저장한다. <b>전체 교체 계약</b> — anonymity/pseudonymity/privacyIncluded "
                    + "3필드를 항상 함께 전송해야 하며, 생략(null)한 필드는 수동값이 삭제되어 조회 시 기본상수로 폴백한다. "
                    + "허용값은 Y/N 뿐이다. 저장값은 학습데이터 export JSON 의 video 블록 개인정보 3필드로 나가며 "
                    + "<b>비식별(deid) 산출물에만</b> 실린다(원천 산출물은 비식별 처리 전이라 판정하지 않고 null). "
                    + "검수 완료 후 수정 시 export 폴더가 새 버전으로 전량 재생성된 뒤 관제 TASK_MODIFIED(META_UPDATED) "
                    + "통지가 발행된다. WORKER 는 본인 배정 영상만.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용값(Y/N) 외"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님/채널 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @PutMapping("/v1/videos/{rawSn}/privacy-meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<VideoPrivacyMetaResponse> update(
            @Parameter(description = "영상 PK(RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody VideoPrivacyMetaUpdateRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(videoPrivacyMetaService.update(rawSn, req, actor));
    }
}
