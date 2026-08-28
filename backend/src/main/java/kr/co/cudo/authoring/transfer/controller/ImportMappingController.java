package kr.co.cudo.authoring.transfer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.transfer.dto.ImportMappingCreateRequest;
import kr.co.cudo.authoring.transfer.dto.ImportMappingListResponse;
import kr.co.cudo.authoring.transfer.dto.ImportMappingSaveResponse;
import kr.co.cudo.authoring.transfer.service.ImportMappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 <b>분류 대응</b> 관리 API — 외부 URL = {@code /api/v1/import-mappings}
 * (context-path={@code /api}).
 *
 * <h3>왜 대응을 따로 관리하는가</h3>
 * <p>외부 산출물의 분류 이름은 저작도구 라벨 체계와 다르다. 한 번 정해 두면 다음 산출물부터는 같은
 * 분류를 다시 묻지 않는다 — 산출물마다 다시 확정해야 한다면 규모가 큰 이관에서는 쓸 수 없다.
 *
 * <h3>확정은 사람이 한다</h3>
 * <p>검사 응답이 이름이 비슷한 후보를 제시하지만, 저장되는 것은 <b>사람이 고른 값</b>뿐이다. 서버가
 * 대신 확정하는 통로는 이 API 에 없다.
 *
 * <h3>보안 — 한 클래스 안에서 축이 갈린다</h3>
 * <ul>
 *   <li><b>조회는 검수자 이상, 쓰기는 관리자 전용</b>이다. 목록 조회(API-209)는 검수자 권한으로
 *       응답하고, 대응 확정(API-210)과 해제(API-211)는 관리자만 호출할 수 있다 — 산출물을 들여오는
 *       쪽은 운영·관리 성격의 쓰기이기 때문이다(ROLE-004).</li>
 *   <li>★ <b>클래스에 건 게이트를 관리자로 올리지 말 것</b> — 그러면 조회까지 함께 좁아져 화면이
 *       열리자마자 빈 채로 죽는다. 좁히는 표기는 <b>쓰기 메서드에만</b> 얹는다. 관리자는 검수자
 *       권한을 계층으로 물려받으므로 조회에도 그대로 들어온다.</li>
 *   <li>{@code SecurityConfig} 의 {@code /v1/**} 매처와 {@code @PreAuthorize} 이중 방어.</li>
 *   <li>요청 본문은 DTO 로만 받는다 — 식별번호·등록일시·사용여부를 요청으로 받지 않는다(CWE-915).</li>
 *   <li>목록은 페이지 단위로만 돌려준다 — 대응 표는 산출물을 가져올수록 늘어난다(CWE-770).</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design API-209
 * @design API-210
 * @design API-211
 * @design ADR-055
 * @design ROLE-004
 * @design AC-043
 */
@Tag(name = "ImportMapping", description = "외부 분류 대응 — 조회는 검수자 이상, 확정·해제는 관리자 전용.")
@RestController
@RequestMapping("/v1/import-mappings")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('REVIEWER')")
public class ImportMappingController {

    /** 한 페이지 기본 크기 — 목록 API 공통 규약과 같다. */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ImportMappingService importMappingService;

    @Operation(summary = "분류 대응 목록 조회 (REVIEWER 이상)",
            description = "종류로 거를 수 있다. 해제된 대응은 기본적으로 뺀다.")
    @GetMapping
    public ApiResponse<ImportMappingListResponse> list(
            @Parameter(description = "대응 종류 (LABEL/EVNT_TYPE). 비우면 둘 다")
            @RequestParam(required = false) String kind,
            @Parameter(description = "해제된 대응까지 포함할지 여부")
            @RequestParam(defaultValue = "false") boolean includeUnused,
            @Parameter(description = "페이지 번호 (0-based)", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 (1~100)", example = "20")
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(100) int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(importMappingService.list(kind, includeUnused, pageable));
    }

    /**
     * 분류 대응 확정 — <b>관리자 전용</b>. 클래스에 걸린 검수자 게이트보다 좁은 표기를 이 자리에만
     * 얹는다(API-210 · ROLE-004). 같은 클래스의 목록 조회는 검수자 권한으로 계속 응답한다.
     */
    @Operation(summary = "분류 대응 확정 (ADMIN)",
            description = "사람이 고른 연결만 저장한다. 이미 있는 대응을 바꾸려면 overwrite 를 함께 보낸다.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImportMappingSaveResponse> create(
            @Valid @RequestBody ImportMappingCreateRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        String actorId = actor == null ? null : actor.sub();
        return ApiResponse.ok(importMappingService.save(request, actorId));
    }

    /**
     * 분류 대응 해제 — <b>관리자 전용</b>. 확정과 같은 축이라 같은 요구를 건다(API-211 · ROLE-004).
     */
    @Operation(summary = "분류 대응 해제 (ADMIN)",
            description = "행을 지우지 않고 쓰지 않음으로 표시한다. 이미 적재된 라벨은 그대로 둔다.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{mpngSn}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Min(1) long mpngSn,
                       @AuthenticationPrincipal TokenClaims actor) {
        importMappingService.disable(mpngSn, actor == null ? null : actor.sub());
    }
}
