package kr.co.cudo.authoring.controlnotify.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.dto.TaskLabelsResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskMetaResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskSummaryResponse;
import kr.co.cudo.authoring.controlnotify.service.TaskQueryService;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 4 -- 관제서버 조회용 API.
 *
 * <p>통지 수신 후 상세 데이터 취득 목적. 조회 전용 (상태 변경 없음).
 * <p>{@code authoring.control-notify.enabled=true} 환경에서만 활성화.
 *
 * <p><b>CWE-639 IDOR (DEV_FIX H-1)</b>: 역할({@code @PreAuthorize})만으로는 부족하다 — 세 경로 모두
 * {@code rawSn} 하나로 영상 요약·라벨 본문·메타를 반환하므로, 배정 이력이 없는 WORKER 가 rawSn 을
 * 순회하면 {@code /v1/videos/**} 에 적용된 영상 단위 통제를 그대로 우회한다. 진입부에서
 * {@link LabelAccessGuard#verifyRawAccess}(REVIEWER 전체 / WORKER 본인 LABELER 배정)를 적용한다.
 * 관제서버는 REVIEWER 권한으로 조회하므로 얼리리턴으로 통과한다(연동 영향 없음).
 */
@RestController
@RequestMapping("/v1/tasks")
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
@Validated
@Tag(name = "Task Query", description = "관제서버 조회용 API — 통지 수신 후 상세 데이터 취득")
@SecurityRequirement(name = "bearerAuth")
public class TaskQueryController {

    private final TaskQueryService taskQueryService;
    /** 영상 단위 인가 — 라벨/스트림 경로와 동일한 확립된 가드를 재사용한다(DEV_FIX H-1). */
    private final LabelAccessGuard labelAccessGuard;

    @Operation(
            summary = "영상별 요약 조회",
            description = "프레임 수, 라벨 수, 메타 수, 상태, 최종 수정일 반환."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/{rawSn}/summary")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<TaskSummaryResponse> getSummary(
            @Parameter(description = "raw 영상 PK", required = true) @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        labelAccessGuard.verifyRawAccess(rawSn, actor);
        return ApiResponse.ok(taskQueryService.getSummary(rawSn));
    }

    @Operation(
            summary = "영상별 라벨 목록 조회",
            description = "프레임 단위 라벨 목록. frameIds 파라미터로 특정 프레임만 필터 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/{rawSn}/labels")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<Page<TaskLabelsResponse>> getLabels(
            @Parameter(description = "raw 영상 PK", required = true) @PathVariable Long rawSn,
            @Parameter(description = "필터할 프레임 srcSn 목록 (생략 시 전체, 최대 100개)")
            @RequestParam(required = false)
            @Size(max = TaskQueryService.MAX_FRAME_ID_FILTER,
                    message = "frameIds 는 최대 100개까지 지정할 수 있습니다.") List<Long> frameIds,
            @Parameter(description = "프레임 단위 페이징 (기본 20, 최대 100)")
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal TokenClaims actor) {
        labelAccessGuard.verifyRawAccess(rawSn, actor);
        return ApiResponse.ok(taskQueryService.getLabels(rawSn, frameIds, pageable));
    }

    @Operation(
            summary = "영상별 메타데이터 조회",
            description = "영상에 연결된 메타 K/V 목록 반환 (페이징 — 기본 20, 최대 100)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/{rawSn}/meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<TaskMetaResponse> getMeta(
            @Parameter(description = "raw 영상 PK", required = true) @PathVariable Long rawSn,
            @Parameter(description = "메타 단위 페이징 (기본 20, 최대 100)")
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal TokenClaims actor) {
        labelAccessGuard.verifyRawAccess(rawSn, actor);
        return ApiResponse.ok(taskQueryService.getMeta(rawSn, pageable));
    }
}
