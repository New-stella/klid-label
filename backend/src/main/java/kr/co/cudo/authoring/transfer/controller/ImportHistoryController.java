package kr.co.cudo.authoring.transfer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.transfer.dto.ImportHistoryDetailResponse;
import kr.co.cudo.authoring.transfer.dto.ImportHistoryItemResponse;
import kr.co.cudo.authoring.transfer.service.ImportHistoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이관 <b>이력 조회</b> API — 외부 URL = {@code /api/v1/imports}(context-path={@code /api}).
 *
 * <h3>무엇을 위한 통로인가</h3>
 * <p>언제 누가 어떤 폴더를 가져왔고 얼마나 들어왔는지를 본다. 이미 가져온 산출물인지 판단하는 근거이자,
 * 적재가 도중에 깨졌을 때 무엇 때문이었는지를 되짚는 자리다.
 *
 * <h3>목록은 언제나 한 쪽씩이다</h3>
 * <p>이력은 산출물을 가져올수록 단조 증가한다. 전체를 한 번에 돌려주면 표가 커진 뒤에 응답이 무거워지고
 * 되돌릴 방법이 없다(CWE-770). 정렬은 시간순 하나이며 <b>서버가 고정</b>한다 — 요청이 정렬 키를 고르지
 * 못하므로 알 수 없는 프로퍼티가 그대로 흘러 500 이 되는 자리도 없다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>REVIEWER 전용</b> — {@code SecurityConfig} 의 {@code /v1/**} 매처와 {@code @PreAuthorize}
 *       이중 방어. 인가는 이력 존재 판정보다 <b>먼저</b> 평가되므로 권한 없는 요청에는 그 이력이 있는지
 *       없는지가 응답으로 새지 않는다(CWE-209).</li>
 *   <li>★ <b>이 창구는 일부러 좁히지 않았다</b> — 같은 도메인의 적재 실행·분류 대응 확정은
 *       관리자로 좁혔지만, 탐색·검사와 이력 조회는 검수자 권한으로 응답한다(ROLE-004). 함께
 *       좁히면 화면이 열리자마자 빈 채로 죽는다. 관리자는 계층으로 여기에도 그대로 들어온다.</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design ROLE-004
 * @design API-207
 * @design API-208
 * @design DFEAT-059
 * @design SCREEN-039
 */
@Tag(name = "ImportHistory", description = "외부 산출물 이관 이력 조회 — REVIEWER 전용.")
@RestController
@RequestMapping("/v1/imports")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('REVIEWER')")
public class ImportHistoryController {

    /** 한 쪽 기본 크기 — 목록 API 공통 규약과 같다. */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 이관 상태 허용값 — 계약이 정한 값 밖은 조용히 무시하지 않고 거부한다(0건과 구분되게). */
    private static final String STATUS_PATTERN = "PROCESSING|SUCCESS|FAILED";

    private final ImportHistoryQueryService importHistoryQueryService;

    @Operation(summary = "이관 이력 목록 조회 (REVIEWER)",
            description = "지금까지 가져온 산출물을 최근순으로 돌려준다. 각 항목은 그 이관으로 만들어진 영상에 "
                    + "검수 승인 보류가 서 있는지를 함께 싣는다 — 이관 상태와는 다른 축이라 이관 상태로 대신 "
                    + "판단할 수 없다.")
    @GetMapping
    public ApiResponse<Page<ImportHistoryItemResponse>> list(
            @Parameter(description = "이관 상태 필터 (PROCESSING/SUCCESS/FAILED). 비우면 전체")
            @RequestParam(required = false) @Pattern(regexp = STATUS_PATTERN) String status,
            @Parameter(description = "페이지 번호 (0-based)", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 (1~100)", example = "20")
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(100) int size) {
        return ApiResponse.ok(importHistoryQueryService.list(status, page, size));
    }

    @Operation(summary = "이관 이력 상세 조회 (REVIEWER)",
            description = "이관 한 건의 경로·건수·상태와 실패한 경우 그 사유를 돌려준다.")
    @GetMapping("/{trnsfSn}")
    public ApiResponse<ImportHistoryDetailResponse> detail(@PathVariable @Min(1) long trnsfSn) {
        return ApiResponse.ok(importHistoryQueryService.detail(trnsfSn));
    }
}
