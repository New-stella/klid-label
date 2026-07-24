package kr.co.cudo.authoring.dataset.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.dataset.dto.EnvironmentMetaResponse;
import kr.co.cudo.authoring.dataset.dto.EnvironmentMetaUpdateRequest;
import kr.co.cudo.authoring.dataset.service.EnvironmentMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2 — 영상 단위 촬영환경(날씨·시간대·계절) 메타 API.
 *
 * <p>인가는 {@code @PreAuthorize}(역할) + 서비스단 {@code verifyRawAccess}(WORKER 본인 배정) 이중 방어이며,
 * 포털 채널 토큰은 {@code SecurityConfig} 의 내부/포털 채널 격리로 차단된다. Entity 직접 노출 없이
 * {@link EnvironmentMetaResponse} 로만 반환한다.
 */
@Tag(name = "EnvironmentMeta",
        description = "영상 촬영환경(날씨·시간대·계절) 메타 — 조회는 수동값 우선 파생 프리필, 저장은 전체 교체(PUT).")
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class EnvironmentMetaController {

    private final EnvironmentMetaService environmentMetaService;

    @Operation(summary = "촬영환경 메타 조회",
            description = "영상 단위 촬영환경을 조회한다. 수동 저장값이 있으면 그 값(MANUAL), 없으면 촬영일시 파생값(DERIVED)을 "
                    + "프리필로 반환한다. 날씨는 자동 출처가 없어 미입력 시 null. WORKER 는 본인 배정 영상만. "
                    + "<b>주의</b> — 값과 함께 반환되는 weatherSource/timeOfDaySource/seasonSource(MANUAL/DERIVED)가 "
                    + "해당 값이 저장된 수동값인지 촬영일시 파생 프리필인지를 구분하는 근거다. DERIVED 값을 그대로 "
                    + "PUT 으로 되돌려보내면 수동값으로 승격되므로, 파생 상태로 유지할 필드는 PUT 시 null 로 전송한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님/채널 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/v1/videos/{rawSn}/environment-meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<EnvironmentMetaResponse> get(
            @Parameter(description = "영상 PK(RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(environmentMetaService.get(rawSn, actor));
    }

    @Operation(summary = "촬영환경 메타 저장(전체 교체)",
            description = "영상 단위 촬영환경을 저장한다. <b>전체 교체 계약</b> — weather/timeOfDay/season 3필드를 항상 함께 "
                    + "전송해야 하며, 생략(null)한 필드는 수동값이 삭제되어 조회 시 파생값으로 폴백한다. "
                    + "허용값: weather=맑음/흐림/비/눈/안개, timeOfDay=DAY/NGT, season=SPRING/SUMMER/FALL/WINTER. "
                    + "<b>파생값을 파생 상태로 유지하려면 해당 필드를 null 로 전송해야 한다</b> — 조회 응답의 프리필값"
                    + "(source=DERIVED)을 그대로 되돌려보내면 수동값(MANUAL)으로 승격되어, 이후 촬영일시가 정정돼도 "
                    + "옛 값이 스냅샷·export 에 고정된다. "
                    + "검수 완료 후 수정 시 동결 스냅샷만 재동결(데이터마트 뷰에 최신값 반영)되고 관제 "
                    + "TASK_MODIFIED(META_UPDATED) 통지가 발행된다. 편집은 export 파일 재생성을 트리거하지 않으며(라벨 수정과 동일 정책), "
                    + "export 폴더는 다음 검수 승인 시점에 전량 재산출된다. WORKER 는 본인 배정 영상만.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용값 외/길이 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님/채널 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @PutMapping("/v1/videos/{rawSn}/environment-meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<EnvironmentMetaResponse> update(
            @Parameter(description = "영상 PK(RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody EnvironmentMetaUpdateRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(environmentMetaService.update(rawSn, req, actor));
    }
}
