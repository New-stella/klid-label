package kr.co.cudo.authoring.assignment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.assignment.dto.EventTypeOptionsResponse;
import kr.co.cudo.authoring.assignment.dto.TaskBoardItemResponse;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSummaryResponse;
import kr.co.cudo.authoring.assignment.service.TaskBoardService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.SortAllowlist;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCR-TASK-001 REVIEWER 작업 목록 통합 BE 엔드포인트 (Phase 3).
 *
 * <p>처리 완료 영상 + (optional) LABELER 배정을 LEFT JOIN 형태로 페이징 응답한다.
 * FE 가 기존에 /v1/videos?size=999 + /v1/assignments 두 번 호출해 클라이언트에서 left-join 하던
 * 로직을 BE 단일 엔드포인트로 흡수한다.
 *
 * <p>접근 제어: REVIEWER 만 접근 가능 — 미배정 영상도 노출되므로 권한 상승 위험 방어 (CWE-862/863).
 */
@Tag(name = "TaskBoard", description = "REVIEWER 통합 작업 목록 — 처리 완료 영상 + (left-join) 배정 정보.")
@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@org.springframework.validation.annotation.Validated
public class TaskBoardController {

    private final TaskBoardService taskBoardService;

    /** 정렬 미지정/폴백 기본값 — 등록일 최신순 (R8: 기존 기본 동작 불변). */
    private static final Sort DEFAULT_BOARD_SORT = Sort.by(Sort.Direction.DESC, "regDt");

