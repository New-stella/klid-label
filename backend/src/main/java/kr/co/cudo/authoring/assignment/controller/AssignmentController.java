package kr.co.cudo.authoring.assignment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.AssignmentHistoryResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.service.AssignmentService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
public class AssignmentController {

    private final AssignmentService assignmentService;

    @Operation(
            summary = "배정 생성",
            description = "REVIEWER가 WORKER에게 영상/프레임 작업을 배정한다. LS_PJT_USER_AUTHRT INSERT."
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
            description = "기존 배정을 다른 WORKER로 변경한다. LS_PJT_USER_AUTHRT_HSTRY 이력 기록."
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

    @Operation(
            summary = "배정 목록 조회 (페이징)",
            description = "인증된 사용자가 본인 또는 (REVIEWER인 경우) 특정 작업자의 배정을 페이징 조회. workerId 파라미터로 필터링 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "타인 배정 조회 권한 없음")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Page<AssignmentResponse.Item>> list(@Parameter(description = "조회 대상 작업자 PK (선택, REVIEWER만)", example = "1001") @RequestParam(required = false) Long workerId,
                                                            @AuthenticationPrincipal TokenClaims actor,
                                                            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(assignmentService.listAssignments(workerId, actor, pageable));
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
