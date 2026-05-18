package kr.co.cudo.authoring.assignment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import kr.co.cudo.authoring.assignment.dto.TaskBoardItemResponse;
import kr.co.cudo.authoring.assignment.service.TaskBoardService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
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

    @Operation(
            summary = "REVIEWER 작업 목록 조회 (페이징)",
            description = "처리 완료 영상(LS_DATA_RAW.DATA_STTS_CD) 을 페이징하여 LABELER/REVIEWER 배정 정보와 함께 반환한다. " +
                    "미배정 영상도 포함되며 task 측 필드는 null. REVIEWER 권한 필수."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/board")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Page<TaskBoardItemResponse>> board(
            @Parameter(description = "영상 배치 상태 (기본 COMPLETED)", example = "COMPLETED")
            @RequestParam(name = "status", required = false, defaultValue = "COMPLETED")
            @Pattern(
                    regexp = "^(COMPLETED|ASSIGNED|PENDING|IN_REVIEW|APPROVED|REJECTED)$",
                    message = "허용되지 않은 status 값"
            ) String status,
            @PageableDefault(size = 20, sort = "regDt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(taskBoardService.listBoard(status, actor, pageable));
    }
}