    @Operation(
            summary = "REVIEWER 작업 목록 조회 (페이징 + 필터)",
            description = "영상(LS_DATA_RAW) 을 페이징하여 LABELER/REVIEWER 배정 정보와 함께 반환한다. " +
                    "미배정 영상도 포함되며 task 측 필드는 null. REVIEWER 권한 필수.\n\n" +
                    "정렬은 시간축 단일(기본 등록일 최신순)이며 상태 우선순위 정렬은 적용하지 않는다 — " +
                    "우선순위는 workStatus 필터로 표현한다. 정렬 키는 allowlist(regDt/capturedAt/shtDt/rawSn/videoId) " +
                    "밖이면 400 이다. status(배치 상태)와 workStatus(워크플로 상태)는 독립 축이며 AND 결합된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용되지 않은 필터/정렬 값"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/board")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Page<TaskBoardItemResponse>> board(
            @Parameter(description = "영상 배치 상태 (기본 COMPLETED). UNASSIGNED 지정 시 LABELER 배정이 없는 영상만 반환.",
                    example = "COMPLETED")
            @RequestParam(name = "status", required = false, defaultValue = "COMPLETED")
            @Pattern(
                    regexp = "^(COMPLETED|UNASSIGNED|ASSIGNED|PENDING|IN_REVIEW|APPROVED|REJECTED)$",
                    message = "허용되지 않은 status 값"
            ) String status,
            @Parameter(description = "워크플로 상태 필터 (선택). 배치 상태(status)와 독립 축. "
                    + "빈 값/공백만 보내면 q·eventTypeCd 와 동일하게 '필터 미적용' 으로 취급된다.",
                    example = "REJECTED")
            @RequestParam(name = "workStatus", required = false)
            @Pattern(
                    // 앞의 `[\x00-\x20]*` 는 "빈 문자열·공백만" 입력 허용 — TaskBoardSearchCondition 의
                    // 정규화(String#trim, U+0020 이하를 제거)가 null 로 떨어뜨려 필터가 걸리지 않는다.
                    // 이 분기가 없으면 defaultValue 가 없는 workStatus 만 `?workStatus=` 에서 400 이 되어
                    // q·eventTypeCd 와 blank 처리 의미가 갈린다.
                    regexp = "^[\\x00-\\x20]*$|^(UNASSIGNED|PENDING|REVIEW_PENDING|REJECTED|COMPLETED)$",
                    message = "허용되지 않은 workStatus 값"
            ) String workStatus,
            @Parameter(description = "검색어 (선택) — 영상명·작업자명 부분일치.", example = "강남")
            @RequestParam(name = "q", required = false)
            @Size(max = 100, message = "검색어는 100자 이하여야 합니다") String q,
            @Parameter(description = "이벤트 유형 코드 필터 (선택). 표시명이 같은 유형은 한 그룹이라 "
                    + "그룹 내 어느 코드를 보내도 그룹 전체가 조회된다.", example = "EV01000101")
            @RequestParam(name = "eventTypeCd", required = false)
            @Size(max = 20, message = "이벤트 유형 코드는 20자 이하여야 합니다") String eventTypeCd,
            @Parameter(description = "작업자(USER_NO) 필터 (선택) — 최신 배정 작업자 기준.", example = "100")
            @RequestParam(name = "workerId", required = false)
            @Positive(message = "workerId 는 양수여야 합니다") Long workerId,
            @PageableDefault(size = 20, sort = "regDt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal TokenClaims actor) {
        // 정렬 키 화이트리스트 (CWE-20/CWE-209) — 미등록 키는 500(PropertyReferenceException) 이 아니라 400.
        Pageable safePageable = SortAllowlist.apply(pageable, SortAllowlist.TASK_BOARD, DEFAULT_BOARD_SORT);
        TaskBoardSearchCondition condition =
                new TaskBoardSearchCondition(status, workStatus, q, eventTypeCd, workerId);
        return ApiResponse.ok(taskBoardService.listBoard(condition, actor, safePageable));
    }

    @Operation(
            summary = "REVIEWER 작업 목록 KPI 집계",
            description = "작업목록 KPI 카드용 워크플로 상태별 건수를 **필터 결과 전체 기준**으로 반환한다 " +
                    "(현재 페이지가 아니다). REVIEWER 권한 필수.\n\n" +
                    "- 필터는 목록(GET /v1/tasks/board)과 동일하게 status/q/eventTypeCd/workerId 가 적용된다.\n" +
                    "- **workStatus 는 전달돼도 무시**한다 — KPI 카드 자체가 workStatus 선택지이므로, " +
                    "이미 workStatus 로 좁혀진 집합 위에서 세면 항상 1개 카드만 값을 갖는다.\n" +
                    "- status=UNASSIGNED(가상 status) 이면 목록과 동일하게 배치 상태 무관 · LABELER 미배정 " +
                    "전체가 기준이 되어 결과적으로 unassigned 카드만 값을 갖는다.\n" +
                    "- **제외분은 집계에서 빠진다** — 화면 목록에서 제외한 영상은 목록과 마찬가지로 " +
                    "이 집계에도 들어가지 않는다(목록과 같은 조건 조립을 공유한다).\n" +
                    "- **excludedCount** — 같은 필터 범위 안의 제외 건수가 키 하나로 실린다. 현재 " +
                    "페이지가 아니라 필터 결과 전체 기준이며 **0건이어도 실린다**. 위 합의 항이 아니다.\n" +
                    "- 불변식: total == unassigned + inProgress + reviewPending + completed + rejected.\n" +
                    "- inProgress 는 BoardWorkStatus.PENDING(배정됨 · 검수 미제출) 집계다(IN_PROGRESS 값은 없다).\n" +
                    "- 목록과 별도 요청이므로 각 값은 조회 시점 스냅샷이다.\n\n" +
                    "**FE 연동 지침 — 미배정 카드 클릭 시 `status=UNASSIGNED` 가 아니라 " +
                    "`workStatus=UNASSIGNED` 를 보낼 것.** UNASSIGNED 는 두 축에서 서로 다른 집합을 뜻한다: " +
                    "`status=UNASSIGNED` 는 배치 상태 필터를 끄고 미배정 전체를 반환하고, " +
                    "`workStatus=UNASSIGNED` 는 현재 배치 상태 필터 안에서의 미배정만 반환한다. " +
                    "unassigned 버킷은 **후자**를 세므로, 카드 클릭 시 status 를 바꿔 보내면 " +
                    "카드 숫자와 목록 totalElements 가 어긋난다(예: 카드 3 ↔ 목록 13). " +
                    "카드 클릭은 현재 쿼리스트링의 status 를 유지한 채 workStatus 만 추가하면 된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용되지 않은 필터 값"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/board/summary")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<TaskBoardSummaryResponse> boardSummary(
            @Parameter(description = "영상 배치 상태 (기본 COMPLETED). UNASSIGNED 지정 시 LABELER 배정이 없는 영상만 집계. "
                    + "빈 값/공백만 보내면 기본값(COMPLETED)으로 정규화된다.",
                    example = "COMPLETED")
            @RequestParam(name = "status", required = false, defaultValue = "COMPLETED")
            @Pattern(
                    // 앞의 `[\x00-\x20]*` 는 "빈 문자열·공백만" 허용 — TaskBoardSearchCondition 이 기본값
                    // (COMPLETED)으로 정규화한다. FE 가 상태 필터를 해제하며 `status=` 를 보낼 때 KPI 카드가
                    // 400 으로 통째로 비는 것을 막는다. 기존 GET /v1/tasks/board 의 status 는 R8 하위호환
                    // 제약으로 regex 를 바꾸지 않지만, 신규 엔드포인트에는 그 제약이 없다.
                    regexp = "^[\\x00-\\x20]*$|^(COMPLETED|UNASSIGNED|ASSIGNED|PENDING|IN_REVIEW|APPROVED|REJECTED)$",
                    message = "허용되지 않은 status 값"
            ) String status,
            @Parameter(description = "워크플로 상태 필터 — 집계에서는 무시된다(카드 자체가 이 값의 선택지). "
                    + "목록과 동일한 쿼리스트링을 그대로 보낼 수 있도록 파라미터만 허용한다.",
                    example = "REJECTED")
            @RequestParam(name = "workStatus", required = false)
            @Pattern(
                    regexp = "^[\\x00-\\x20]*$|^(UNASSIGNED|PENDING|REVIEW_PENDING|REJECTED|COMPLETED)$",
                    message = "허용되지 않은 workStatus 값"
            ) String workStatus,
            @Parameter(description = "검색어 (선택) — 영상명·작업자명 부분일치.", example = "강남")
            @RequestParam(name = "q", required = false)
            @Size(max = 100, message = "검색어는 100자 이하여야 합니다") String q,
            @Parameter(description = "이벤트 유형 코드 필터 (선택). 표시명이 같은 유형은 한 그룹이라 "
                    + "그룹 내 어느 코드를 보내도 그룹 전체가 조회된다.", example = "EV01000101")
            @RequestParam(name = "eventTypeCd", required = false)
            @Size(max = 20, message = "이벤트 유형 코드는 20자 이하여야 합니다") String eventTypeCd,
            @Parameter(description = "작업자(USER_NO) 필터 (선택) — 최신 배정 작업자 기준.", example = "100")
            @RequestParam(name = "workerId", required = false)
            @Positive(message = "workerId 는 양수여야 합니다") Long workerId,
            @AuthenticationPrincipal TokenClaims actor) {
        // workStatus 는 조건 객체에 담지 않는다 — 서비스/리포지토리 어느 단계에서도 집계 대상이 좁혀지면 안 된다.
        TaskBoardSearchCondition condition =
                new TaskBoardSearchCondition(status, null, q, eventTypeCd, workerId);
        return ApiResponse.ok(taskBoardService.summarizeBoard(condition, actor));
    }

    /**
     * 이벤트유형 셀렉트 옵션 조회.
     *
     * <p><b>파라미터 시그니처가 계약이다</b> — 이 메서드는 {@code status} 하나만 선언하고
     * {@link TaskBoardSearchCondition#statusOnly()} 로 나머지 축을 한 번 더 제거해 이중으로 방어한다.
     * 여기에 파라미터를 추가할 때는 그 값이 옵션 목록을 좁혀도 되는지 먼저 판단해야 한다
     * (좁히면 사용자가 필터를 건 뒤 옵션이 사라져 되돌아갈 수 없다).
     */
    @Operation(
            summary = "작업 목록 이벤트유형 옵션 조회",
            description = "이벤트유형 셀렉트 옵션용 코드 목록을 중복 없이 오름차순으로 반환한다. REVIEWER 권한 필수.\n\n" +
                    "- **표시명이 같은 유형은 옵션 1건으로 접힌다** — 값은 그룹 **대표코드**(그룹 내 최소 " +
                    "유형코드)이며, 그 값으로 목록을 필터하면 **그룹 전체 코드**의 영상이 조회된다. " +
                    "비대표 코드로 필터해도 같은 그룹 전체가 조회된다(기존 북마크 하위호환).\n" +
                    "- 마스터에 없는 비규격 코드는 접지 않고 **원문 그대로** 노출된다(필터로 도달 가능).\n" +
                    "- 적용 필터는 status(배치 상태 축) 하나뿐이다 — q/eventTypeCd/workerId/workStatus 는 " +
                    "반영하지 않는다(필터를 건 뒤 옵션이 사라지면 되돌아갈 수 없다).\n" +
                    "- EVNT_TYPE_CD 가 null/공백인 영상은 제외되고, 반환 값은 앞뒤 공백이 제거된다 " +
                    "(목록 필터 eventTypeCd 도 trim 후 비교하므로 옵션을 그대로 다시 보내면 매칭된다).\n" +
                    "- **페이징 없음** — 목록 조회지만 코드값 select-option 성격이라 화면이 전량을 한 번에 " +
                    "받아야 하고, 무제한 조회는 상한(500)으로 방어한다(페이징 없는 전체조회 금지 규칙의 예외).\n" +
                    "- 상한 초과 시 잘라서 반환하며 **응답의 truncated=true 로 그 사실을 알린다** — " +
                    "true 면 items 는 전체가 아니므로 화면은 '일부만 표시' 안내나 검색형 입력으로 대체해야 한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용되지 않은 status 값"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/board/event-types")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<EventTypeOptionsResponse> boardEventTypes(
            @Parameter(description = "영상 배치 상태 (기본 COMPLETED). UNASSIGNED 지정 시 LABELER 배정이 없는 영상 기준. "
                    + "빈 값/공백만 보내면 기본값(COMPLETED)으로 정규화된다.",
                    example = "COMPLETED")
            @RequestParam(name = "status", required = false, defaultValue = "COMPLETED")
            @Pattern(
                    // summary 와 동일 — 빈 값/공백만 허용해 기본값으로 정규화한다(위 boardSummary 주석 참조).
                    regexp = "^[\\x00-\\x20]*$|^(COMPLETED|UNASSIGNED|ASSIGNED|PENDING|IN_REVIEW|APPROVED|REJECTED)$",
                    message = "허용되지 않은 status 값"
            ) String status,
            @AuthenticationPrincipal TokenClaims actor) {
        TaskBoardSearchCondition condition =
                new TaskBoardSearchCondition(status, null, null, null, null);
        return ApiResponse.ok(taskBoardService.listEventTypeOptions(condition, actor));
    }
}
