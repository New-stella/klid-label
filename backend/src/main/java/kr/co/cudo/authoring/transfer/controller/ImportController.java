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
 * <h3>보안 — 관리자 전용</h3>
 * <ul>
 *   <li><b>관리자만 호출할 수 있다</b>(API-206 · ROLE-004). 산출물을 들여오는 것은 운영·관리 성격의
 *       쓰기이기 때문이다. 관리자는 검수자 권한을 계층으로 물려받으므로 이 좁히기는 검수자에게 열려
 *       있던 다른 자리를 건드리지 않는다.</li>
 *   <li>⚠ <b>같은 도메인 안에서도 축이 갈린다</b> — 폴더 탐색·검사와 이관 이력 조회는 이 요구의
 *       대상이 아니며 검수자 권한만으로 응답한다. 조회까지 함께 좁히면 화면이 열리자마자 빈 채로
 *       죽는다.</li>
 *   <li>{@code SecurityConfig} 의 매처와 이 클래스의 {@code @PreAuthorize} 이중 방어.</li>
 *   <li>인가는 <b>경로 판정보다 먼저</b> 평가된다 — 권한 없는 요청에는 그 위치가 있는지 없는지가
 *       응답으로 새지 않는다(CWE-209, AC-048).</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design API-206
 * @design ADR-055
 * @design ROLE-004
 * @design AC-044
 * @design AC-048
 */
@Tag(name = "Import", description = "외부 산출물 적재 — 관리자 전용.")
@RestController
@RequestMapping("/v1/imports")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class ImportController {

    private final ImportService importService;

    @Operation(summary = "외부 산출물 적재 (ADMIN)",
            description = "산출물 폴더를 적재하고 검수 대기로 둔다. 작업자 배정과 검수 제출을 거치지 않는다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImportCreateResponse> create(
            @Valid @RequestBody ImportCreateRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(importService.importFolder(request, actor == null ? null : actor.sub()));
    }
}
