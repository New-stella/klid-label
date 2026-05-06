package kr.co.cudo.authoring.sysconfig.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.sysconfig.dto.ConfigResponse;
import kr.co.cudo.authoring.sysconfig.dto.ConfigUpdateRequest;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 시스템 설정 관리 컨트롤러 (V1.3 — REVIEWER 가 ADMIN 권한 흡수, /v1/manage/* 경로).
 */
@Tag(name = "System Config", description = "시스템 설정 관리 — V1.3: ADMIN 흡수로 REVIEWER 전용. /v1/manage/* 경로.")
@RestController
@RequestMapping("/v1/manage/configs")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class SystemConfigController {

    private final SystemConfigService service;

    @Operation(
            summary = "시스템 설정 전체 조회 (REVIEWER)",
            description = "모든 시스템 설정 키-값 목록을 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<List<ConfigResponse>> list() {
        return ApiResponse.ok(service.listAll());
    }

    @Operation(
            summary = "시스템 설정 단건 수정 (REVIEWER)",
            description = "지정 key의 설정값을 수정한다. 수정 이력은 별도 감사 로그로 기록."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "설정 키 없음")
    })
    @PutMapping("/{key}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ConfigResponse> update(@Parameter(description = "설정 키", required = true, example = "BATCH_INTERVAL_SEC") @PathVariable String key,
                                              @Valid @RequestBody ConfigUpdateRequest request,
                                              @AuthenticationPrincipal TokenClaims claims) {
        return ApiResponse.ok(service.update(key, request.value(), claims));
    }
}
