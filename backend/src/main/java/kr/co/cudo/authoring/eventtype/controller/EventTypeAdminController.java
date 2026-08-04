package kr.co.cudo.authoring.eventtype.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.eventtype.dto.EventTypeAdminResponse;
import kr.co.cudo.authoring.eventtype.dto.EventTypeUpdateRequest;
import kr.co.cudo.authoring.eventtype.service.EventTypeAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 이벤트유형 관리 API — 외부 URL = {@code /api/v1/manage/event-types} (context-path={@code /api}).
 *
 * <p>자동등록({@code LS_EVNT_TYPE} — 인입 소비 시점)된 유형의 <b>이벤트명·수집여부를 정정</b>한다.
 * 자동등록은 {@code ON CONFLICT DO NOTHING} 이라 기존 행을 절대 갱신하지 않으므로, <b>이 API 가
 * 없으면 최초 등록값이 영구 고착</b>한다.
 *
 * <p><b>생성·삭제 API 를 두지 않는다</b> — 등록의 유일한 출처는 관제 인입이라는 확정 설계 때문이다
 * (화면에 없는 유형은 관제가 보낸 적이 없는 유형이며, 지우면 그 유형의 영상이 라벨을 잃는다).
 *
 * <p>보안:
 * <ul>
 *   <li><b>REVIEWER 전용</b> — SecurityConfig {@code /v1/manage/**} 매처 + {@code @PreAuthorize}
 *       이중 방어. 조회 API({@code /v1/event-types})와 경로를 분리한 이유가 이 인가 경계다.</li>
 *   <li>RequestBody 는 DTO({@link EventTypeUpdateRequest})로 강제 — Mass Assignment 방어
 *       (CWE-915). PK·분류코드·등록일시는 요청으로 받지 않는다.</li>
 *   <li>경로변수 {@code evntTypeCd} 는 컬럼 규격(코드V20)에 맞춰 형식·길이를 1차 검증한다(CWE-20).</li>
 *   <li>쿼리 파라미터로 행위를 분기하지 않는다 — 수정은 PATCH 한 가지다({@code api-design.md}).</li>
 * </ul>
 */
@Tag(name = "EventTypeAdmin", description = "이벤트유형 관리(이름·수집여부 정정) — REVIEWER 전용.")
@RestController
@RequestMapping("/v1/manage/event-types")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('REVIEWER')")
public class EventTypeAdminController {

    private final EventTypeAdminService eventTypeAdminService;

    @Operation(summary = "등록된 이벤트유형 전체 조회 — 비수집·제외 대분류 포함(관리 화면용)")
    @GetMapping
    public ApiResponse<List<EventTypeAdminResponse>> list() {
        return ApiResponse.ok(eventTypeAdminService.list());
    }

    @Operation(summary = "이벤트유형 부분 수정 — 이벤트명·수집여부(null 은 미변경)")
    @PatchMapping("/{evntTypeCd}")
    public ApiResponse<EventTypeAdminResponse> update(
            @PathVariable
            @Size(min = 1, max = 20, message = "이벤트유형코드는 1~20자여야 합니다.")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                    message = "이벤트유형코드 형식이 올바르지 않습니다.")
            String evntTypeCd,
            @Valid @RequestBody EventTypeUpdateRequest request) {
        return ApiResponse.ok(eventTypeAdminService.update(evntTypeCd, request));
    }
}
