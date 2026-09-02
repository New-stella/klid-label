package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalMarkingListResponse;
import kr.co.cudo.authoring.portal.dto.PortalMarkingRequest;
import kr.co.cudo.authoring.portal.dto.PortalMarkingSaveResponse;
import kr.co.cudo.authoring.portal.service.PortalUploadMarkingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포털 업로드 영상 마킹 창구 (PORTAL_USER 전용).
 *
 * <p>관제 마킹 창구를 재사용하지 않는다 — 인가 주체가 다르고, 그 창구가 전제하는 비식별 완료
 * 조건·검증 질문 선택·외부 위탁 기동이 이 경로에는 성립하지 않는다. 반면 <b>마킹 로직 자체는</b>
 * 채널 공통 서비스 한 벌을 그대로 쓴다.
 *
 * @design API-240
 * @design API-241
 * @design SCREEN-045
 */
@Tag(name = "Portal Upload Marking",
        description = "포털 업로드 영상 이벤트 구간 마킹 — 저장이 그 지점으로 프레임 추출을 시작한다.")
@RestController
@RequestMapping("/v1/portal/uploads/{uldSn}/markings")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalUploadMarkingController {

    private final PortalUploadMarkingService markingService;

    @Operation(summary = "마킹 저장(프레임 추출 시작)",
            description = "자동(간격, 단위는 프레임 수)·수동(지점 목록) 두 방식을 받는다. "
                    + "저장이 받아들여지면 그 지점으로 프레임 추출이 시작되고 자산이 추출 중으로 넘어간다. "
                    + "재마킹은 제공하지 않으므로 되돌릴 수 없다 — 다시 마킹하려면 자산을 지우고 다시 올린다. "
                    + "지점 수가 추출 장수 상한을 넘으면 거부하지 않고 상한까지만 뽑으며, 잘린 사실을 응답에 싣는다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalMarkingSaveResponse> save(
            @PathVariable Long uldSn,
            @Valid @RequestBody PortalMarkingRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(markingService.save(uldSn, req, actor));
    }

    @Operation(summary = "마킹 조회",
            description = "저장된 마킹을 저장 시각 내림차순으로 돌려준다. 저장이 막힌 상태에서도 조회는 열려 있다 "
                    + "— 다시 저장할 수 없는 자산일수록 무엇이 저장돼 있는지 확인할 필요가 크다. "
                    + "저장된 것이 없으면 빈 목록이다.")
    @GetMapping
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalMarkingListResponse> list(
            @PathVariable Long uldSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(markingService.list(uldSn, actor));
    }
}
