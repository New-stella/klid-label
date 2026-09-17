package kr.co.cudo.authoring.assignment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.AssignmentHistoryResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentSearchCondition;
import kr.co.cudo.authoring.assignment.dto.EventTypeOptionsResponse;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.service.AssignmentService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.SortAllowlist;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Assignment", description = "작업 배정 — REVIEWER가 WORKER에게 라벨링 작업을 배정/재배정한다 (V1.3 정책).")
@RestController
@RequestMapping("/v1/assignments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@org.springframework.validation.annotation.Validated
public class AssignmentController {

    private final AssignmentService assignmentService;

    /** 정렬 미지정 시 기본값 — 배정일 최신순 (기존 기본 동작 불변). */
    private static final Sort DEFAULT_ASSIGNMENT_SORT = Sort.by(Sort.Direction.DESC, "regDt");

    @Operation(
            summary = "배정 생성",
            description = "REVIEWER가 WORKER에게 영상/프레임 작업을 배정한다. LS_TASK_ALTMNT INSERT."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "배정 생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "중복 배정")
    })
    @PostMapping
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<ApiResponse<AssignmentResponse>> create(@Valid @RequestBody AssignmentCreateRequest req,
                                                                   @AuthenticationPrincipal TokenClaims actor) {
        AssignmentResponse res = assignmentService.assign(req, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(res));
    }

    @Operation(
            summary = "배정 재할당",
            description = "기존 배정을 다른 WORKER로 변경한다. 작업 이벤트 로그에 재배정 이력 기록."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재배정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "배정 없음")
    })
    @PatchMapping("/{assignmentId}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AssignmentResponse> reassign(@Parameter(description = "배정 PK", required = true, example = "100") @PathVariable Long assignmentId,
                                                    @Valid @RequestBody ReassignRequest req,
                                                    @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(assignmentService.reassign(assignmentId, req, actor));
    }

    /**
     * <b>배정 해제</b> — 담당을 <b>없앤다</b>(재배정은 담당을 <b>바꾼다</b>).
     * [@design ADR-069] [@design API-259] [@design AC-1122] [@design AC-1123]
     *
     * <p>요청 본문을 받지 않는다 — 사유를 받지 않으며(감추는 쪽만 사유를 남긴다), 사유를 주소에 실으면
     * 접근 기록에 개인정보가 남는다.
     */
    @Operation(
            summary = "배정 해제",
            description = "작업자 배정을 푼다. 그 배정이 사라져 영상이 미배정 상태로 돌아간다. " +
                    "검수자 이상이 수행하며 역할 계층으로 관리자도 그대로 수행한다(작업자는 403).\n\n" +
                    "- **배정만 푼다** — 그 작업의 라벨과 라벨 이력은 한 건도 지우지 않는다.\n" +
                    "- **검수 대기·검수 중·승인 상태의 배정은 409**(ASSIGNMENT_SUBMITTED) — 배정이 그 " +
                    "워크플로의 전제라 풀면 검수 흐름이 주인 없는 상태가 된다.\n" +
                    "- **반려 상태는 해제된다** — 워크플로가 작업자에게 되돌아온 상태라 그 작업 자체를 " +
                    "접을 수 있어야 한다. 그 귀결로 「반려 → 해제 → 제외」가 이어진다.\n" +
                    "- 되돌리려면 다시 배정한다(POST /v1/assignments). 해제를 취소하는 창구는 없다.\n" +
                    "- 작업 이벤트 원장에 해제 이벤트가 남는다 — 행위자·그 시점 역할·발생일시, " +
                    "배정이 풀린 작업자는 대상 사용자 칸.\n\n" +
                    "⚠ 검수 축의 '점유 해제'(검수 중 표시가 유예로 저절로 풀리는 것)와는 다른 축이다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "해제 성공 (본문 없음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "검수자 이상 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "배정 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "검수에 들어간 배정 (ASSIGNMENT_SUBMITTED)")
    })
    @DeleteMapping("/{assignmentId}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<Void> unassign(
            @Parameter(description = "해제할 배정 PK", required = true, example = "100")
            @PathVariable Long assignmentId,
            @AuthenticationPrincipal TokenClaims actor) {
        assignmentService.unassign(assignmentId, actor);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "배정 목록 조회 (페이징 + 필터)",
            description = "인증된 사용자가 본인 또는 (REVIEWER인 경우) 특정 작업자의 배정을 페이징 조회한다. " +
                    "검색어(q)·워크플로 상태(workStatus)·이벤트유형(eventTypeCd) 필터는 **현재 페이지가 아니라 " +
                    "전체 데이터셋 기준**으로 적용되며 totalElements 도 필터 결과 기준이다.\n\n" +
                    "- 신규 파라미터는 전부 optional 이고 기본값이 없다 — 하나도 보내지 않으면 변경 전과 동일한 결과다.\n" +
                    "- workStatus 는 FE AssignmentStatus 와 같은 값 집합" +
                    "(PENDING|IN_PROGRESS|REVIEW_PENDING|COMPLETED|REJECTED)이며, 응답 status 와 **동일 근거**로 " +
                    "판정된다(작업중 = 그 영상에 사용자 라벨 저장 이력이 1건 이상. 배치 오토라벨은 이력을 남기지 " +
                    "않으므로 '작업중'으로 치지 않는다).\n" +
                    "- 정렬 키는 allowlist(regDt/assignedAt/videoId/rawDataId/id/assignmentId) 밖이면 400 이다.\n" +
                    "- **WORKER 의 workerId 파라미터는 무시**된다(403 아님) — 본인 배정으로 범위가 고정된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용되지 않은 필터/정렬 값"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "타인 배정 조회 권한 없음")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Page<AssignmentResponse.Item>> list(
            @Parameter(description = "조회 대상 작업자 PK (선택, REVIEWER만 — WORKER 요청에서는 무시된다)", example = "1001")
            @RequestParam(required = false)
            @Positive(message = "workerId 는 양수여야 합니다") Long workerId,
            @Parameter(description = "검색어 (선택) — 영상명·작업자명 부분일치.", example = "강남")
            @RequestParam(name = "q", required = false)
            @Size(max = 100, message = "검색어는 100자 이하여야 합니다") String q,
            @Parameter(description = "워크플로 상태 필터 (선택). 빈 값/공백만 보내면 q·eventTypeCd 와 동일하게 "
                    + "'필터 미적용' 으로 취급된다.", example = "IN_PROGRESS")
            @RequestParam(name = "workStatus", required = false)
            @Pattern(
                    // 앞의 `[\x00-\x20]*` 는 "빈 문자열·공백만" 입력 허용 — AssignmentSearchCondition 의
                    // 정규화가 null 로 떨어뜨려 필터가 걸리지 않는다. 이 분기가 없으면
                    // `?workStatus=` 만 400 이 되어 q·eventTypeCd 와 blank 처리 의미가 갈린다.
                    //
                    // ⚠ 이 정규식은 **1차 방어**일 뿐 최종 판정이 아니다. 허용값의 진실원은
                    // AssignmentWorkStatus 이며, 미지 값의 최종 거부는 그쪽 parse() 가 fail-closed 로
                    // 수행한다(둘이 어긋나면 필터만 증발하는 fail-open 이 된다).
                    // 두 정의의 드리프트는 AssignmentWorkStatusTest 의 결박 테스트가 잡는다.
                    regexp = "^[\\x00-\\x20]*$|^(PENDING|IN_PROGRESS|REVIEW_PENDING|COMPLETED|REJECTED)$",
                    message = "허용되지 않은 workStatus 값"
            ) String workStatus,
            @Parameter(description = "이벤트 유형 코드 필터 (선택). 표시명이 같은 유형은 한 그룹이라 "
                    + "그룹 내 어느 코드를 보내도 그룹 전체가 조회된다.", example = "EV01000101")
            @RequestParam(name = "eventTypeCd", required = false)
            @Size(max = 20, message = "이벤트 유형 코드는 20자 이하여야 합니다") String eventTypeCd,
            @AuthenticationPrincipal TokenClaims actor,
            @PageableDefault(size = 20, sort = "regDt", direction = Sort.Direction.DESC) Pageable pageable) {
        // 정렬 키 화이트리스트 (CWE-20/CWE-209) — 미등록 키는 500(PropertyReferenceException) 이 아니라 400.
        Pageable safePageable =
                SortAllowlist.apply(pageable, SortAllowlist.ASSIGNMENT, DEFAULT_ASSIGNMENT_SORT);
        AssignmentSearchCondition condition =
                AssignmentSearchCondition.ofRequest(workerId, q, workStatus, eventTypeCd);
        return ApiResponse.ok(assignmentService.listAssignments(condition, actor, safePageable));
    }

    /**
     * 배정 목록 이벤트유형 셀렉트 옵션.
     *
     * <p><b>파라미터 시그니처가 계약이다</b> — {@code eventTypeCd} 를 <b>선언하지 않는다</b>. 자기 축을
     * 반영하면 하나를 고르는 순간 나머지 선택지가 사라져 되돌아갈 수 없기 때문이며, FE 가 목록과 같은
     * 쿼리스트링을 그대로 보내도(=eventTypeCd 포함) 미선언 파라미터로 무시된다.
     * 나머지 축(workerId/q/workStatus)은 목록과 <b>같은 검증·같은 인가</b>를 통과한다.
     */
    @Operation(
            summary = "배정 목록 이벤트유형 옵션 조회",
            description = "본인(REVIEWER 는 전체/특정 작업자) 배정 **전체**에 존재하는 이벤트유형 코드를 " +
                    "중복 없이 오름차순으로 반환한다.\n\n" +
                    "- **표시명이 같은 유형은 옵션 1건으로 접힌다** — 값은 그룹 **대표코드**(그룹 내 최소 " +
                    "유형코드)이며, 그 값으로 목록을 필터하면 **그룹 전체 코드**의 배정이 조회된다. " +
                    "비대표 코드로 필터해도 같은 그룹 전체가 조회된다(기존 북마크 하위호환).\n" +
                    "- 마스터에 없는 비규격 코드는 접지 않고 **원문 그대로** 노출된다(필터로 도달 가능).\n" +
                    "- 목록(`GET /v1/assignments`)과 **같은 조건**을 적용하되 **자기 축(eventTypeCd)만 제외**한다 — " +
                    "따라서 옵션에서 고른 값으로 같은 필터에 이어 붙이면 결과가 0건일 수 없다.\n" +
                    "- 인가도 목록과 동일하다 — **WORKER 의 workerId 파라미터는 무시**되고 본인 배정으로 고정된다.\n" +
                    "- EVNT_TYPE_CD 가 null/공백인 영상은 제외되고, 반환 값은 앞뒤 공백이 제거된다 " +
                    "(목록 필터 eventTypeCd 도 trim 후 비교하므로 옵션을 그대로 다시 보내면 매칭된다).\n" +
                    "- **페이징 없음** — 코드값 select-option 성격이라 화면이 전량을 한 번에 받아야 하고, " +
                    "무제한 조회는 상한(500)으로 방어한다(페이징 없는 전체조회 금지 규칙의 예외).\n" +
                    "- 상한 초과 시 잘라서 반환하며 **응답의 truncated=true 로 그 사실을 알린다**.\n" +
                    "- 응답 형태는 `GET /v1/tasks/board/event-types` 와 동일하다(FE 공용 컴포넌트)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용되지 않은 필터 값"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "조회 권한 없음")
    })
    @GetMapping("/event-types")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<EventTypeOptionsResponse> eventTypes(
            @Parameter(description = "조회 대상 작업자 PK (선택, REVIEWER만 — WORKER 요청에서는 무시된다)", example = "1001")
            @RequestParam(required = false)
            @Positive(message = "workerId 는 양수여야 합니다") Long workerId,
            @Parameter(description = "검색어 (선택) — 영상명·작업자명 부분일치.", example = "강남")
            @RequestParam(name = "q", required = false)
            @Size(max = 100, message = "검색어는 100자 이하여야 합니다") String q,
            @Parameter(description = "워크플로 상태 필터 (선택). 빈 값/공백만 보내면 '필터 미적용'.",
                    example = "IN_PROGRESS")
            @RequestParam(name = "workStatus", required = false)
            @Pattern(
                    // 목록(list)과 동일한 정의를 유지한다 — 같은 값 집합을 다르게 검증하면 FE 가 같은
                    // 쿼리스트링으로 두 엔드포인트를 호출할 때 한쪽만 400 이 된다.
                    // 최종 판정은 여기가 아니라 AssignmentWorkStatus.parse (fail-closed) 다.
                    regexp = "^[\\x00-\\x20]*$|^(PENDING|IN_PROGRESS|REVIEW_PENDING|COMPLETED|REJECTED)$",
                    message = "허용되지 않은 workStatus 값"
            ) String workStatus,
            @AuthenticationPrincipal TokenClaims actor) {
        AssignmentSearchCondition condition =
                AssignmentSearchCondition.ofRequest(workerId, q, workStatus, null);
        return ApiResponse.ok(assignmentService.listEventTypeOptions(condition, actor));
    }

    @Operation(
            summary = "배정 이력 조회",
            description = "단일 배정의 통합 이벤트 이력(배정/재배정/검수)을 시간 오름차순으로 반환한다. " +
                    "REVIEWER 는 모든 배정 이력을 조회할 수 있고, WORKER 는 본인 배정 이력만 조회 가능 (IDOR 방어)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정이 아닌 이력 조회 시도")
    })
    @GetMapping("/{assignmentId}/history")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<AssignmentHistoryResponse>> history(@Parameter(description = "배정 PK", required = true, example = "100") @PathVariable Long assignmentId,
                                                                @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(assignmentService.getHistory(assignmentId, actor));
    }
}
