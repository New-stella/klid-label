package kr.co.cudo.authoring.eventtype.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.eventtype.dto.EventTypeResponse;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 이벤트 타입 조회 API (Phase 2). 외부 URL = {@code /api/v1/event-types} (context-path={@code /api}).
 *
 * <p>이벤트 타입은 필터 드롭다운·라벨 표시에 공통 필요하므로 별도 역할 제한 없이 <b>인증된 내부 사용자
 * (REVIEWER/WORKER)</b> 면 접근 가능하다. SecurityConfig 의 {@code /v1/**} (CHANNEL_INTERNAL +
 * authenticated) 매처가 적용되며, 포털 채널(PORTAL_USER) 토큰은 채널 격리로 차단된다.
 *
 * <p>엔티티를 직접 노출하지 않고 {@link EventTypeResponse}/코드-라벨 맵 DTO 만 반환한다.
 * 입력 파라미터가 없는 GET 이므로 별도 입력 검증 표면이 없다.
 */
@Tag(name = "EventType", description = "이벤트 타입 필터 옵션·라벨 매핑 조회 (인증된 내부 사용자).")
@RestController
@RequestMapping("/v1/event-types")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class EventTypeController {

    private final EventTypeService eventTypeService;

    @Operation(summary = "이벤트 타입 필터 옵션 조회 — CLCT_YN='Y' AND 대분류!='08' 카테고리 dedup")
    @GetMapping
    public ApiResponse<List<EventTypeResponse>> filterOptions() {
        return ApiResponse.ok(eventTypeService.filterOptions());
    }

    @Operation(summary = "이벤트 코드→한글 라벨 맵 조회 — 전체 코드 라벨 해석")
    @GetMapping("/labels")
    public ApiResponse<Map<String, String>> labels() {
        return ApiResponse.ok(eventTypeService.codeLabelMap());
    }
}
