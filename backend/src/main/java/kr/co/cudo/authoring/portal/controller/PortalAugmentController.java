package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalAugmentDetailResponse;
import kr.co.cudo.authoring.portal.dto.PortalAugmentSummaryResponse;
import kr.co.cudo.authoring.portal.service.PortalAugmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포털 증강 <b>요청 현황·결과 확인</b> 창구 (PORTAL_USER 전용).
 *
 * <p>요청을 거는 자리는 여기가 아니다 — 같은 행위의 진입을 두 자리에 두지 않는다
 * ({@link PortalUploadAugmentController}).
 *
 * <h3>정렬을 받지 않는다</h3>
 * <p>요청 일시 내림차순 고정이다. 받으면 정렬 키 허용 목록을 따로 유지해야 하는데(CWE-89/770)
 * 이 목록에는 최근 것부터 보는 것 말고 다른 순서가 필요하지 않다.
 *
 * <h3>페이지 번호와 크기의 처리가 다르다</h3>
 * <p>음수 페이지는 <b>거부</b>(400)하고, 상한을 넘는 페이지 크기는 거부하지 않고 <b>상한으로 줄여</b>
 * 처리한다. 전자는 요청이 성립하지 않는 값이고 후자는 성립하되 과한 값이기 때문이다.
 *
 * @design API-232
 * @design API-233
 * @design SCREEN-044
 */
@Tag(name = "Portal Augment",
        description = "포털 증강 요청 현황·결과 — 본인이 낸 요청만 조회된다. 채택·반려 결정 단계가 없다.")
@RestController
@RequestMapping("/v1/portal/augments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalAugmentController {

    /** 페이지 크기 기본값. */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 페이지 크기 상한 — 무제한 조회 방지(초과분은 거부가 아니라 절단). */
    private static final int MAX_PAGE_SIZE = 100;

    private final PortalAugmentService portalAugmentService;

    @Operation(summary = "증강 요청 현황 목록",
            description = "본인이 낸 요청을 요청 일시 내림차순으로 페이징해 돌려준다. 요청이 하나도 없으면 "
                    + "빈 목록이 담긴 성공 응답이며 오류가 아니다. 페이지 크기는 상한으로 잘라 처리한다.")
    @GetMapping
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<Page<PortalAugmentSummaryResponse>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @AuthenticationPrincipal TokenClaims actor) {
        if (page < 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "페이지 번호는 0 이상이어야 합니다.");
        }
        return ApiResponse.ok(portalAugmentService.list(actor, PageRequest.of(page, capped(size))));
    }

    @Operation(summary = "증강 요청 단건 조회",
            description = "요청 정보와 결과물 위치를 돌려준다. 대기·실패도 조회 자체는 성공이며 그때 결과물 "
                    + "식별자는 비어 있다. 남의 요청과 없는 요청은 같은 코드로 거절한다.")
    @GetMapping("/{augSn}")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalAugmentDetailResponse> get(
            @PathVariable Long augSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(portalAugmentService.get(augSn, actor));
    }

    /** 상한 절단 — 1 미만은 기본값으로, 상한 초과는 상한으로. 어느 쪽도 거부가 아니다. */
    private static int capped(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
