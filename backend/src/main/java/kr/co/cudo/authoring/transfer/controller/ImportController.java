package kr.co.cudo.authoring.transfer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.transfer.dto.ImportCreateRequest;
import kr.co.cudo.authoring.transfer.dto.ImportCreateResponse;
import kr.co.cudo.authoring.transfer.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 산출물 <b>적재</b> API — 외부 URL = {@code /api/v1/imports} (context-path={@code /api}).
 *
 * <h3>미리보기와 적재는 나뉜 두 단계다</h3>
 * <p>검사({@code /v1/imports/scan})는 아무것도 저장하지 않고 무엇이 몇 건 들어오는지만 보여 준다.
 * 이 요청은 그 결과를 사람이 확인한 뒤의 <b>별도 행위</b>다. 한 번에 처리하면 사람이 확인할 자리가
 * 사라진다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>REVIEWER 전용</b> — {@code SecurityConfig} 의 매처와 이 클래스의 {@code @PreAuthorize}
 *       이중 방어.</li>
 *   <li>인가는 <b>경로 판정보다 먼저</b> 평가된다 — 권한 없는 요청에는 그 위치가 있는지 없는지가
 *       응답으로 새지 않는다(CWE-209, AC-048).</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design API-206
 * @design AC-044
 * @design AC-048
 */
@Tag(name = "Import", description = "외부 산출물 적재 — REVIEWER 전용.")
@RestController
@RequestMapping("/v1/imports")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('REVIEWER')")
public class ImportController {

    private final ImportService importService;

    @Operation(summary = "외부 산출물 적재 (REVIEWER)",
            description = "산출물 폴더를 적재하고 검수 대기로 둔다. 작업자 배정과 검수 제출을 거치지 않는다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImportCreateResponse> create(
            @Valid @RequestBody ImportCreateRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(importService.importFolder(request, actor == null ? null : actor.sub()));
    }
}
