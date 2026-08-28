package kr.co.cudo.authoring.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.auth.dto.AdminSessionRequest;
import kr.co.cudo.authoring.auth.dto.AdminSessionResponse;
import kr.co.cudo.authoring.auth.service.AdminSessionService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 단기 유효창 컨트롤러 — <b>관리 기능 공통 진입</b>. [@design API-194] [@design ADR-046]
 *
 * <p>연동 서버 주소는 잘못 바꾸면 학습데이터가 통째로 외부로 나갈 수 있는 값이라, REVIEWER 권한만으로는
 * 열지 않고 <b>그 순간 패스워드를 다시 확인</b>한 짧은 창에서만 바꾸게 했다. 그 창의 적용 축이
 * <b>운영·관리 성격의 쓰기 전반</b>으로 넓어졌다 — 사용자 역할 변경 · 업로드 시작 · 관리자 자격 교체가
 * 그런 행위다(전수 목록이 아니라 예시이며 판정 기준은 「운영·관리 성격의 쓰기인가」 하나다).
 *
 * <p>⚠ 그래도 <b>조회는 검수자 권한만으로</b> 된다. 조회까지 묶으면 작업 배정 흐름이 끊긴다.
 *
 * <h3>왜 이 창구가 auth 패키지에 있나</h3>
 * <p>자격을 검증하고 발급하는 창구라 <b>자격을 소유한 패키지</b>에 둔다. 판정·발급·속도 제한
 * ({@code AdminPasswordVerifier} · {@code AdminSessionTokenService} · {@code RoleClaimRateLimiter})이
 * 모두 여기 있는데 창구만 설정 도메인에 홀로 있으면, 자격 축을 고칠 때 함께 봐야 할 것이 두 곳으로
 * 갈린다. ★<b>HTTP 경로는 바뀌지 않았다</b>({@code POST /v1/manage/admin-session}) — 패키지 이동일 뿐
 * 계약 변경이 아니다.
 */
@Tag(name = "Admin Session",
        description = "연동 서버 주소 변경을 위한 관리자 단기 유효창 — 패스워드 재확인 후 짧은 시간 동안만 열린다.")
@RestController
@RequestMapping("/v1/manage/admin-session")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AdminSessionController {

    private final AdminSessionService adminSessionService;

    @Operation(
            summary = "관리자 단기 유효창 개시 (ADMIN)",
            description = """
                    관리자 공유 패스워드를 확인하고 짧은 유효기간의 토큰을 발급한다.
                    이 토큰은 관리 성격의 쓰기(연동 서버 주소 저장·사용자 역할 변경 등)에
                    X-Admin-Session 헤더로 전송한다.
                    역할을 승격시키지 않으며, 유효창만으로는 어떤 관리 기능에도 닿지 못한다 —
                    관리자 역할이 1차 축이고 유효창은 그 위에 가산되는 2차 요건이다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증 또는 관리자 패스워드 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "시도 횟수 제한 초과")
    })
    /**
     * ★ <b>관리자 역할이 1차 축이다</b> — 유효창은 그 위에 가산되는 2차 요건이지 역할을 대신하지
     * 않는다. 검수자에게 이 창구를 열어 두면 <b>관리자 공유 패스워드를 아는 검수자</b>가 유효창을
     * 얻어 관리 성격의 쓰기(그리고 관리자 패스워드 교체 자체)에 닿는다.
     *
     * <p>⚠ 이 창구를 좁히면 <b>유효창을 소비하는 모든 창구가 함께 관리자 전용이 된다</b>(발급을
     * 못 받으면 소비도 못 한다). 소비처의 역할 게이트를 따로 올리지 않아도 실질 경계가 여기서
     * 정해지므로, 검수자가 계속 써야 하는 흐름이 새로 생기면 <b>이 창구가 아니라 그 흐름의 요구
     * 자체</b>를 재검토해야 한다.
     *
     * @design AC-072
     * @design ROLE-004
     * @design ADR-055
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AdminSessionResponse> open(@Valid @RequestBody AdminSessionRequest request,
                                                  @AuthenticationPrincipal TokenClaims claims) {
        return ApiResponse.ok(adminSessionService.open(request, claims));
    }
}
