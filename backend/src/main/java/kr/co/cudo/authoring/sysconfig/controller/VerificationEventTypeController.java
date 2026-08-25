package kr.co.cudo.authoring.sysconfig.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.sysconfig.dto.VerificationEventQuestionsResponse;
import kr.co.cudo.authoring.sysconfig.dto.VerificationEventQuestionsUpdateRequest;
import kr.co.cudo.authoring.sysconfig.dto.VerificationEventTypeResponse;
import kr.co.cudo.authoring.sysconfig.service.VerificationEventTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 검증 이벤트 유형·질문 관리 API — 외부 URL = {@code /api/v1/manage/verification-event-types}
 * (context-path={@code /api}). [design: API-219 · API-220]
 *
 * <p>외부 시계열 분석의 <b>추가 질문 문장은 사업자 서버가 이벤트별로 관리</b>해 우리가 지정할 수도,
 * 응답으로 받을 수도 없다. 그런데 이벤트 어노테이션의 질문 칸은 채워져야 하므로 문구를 <b>저작도구가
 * 보관</b>하고 거기서 조달한다. 이 경로는 운영자가 그 목록을 확인하고 고치는 통로다.
 *
 * <h3>이벤트유형 관리 경로와 별개다</h3>
 * <p>{@code /v1/manage/event-types}(관제가 채번한 {@code EV…} 코드)와는 <b>코드 체계가 다르다</b>.
 * 한 목록으로 합치지 않고, 그 경로의 응답에 필드를 더하지도 않는다.
 *
 * <p>보안:
 * <ul>
 *   <li><b>REVIEWER 전용</b> — SecurityConfig {@code /v1/manage/**} 매처 + {@code @PreAuthorize}
 *       이중 방어.</li>
 *   <li>RequestBody 는 DTO 로 강제 — Mass Assignment 방어(CWE-915). 일련번호·정렬순서·감사 컬럼은
 *       요청으로 받지 않는다.</li>
 *   <li>경로변수는 컬럼 규격(코드V20)과 벤더 표기(소문자 스네이크)에 맞춰 형식·길이를 1차 검증한다
 *       (CWE-20).</li>
 *   <li>쿼리 파라미터로 행위를 분기하지 않는다 — 질문 편집은 <b>전체 교체 PUT 한 가지</b>다.</li>
 * </ul>
 */
@Tag(name = "VerificationEventType",
        description = "검증 이벤트 유형·질문 관리(외부 시계열 분석 질문 문구) — REVIEWER 전용.")
@RestController
@RequestMapping("/v1/manage/verification-event-types")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('REVIEWER')")
public class VerificationEventTypeController {

    private final VerificationEventTypeService service;

    @Operation(
            summary = "검증 이벤트 유형·질문 목록·관제 유형 짝 조회 (REVIEWER)",
            description = """
                    등록된 검증 이벤트 유형을 정렬순서 오름차순으로 반환하고, 각 유형에 그 유형의 질문 목록
                    (정렬순서 오름차순)과 관제 이벤트유형 코드 짝을 함께 싣는다.

                    관제 유형 짝은 인입 원장에 실려 온 값을 읽어 만든다(별도 매핑표를 두지 않는다).
                    한 검증 유형에 관제 코드가 여러 개 붙을 수 있고 짝이 없으면 빈 배열이다.
                    질문이 없는 유형도 목록에서 빠지지 않는다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping
    public ApiResponse<List<VerificationEventTypeResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(
            summary = "검증 이벤트 유형의 질문 목록 전체 교체 (REVIEWER)",
            description = """
                    한 유형의 질문 목록을 통째로 받아 교체한다. 받은 배열의 순서가 곧 정렬순서이며
                    첫 번째가 그 유형의 기본 질문이다. 추가·수정·삭제·순서 변경을 이 하나로 처리한다.

                    빈 배열을 보내면 그 유형의 질문이 없어지고, 그 유형은 어노테이션 질문 칸을 비운 채로 둔다.
                    질문 문구는 외부 사업자 요청 본문과 로그에 그대로 실리는 값이라 개행·제어문자를 허용하지 않고
                    길이 상한이 있으며, 하나라도 어긋나면 요청 전체를 거부한다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "교체 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "경로변수 형식 위반 또는 질문 문구 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "등록되지 않은 검증 이벤트 유형")
    })
    @PutMapping("/{vrfcEvntTypeCd}/questions")
    public ApiResponse<VerificationEventQuestionsResponse> replaceQuestions(
            @Parameter(description = "검증 이벤트 유형 코드", required = true, example = "car_accident")
            @PathVariable
            @Size(min = 1, max = 20, message = "검증 이벤트 유형 코드는 1~20자여야 합니다.")
            @Pattern(regexp = "^[a-z0-9_]+$",
                    message = "검증 이벤트 유형 코드 형식이 올바르지 않습니다.")
            String vrfcEvntTypeCd,
            @Valid @RequestBody VerificationEventQuestionsUpdateRequest request,
            @AuthenticationPrincipal TokenClaims claims) {
        return ApiResponse.ok(service.replaceQuestions(vrfcEvntTypeCd, request, claims));
    }
}
