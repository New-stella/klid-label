package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.SortAllowlist;
import kr.co.cudo.authoring.portal.dto.PortalUserWorkResponse;
import kr.co.cudo.authoring.portal.service.PortalUserWorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 포털 채널 진입 화면의 <b>「내 작업」 목록</b> 창구. @design API-225, SCREEN-028, UC-024
 *
 * <p>데이터마트 영상 <b>카탈로그</b>는 포털(Host)이 자기 화면에서 제공한다. 저작도구가 그리는 목록은
 * 「내가 저장한 작업」이며, 그래서 기존 본인 라벨 조회 창구(영상 식별자가 필수라 한 영상 안의 라벨만
 * 돌려준다)로는 채울 수 없다. 같은 URL 에 파라미터로 행위를 분기하지 않는다는 규약에 따라 확장이
 * 아니라 <b>별도 창구</b>로 둔다.
 *
 * <p>보안 — {@code SecurityConfig} 의 {@code /v1/portal/**} 규칙과 {@code @PreAuthorize} 로
 * {@code PORTAL_USER} 만 통과하고, 본인 데이터 격리는 서비스가 토큰 주체로 강제한다(CWE-639).
 */
@Tag(name = "Portal User Work",
        description = "포털 「내 작업」 목록 — PORTAL_USER 전용. 본인 업로드 자산 + 본인 저작물이 있는 데이터마트 영상.")
@RestController
@RequestMapping("/v1/portal/user-works")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalUserWorkController {

    /** 페이지 크기 하드캡(API-225) — 넘으면 상한으로 클램프한다. */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 정렬 allowlist — 외부 키 → 리포지토리 논리 키. 값 집합은 응답에 실제로 실리는 두 값뿐이다.
     *
     * <p><b>{@code lastSavedAt} 은 반드시 포함</b>한다 — {@code @PageableDefault} 의 기본 정렬이라
     * 빠지면 파라미터 없는 호출이 전부 400 이 된다.
     *
     * <p>strict 모드({@link SortAllowlist#apply})다 — 신설 창구라 「변경 전에는 200 이었다」는 하위호환
     * 논거가 없고, 사양이 미등록 키를 400 으로 못박았다(API-225).
     */
    private static final Map<String, String> SORT_KEYS = Map.of(
            "lastSavedAt", "lastSavedAt",
            "rawSn", "rawSn",
            "videoId", "rawSn");

    private final PortalUserWorkService portalUserWorkService;

    @Operation(summary = "내 작업 목록",
            description = "본인이 올린 업로드 자산(저작 여부와 무관하게 전부) + 본인 저작물(저장 라벨·메타 오버레이·"
                    + "이벤트 어노테이션 오버레이 중 하나라도)이 있는 데이터마트 영상을 한 목록으로 페이징 조회한다. "
                    + "행마다 자산 출처(assetSource)와 보존기간 만료 예정일(expiresOn)이 실리며, 만료는 행 출처에 따라 "
                    + "서로 다른 규칙으로 계산된다(저장하지 않는 파생값 — 캐시 금지). "
                    + "정렬(sort)은 allowlist(lastSavedAt, rawSn/videoId)만 허용하며 미등록 키·과다 항목은 400.")
    @GetMapping
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<Page<PortalUserWorkResponse>> listUserWorks(
            @PageableDefault(size = 20, sort = "lastSavedAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        return ApiResponse.ok(portalUserWorkService.listUserWorks(owner, capped(safeSort(pageable))));
    }

    private String requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return actor.sub();
    }

    /**
     * 정렬 키를 allowlist 로만 해석한다(CWE-89 / CWE-770 / CWE-209).
     *
     * <p>폴백은 {@link Sort#unsorted()} 다 — 리포지토리가 기본 정렬(마지막 저장 시각 내림차순 +
     * 식별자 tiebreak)을 고정 보유하므로 정렬을 비워도 순서가 정해진다.
     */
    private Pageable safeSort(Pageable pageable) {
        return SortAllowlist.apply(pageable, SORT_KEYS, Sort.unsorted());
    }

    /** 페이지 크기 하드캡 — {@value #MAX_PAGE_SIZE} 초과는 상한으로 클램프(API-225). */
    private Pageable capped(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }
}
