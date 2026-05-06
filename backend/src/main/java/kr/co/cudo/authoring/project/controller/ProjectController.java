package kr.co.cudo.authoring.project.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.project.dto.ProjectResponse;
import kr.co.cudo.authoring.project.dto.ProjectSummaryResponse;
import kr.co.cudo.authoring.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Project", description = "프로젝트 — 활성 프로젝트 목록 및 상세 조회 (인증 사용자 누구나).")
@RestController
@RequestMapping("/v1/projects")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ProjectController {

    private final ProjectService projectService;

    @Operation(
            summary = "활성 프로젝트 목록 조회 (페이징)",
            description = "사용중(USE_YN=Y) 프로젝트만 페이징하여 반환. 기본 size=20, sort=createdAt,desc."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @GetMapping
    public ApiResponse<Page<ProjectSummaryResponse>> list(@PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(projectService.listActiveProjects(pageable));
    }

    @Operation(
            summary = "프로젝트 상세 조회",
            description = "프로젝트 PK로 단건 조회한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프로젝트 없음")
    })
    @GetMapping("/{pjtId}")
    public ApiResponse<ProjectResponse> get(@Parameter(description = "프로젝트 PK", required = true, example = "1") @PathVariable Long pjtId) {
        return ApiResponse.ok(projectService.getProject(pjtId));
    }
}
