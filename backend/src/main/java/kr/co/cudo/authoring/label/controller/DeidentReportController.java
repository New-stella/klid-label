package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.SortAllowlist;
import kr.co.cudo.authoring.label.dto.DeidentReportRequest;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2 (R1 v1.14) — 비식별 누락 신고 API.
 *
 * <p>경로:
 * <ul>
 *   <li>{@code POST /v1/labels/{srcSn}/deident-report} — 신고 등록(라벨링 단계, 프레임 기준).</li>
 *   <li>{@code POST /v1/videos/{rawSn}/deident-report} — 신고 등록(마킹 단계, 영상 기준 — B-ISSUE-28).</li>
 *   <li>{@code POST /v1/deident-reports/{rprtSn}/resolve} — 외부 솔루션 수동 비식별화 완료 후 신고 해소.</li>
 * </ul>
 * <p>영상 단위 경로는 URL 이 {@code /v1/videos/**} 지만 <b>비식별 신고 워크플로</b>의 일부라 본 컨트롤러에
 * 둔다 — 두 진입점의 응답 규약·권한·부수효과가 한 파일에서 함께 검토되게 하기 위함이다(서비스도
 * {@code DeidentReportService} 한 곳으로 수렴한다).
 * <p>권한: REVIEWER, WORKER (WORKER 는 본인 배정 영상만 — LabelAccessGuard).
 */
@Tag(name = "DeidentReport",
        description = "Phase 2(R1 v1.14) — 마킹·라벨링 중 비식별 미흡(얼굴/번호판 미블러 등) 신고. " +
                "신고 즉시 영상이 잠기고(LOCKED_FOR_REDEIDENT) DE_IDNTF_YN='F' 로 전이된다. " +
                "작업 결과는 보존한다 — 라벨과 개인정보 판정 3필드를 삭제·리셋하지 않으며, " +
                "신고 구간 동안 해당 영상의 라벨 조회·저장만 412 로 막는다(2026-07-27 · 2026-08-04 정책 반전. " +
                "구 동작 '영상 전체 라벨을 복원 가능 스냅샷 기록 후 삭제'는 폐기됐다 — 그 스냅샷은 " +
                "영상 스코프라 복원 진입점이 없는 write-only 이력이었다). " +
                "재처리는 외부 솔루션 수동 비식별화 흐름으로 진행되며, 완료 시 resolve 로 " +
                "OPEN→RESOLVED 전이 + 작업락 해제 + 'F'→'Y' 복원(게이트 자동 해제)이 일어나 " +
                "보존된 라벨을 그대로 재사용한다.")
@RestController
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
@SecurityRequirement(name = "bearerAuth")
public class DeidentReportController {

    /**
     * 정렬 미지정 시 기본 정렬 — 신고일시 최신순. {@code @PageableDefault} 와 동일 값이며,
     * allowlist 해석 결과가 비었을 때의 폴백이기도 하다.
     */
    private static final Sort DEFAULT_REPORT_SORT = Sort.by(Sort.Order.desc("reportDt"));

    private final DeidentReportService deidentReportService;

    @Operation(
            summary = "비식별 신고 목록 조회 (REVIEWER)",
            description = "비식별 누락 신고 목록을 상태(status=OPEN|RESOLVED|DISMISSED)로 필터링해 페이징 조회한다. " +
                    "기본 status=OPEN, 기본 정렬 reportDt DESC. REVIEWER 전용. " +
                    "status allowlist 밖 입력은 400. " +
                    "정렬(sort)은 allowlist(reportDt/reportedAt, resolvedDt/resolvedAt, status, rprtSn/id, " +
                    "rawSn/videoId)만 허용하며 미등록 키·과다 항목은 400. " +
                    "응답 행에는 신고 단계(stage=MARKING|LABELING, V171)가 포함된다 — 해소 시 재개 지점이 " +
                    "이 값으로 갈린다(MARKING=마킹부터 다시 / LABELING=프레임만 재추출). " +
                    "컬럼 신설 이전 레거시 신고는 stage=null(단계 미상)이며 해소해도 단계별 재개가 일어나지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "status allowlist 밖 / 미등록 정렬 키"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/v1/deident-reports")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Page<kr.co.cudo.authoring.label.dto.DeidentReportListResponse>> list(
            @Parameter(description = "신고 상태 필터 (기본 OPEN)", example = "OPEN")
            @RequestParam(required = false)
            @Pattern(regexp = "^(OPEN|RESOLVED|DISMISSED)$",
                    message = "status 는 OPEN/RESOLVED/DISMISSED 만 허용됩니다.") String status,
            @PageableDefault(size = 20, sort = "reportDt", direction = Sort.Direction.DESC) Pageable pageable) {
        // A-ISSUE-61 (HIGH, CWE-770/209/20) — 정렬 키를 allowlist 로만 해석한다. 미배선 상태에서는
        //   Pageable 이 리포지토리로 직행해 미등록 키가 PropertyReferenceException → 500 으로 새어나가고
        //   (ERROR 로그에 내부 엔티티명·JPQL 원문 적재), 정렬 항목 개수 상한도 없어 인증 사용자 1명이
        //   쿼리 플랜 캐시를 오염시킬 수 있었다. strict 모드 — 변경 전에도 200 이 아니었으므로 400 은
        //   하위호환 파손이 아니다(CLAUDE.md 목록 정렬 정책).
        Pageable safePageable =
                SortAllowlist.apply(pageable, SortAllowlist.DEIDENT_REPORT, DEFAULT_REPORT_SORT);
        return ApiResponse.ok(deidentReportService.listReports(status, safePageable));
    }

    @Operation(
            summary = "비식별 누락 신고",
            description = "프레임 srcSn 에 해당하는 영상에 대해 비식별 누락 신고를 등록한다. " +
                    "신고 시 영상이 잠기고 DE_IDNTF_YN='F' 로 전이된다. " +
                    "라벨과 개인정보 판정 3필드는 보존된다(삭제·리셋하지 않는다). " +
                    "WORKER 는 본인 배정 영상에 한해서만 가능 (CWE-639 방어). " +
                    "이미 잠금 상태(LOCKED_FOR_REDEIDENT) 인 영상은 409. " +
                    "접수 대상이 아닌 영상은 412 — ① 파생영상(증강·해상도 변환본, 재비식별 수단 없음) " +
                    "② 비식별 미수행 영상 ③ 마킹 단계 제한 위반 ④ 검수가 승인(APPROVED)된 영상(R2, 역할 무관)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신고 등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 (reason 누락/1000자 초과)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (WORKER 인 경우)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임/영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 재비식별 진행 중"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412",
                    description = "신고 접수 대상 아님 — 파생영상 / 비식별 미수행 / 검수 승인(APPROVED) 영상")
    })
    @PostMapping("/v1/labels/{srcSn}/deident-report")
    @PreAuthorize("hasAnyRole('WORKER', 'REVIEWER')")
    public ResponseEntity<ApiResponse<Long>> report(
            @Parameter(description = "프레임 PK (SRC_SN)", required = true, example = "1") @PathVariable Long srcSn,
            @Valid @RequestBody DeidentReportRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        Long rprtSn = deidentReportService.report(srcSn, request.reason(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(rprtSn));
    }

    @Operation(
            summary = "비식별 누락 신고 (마킹 단계 · 영상 단위)",
            description = "영상 rawSn 에 대해 비식별 누락 신고를 등록한다. 마킹 화면은 비식별 '영상'을 재생해 " +
                    "프레임(srcSn) 컨텍스트가 없으므로 영상 단위 진입점을 제공한다. " +
                    "부수효과(작업락 + DE_IDNTF_YN='F')는 라벨링 단계 신고와 동일하다. " +
                    "라벨과 개인정보 판정 3필드는 보존된다(리셋하지 않는다). " +
                    "WORKER 는 본인 배정 영상만 가능 (CWE-639 방어). " +
                    "412 — 파생영상(증강·해상도 변환본, 재비식별 수단 없음) / 비식별 미수행 / " +
                    "마킹 단계(MARKING_READY) 아님 / 검수가 승인(APPROVED)된 영상(R2, 역할 무관)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신고 등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 (reason 누락/1000자 초과)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (WORKER 인 경우)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 재비식별 진행 중"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412",
                    description = "신고 접수 대상 아님 — 파생영상 / 비식별 미수행 / 마킹 단계 아님 / 검수 승인(APPROVED) 영상")
    })
    @PostMapping("/v1/videos/{rawSn}/deident-report")
    @PreAuthorize("hasAnyRole('WORKER', 'REVIEWER')")
    public ResponseEntity<ApiResponse<Long>> reportByVideo(
            @Parameter(description = "영상 PK (RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody DeidentReportRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        Long rprtSn = deidentReportService.reportByVideo(rawSn, request.reason(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(rprtSn));
    }

    @Operation(
            summary = "재비식별 산출물 후보 목록 조회 (R3)",
            description = "이 신고의 영상에 대응하는 비식별 산출 디렉터리를 열거해, 해소 시 고를 수 있는 " +
                    "산출물 후보를 돌려준다. 외부 비식별 솔루션은 결과를 원본과 다른 이름으로 만들 수 있어 " +
                    "(예: {원본stem}-mask{ext}) 서버가 어느 파일이 재비식별 결과인지 단정할 수 없다 — " +
                    "사람이 고르게 하기 위한 목록이다. " +
                    "각 항목은 파일명·크기·수정시각과 함께 eligible(무결성 + 신고 이후 생성 조건 통과 여부), " +
                    "current(현재 원장이 가리키는 파일인지)를 담는다. " +
                    "내부 저장 경로는 응답에 포함하지 않는다. " +
                    "인가는 해소(resolve)와 동일 — WORKER 는 본인 배정 영상만, REVIEWER 는 전체. " +
                    "디렉터리가 없거나 비었으면 빈 목록 + 200(에러 아님)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공(후보 0건이면 빈 목록)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (WORKER 인 경우)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "신고 없음")
    })
    @GetMapping("/v1/deident-reports/{rprtSn}/deident-candidates")
    @PreAuthorize("hasAnyRole('WORKER', 'REVIEWER')")
    public ApiResponse<java.util.List<kr.co.cudo.authoring.label.dto.DeidentCandidateResponse>> deidentCandidates(
            @Parameter(description = "신고 PK (DEIDENT_REPORT_SN)", required = true, example = "1") @PathVariable Long rprtSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(deidentReportService.listDeidentCandidates(rprtSn, actor));
    }

    @Operation(
            summary = "비식별 신고 수동 해소",
            description = "외부 솔루션으로 수동 비식별화를 완료한 뒤 호출. 신고를 OPEN→RESOLVED 로 전이하고 " +
                    "작업락을 해제한다. WORKER 는 본인 배정 영상만 가능 (CWE-639 방어). " +
                    "이미 처리(RESOLVED/DISMISSED)된 신고는 409. " +
                    "★ R3 — 요청 바디의 fileName(재비식별 산출물 선택)은 필수다. 서버는 기본값을 고르지 않으며, " +
                    "후보 목록 API 와 같은 열거 결과에 그 파일명이 있을 때만 수락한다(목록이 곧 허용목록). " +
                    "목록에 없으면 400, 있으나 무결성·시간조건 미통과면 409. " +
                    "성공 시 선택한 산출물이 LS_DEIDENT_PROC_LOG 의 새 성공 행으로 적재되어 " +
                    "이후 프레임 재추출·영상 스트리밍이 그 파일을 사용한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해소 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "fileName 누락/공백 또는 후보 목록에 없는 파일명"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (WORKER 인 경우)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "신고 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "이미 처리된 신고 (OPEN 아님) / 선택 산출물이 재비식별 결과로 확인되지 않음")
    })
    @PostMapping("/v1/deident-reports/{rprtSn}/resolve")
    @PreAuthorize("hasAnyRole('WORKER', 'REVIEWER')")
    public ResponseEntity<ApiResponse<Void>> resolve(
            @Parameter(description = "신고 PK (DEIDENT_REPORT_SN)", required = true, example = "1") @PathVariable Long rprtSn,
            @Valid @RequestBody kr.co.cudo.authoring.label.dto.DeidentResolveRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        deidentReportService.resolveManually(rprtSn, request.fileName(), actor);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
