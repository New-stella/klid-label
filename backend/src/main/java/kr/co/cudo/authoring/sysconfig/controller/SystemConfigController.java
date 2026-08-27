package kr.co.cudo.authoring.sysconfig.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
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
import org.springframework.web.bind.annotation.RequestHeader;
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

    /**
     * 관리자 단기 유효창 토큰 헤더 (R11).
     *
     * <p>연동 서버 주소 4종을 저장할 때만 필요하다. 다른 키는 이 헤더 없이 기존과 동일하게 동작한다.
     *
     * <p>이름의 진실원은 {@link AdminSessionGate#HEADER} 다 — 헤더 이름이 여러 곳에 리터럴로 적히면
     * 새 창구가 다른 이름을 받게 되고, 그러면 클라이언트가 창구마다 다른 헤더를 보내야 한다.
     * 이 상수는 기존 참조자(CORS 허용 목록·로그 마스킹 설명)를 위해 남긴 별칭이다.
     */
    public static final String ADMIN_SESSION_HEADER = AdminSessionGate.HEADER;

    @Operation(
            summary = "시스템 설정 단건 수정 (REVIEWER)",
            description = """
                    지정 key의 설정값을 수정한다. 수정 이력은 LS_SYSTEM_CONFIG 의 수정자·수정일시로 남는다.

                    연동 서버 주소 4종(비식별 서버 · AI 추론 서버 · 외부 시계열 분석 벤더 · 관제 통지 수신처)은
                    REVIEWER 권한에 더해 X-Admin-Session 헤더(관리자 단기 유효창 토큰)를 요구한다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 — 주소 형식·스키마 위반"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음 또는 관리자 단기 유효창 없음·만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "설정 키 없음")
    })
    @PutMapping("/{key}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ConfigResponse> update(@Parameter(description = "설정 키", required = true, example = "BATCH_INTERVAL_SEC") @PathVariable String key,
                                              @Valid @RequestBody ConfigUpdateRequest request,
                                              @RequestHeader(value = ADMIN_SESSION_HEADER, required = false) String adminSessionToken,
                                              @AuthenticationPrincipal TokenClaims claims) {
        return ApiResponse.ok(service.update(key, request.value(), claims, adminSessionToken));
    }
}
