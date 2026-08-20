package kr.co.cudo.authoring.transfer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.transfer.dto.DeidentCompleteRequest;
import kr.co.cudo.authoring.transfer.dto.DeidentCompleteResponse;
import kr.co.cudo.authoring.transfer.service.ImportDeidentCompleteService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비식별 완료 <b>기록</b> API — 외부 URL = {@code /api/v1/videos/{rawSn}/deident-complete}.
 *
 * <h3>이 요청은 비식별을 수행하지 않는다</h3>
 * <p>영상 파일 없이 프레임만 가져온 산출물은 저작도구가 비식별할 대상 영상을 가지고 있지 않다. 밖에서
 * 비식별한 산출물을 받아 <b>그 사실을 기록</b>하는 행위이며, 그 기록이 검수 승인 보류를 푼다.
 *
 * <h3>기록은 산출물 실재를 확인한 뒤에만 성립한다</h3>
 * <p>확인 없이 값만 바꿀 수 있으면 처리되지 않은 산출물이 검수 승인을 통과한다(ADR-048). 판정은
 * {@code ImportDeidentCompleteService} 한 곳이며 컨트롤러는 그 판정을 복제하지 않는다.
 *
 * <p>영상 축 경로를 쓰지만 <b>이관 도메인의 계약</b>이라 이 패키지에 둔다 — 대상 판정("이관 경로로
 * 들어온 영상인가")과 보류 해제 규칙이 전부 이 도메인 소유다.
 *
 * @design DOMAIN-017
 * @design API-215
 * @design AC-046
 * @design ADR-048
 */
@Tag(name = "ImportDeidentComplete", description = "외부 비식별 산출물 기록 — REVIEWER 전용.")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('REVIEWER')")
public class ImportDeidentCompleteController {

    private final ImportDeidentCompleteService deidentCompleteService;

    @Operation(summary = "비식별 완료 기록 (REVIEWER)",
            description = "외부에서 비식별한 산출물을 기록해 검수 승인 보류를 푼다. 산출물 실재를 확인한 뒤에만 성립한다.")
    @PostMapping("/{rawSn}/deident-complete")
    public ApiResponse<DeidentCompleteResponse> record(
            @PathVariable Long rawSn,
            @Valid @RequestBody DeidentCompleteRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(deidentCompleteService.record(rawSn, request,
                actor == null ? null : actor.sub()));
    }
}
