package kr.co.cudo.authoring.transfer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.transfer.dto.MarkingImportCreateRequest;
import kr.co.cudo.authoring.transfer.dto.MarkingImportCreateResponse;
import kr.co.cudo.authoring.transfer.dto.MarkingImportProgressResponse;
import kr.co.cudo.authoring.transfer.dto.MarkingImportScanRequest;
import kr.co.cudo.authoring.transfer.dto.MarkingImportScanResponse;
import kr.co.cudo.authoring.transfer.service.MarkingImportProgressService;
import kr.co.cudo.authoring.transfer.service.MarkingImportScanService;
import kr.co.cudo.authoring.transfer.service.MarkingImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>마킹 산출물</b> 일괄 가져오기 API — 외부 URL = {@code /api/v1/imports/markings**}
 * (context-path={@code /api}).
 *
 * <h3>★ 라벨링 완료 갈래와 창구를 나눈다</h3>
 * <p>두 갈래는 방향이 반대다 — 그쪽은 라벨링이 끝난 결과를 받아 검수만 하고, 이쪽은 시작점만 받아
 * 비식별부터 라벨링까지 앞 단계를 전부 밟는다. ADR-053 이 <b>계약을 합치지 않으며 화면에서만 갈래를
 * 고른다</b>고 정했다. 기존 창구에 종류 항목을 더하지 않는다 — 더하면 그 값에 따라 나머지 항목의
 * 필수 여부가 갈리고, 어느 조합이 유효한지가 계약이 아니라 코드에만 남는다.
 *
 * <h3>검사와 적재는 나뉜 두 단계다</h3>
 * <p>검사는 폴더를 훑어 <b>무엇이 몇 건 들어오는지</b>만 보여 주고 아무것도 저장하지 않는다. 적재는
 * 그 결과를 사람이 확인한 뒤의 별도 요청이다. 한 번에 처리하면 사람이 확인할 자리가 사라진다.
 *
 * <h3>★ 권한이 창구마다 다르다 — 통일하지 말 것</h3>
 * <ul>
 *   <li><b>검사·진행 조회는 검수자</b>(API-216 · API-218). 조회까지 관리자로 좁히면 화면이 열리자마자
 *       빈 채로 죽는다. 관리자는 계층으로 여기에도 그대로 들어온다.</li>
 *   <li><b>적재 실행은 관리자</b>(API-217). 산출물을 들여오는 것은 운영·관리 성격의 쓰기다. 같은
 *       도메인의 라벨링 완료 갈래도 같은 축으로 갈려 있다(검사·이력=검수자 / 적재=관리자).</li>
 * </ul>
 * <p>이 비대칭은 <b>의도</b>이며 「일관성」을 이유로 한쪽으로 맞추면 둘 중 하나가 무너진다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>{@code SecurityConfig} 의 매처와 각 메서드의 {@code @PreAuthorize} 이중 방어.</li>
 *   <li>인가는 <b>경로 판정보다 먼저</b> 평가된다 — 권한 없는 요청에는 그 위치가 있는지 없는지가
 *       응답으로 새지 않는다(CWE-209).</li>
 *   <li>폴더 경로는 사람이 넣는 외부 문자열이라 허용 저장소 범위 판정을 반드시 거친다(CWE-22/59) —
 *       판정은 {@code ImportSourcePolicy} 한 곳이고, 훑기는 그 판정이 돌려준 실경로만 쓴다.</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design ADR-052
 * @design ADR-053
 * @design API-216
 * @design API-217
 * @design API-218
 * @design DFEAT-060
 * @design UC-037
 * @design SCREEN-039
 * @design SEQ-030
 */
@Tag(name = "MarkingImport",
        description = "마킹 산출물 일괄 가져오기 — 검사·진행 조회는 REVIEWER, 적재 실행은 ADMIN.")
@RestController
@RequestMapping("/v1/imports/markings")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class MarkingImportController {

    /** 진행 조회의 상태 필터 허용값 — 계약이 정한 값 밖은 조용히 무시하지 않고 거부한다(0건과 구분되게). */
    private static final String ITEM_STATUS_PATTERN = "PENDING|PROCESSING|SUCCESS|FAILED|SKIPPED";

    private final MarkingImportScanService markingImportScanService;
    private final MarkingImportService markingImportService;
    private final MarkingImportProgressService markingImportProgressService;

    @Operation(summary = "마킹 산출물 폴더 검사 (REVIEWER)",
            description = "폴더와 그 아래를 재귀로 훑어 마킹 문서와 영상의 짝을 찾고 건별 판정을 돌려준다. "
                    + "아무것도 저장하지 않으며 같은 요청을 여러 번 보내도 결과가 같다.")
    @PostMapping("/scan")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<MarkingImportScanResponse> scan(
            @Valid @RequestBody MarkingImportScanRequest request) {
        return ApiResponse.ok(markingImportScanService.scan(request));
    }

    /**
     * 적재 작업을 등록한다 — <b>202</b> 로 곧바로 반환한다.
     *
     * <p>이 응답이 뜻하는 것은 「작업이 등록되었다」뿐이다. 적재는 아직 끝나지 않았으며 진행은
     * 작업 식별번호로 따로 조회한다. 201 이 아닌 이유가 여기 있다 — 만들어진 것은 <b>작업</b>이지
     * 영상이 아니다.
     */
    @Operation(summary = "마킹 산출물 일괄 적재 (ADMIN)",
            description = "검사에서 적재할 수 있다고 나온 짝을 일괄로 적재하는 작업을 등록하고 곧바로 반환한다. "
                    + "실제 적재는 뒤에서 항목별로 진행하며 진행 상황은 따로 조회한다.")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<MarkingImportCreateResponse> create(
            @Valid @RequestBody MarkingImportCreateRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(markingImportService.create(request, actor == null ? null : actor.sub()));
    }

    @Operation(summary = "일괄 적재 진행 조회 (REVIEWER)",
            description = "어디까지 되었는지와 건별 결과를 돌려준다. 상태로 거르는 것은 담기는 목록뿐이며 "
                    + "위쪽 집계는 언제나 전체 기준이다.")
    @GetMapping("/{jobSn}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<MarkingImportProgressResponse> progress(
            @PathVariable @Min(1) long jobSn,
            @Parameter(description = "항목 상태 필터 (PENDING/PROCESSING/SUCCESS/FAILED/SKIPPED). 비우면 전체")
            @RequestParam(required = false) @Pattern(regexp = ITEM_STATUS_PATTERN) String status) {
        return ApiResponse.ok(markingImportProgressService.getProgress(jobSn, status));
    }
}
