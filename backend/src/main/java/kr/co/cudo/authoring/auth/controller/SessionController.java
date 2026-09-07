package kr.co.cudo.authoring.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.cudo.authoring.auth.dto.MeResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtAuthenticationFilter;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 세션 정보 조회.
 *
 * <h3>★ 무엇이 진실원인가 (@design API-006)</h3>
 * <ul>
 *   <li><b>역할</b> — 인가에 쓰는 값의 진실원은 <b>저작도구가 보관한 역할</b>({@code LS_USER_ROLE})이며
 *       인계 토큰의 {@code role} 클레임으로 정하지 않는다. 이 응답이 싣는 {@code role} 은
 *       {@link JwtAuthenticationFilter} 가 그 보관값으로 해석해 놓은 값이다.</li>
 *   <li><b>이름</b> — 화면 상단이 표시하는 이름의 진실원도 <b>이 응답</b>이고 토큰의 이름 클레임은
 *       <b>보조</b>다. 그래서 토큰에 이름이 실려 오지 않아도 저작도구가 아는 이름을 싣는다
 *       (아래 {@code me} 참조). 저작도구도 이름을 모를 때만 {@code null} 이며, 두 조달원 모두에서
 *       얻지 못했을 때만 화면이 대체 표기로 내려간다.</li>
 * </ul>
 *
 * @design API-006
 */
@Tag(name = "Session", description = "현재 사용자 세션 정보 — 관제/포털 채널의 JWT 를 검증하고 저작도구가 보관한 역할·이름을 함께 반환.")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class SessionController {

    /**
     * 사번 → 표시명 해석 <b>단일 헬퍼</b>. 토큰이 이름을 싣지 않았을 때의 보강 조달원이다.
     * 비숫자 사번은 예외가 아니라 {@code null} 로 처리하므로 파싱 방어를 여기서 따로 하지 않는다.
     */
    private final UserNameResolver userNameResolver;

    /**
     * 현재 인증된 사용자 정보 조회.
     *
     * <h3>이름 조달 순서 — 토큰 클레임 → 저작도구 보관값 (@design API-006 · AC-1098)</h3>
     * <p>구 구현은 <b>토큰 클레임 하나</b>만 봤다. 그래서 이름을 싣지 않는 인계 토큰으로 진입하면
     * 저작도구가 그 사람의 이름을 <b>알고 있는데도</b> 응답이 비어, 화면 상단이 대체 표기로 떨어졌다.
     * 확정 축은 그 반대다 — 이 응답이 진실원이고 토큰 클레임은 보조다.
     *
     * <p>★ 보강 조회는 <b>내부 채널로 한정</b>한다. 포털 토큰의 {@code sub} 는 문자열 식별자로
     * 쓰이고 사용자 마스터의 {@code USER_NO} 와의 매핑이 확인되지 않았다 — 추측으로 조인하면 남의
     * 행 이름을 표시하게 된다(CWE-639). 같은 이유로 최종로그인일시 기록도 내부 채널 한정이다.
     * ⇒ <b>포털 채널의 동작은 종전과 바이트 그대로 같다.</b>
     *
     * <p>★ 조회는 <b>토큰에 이름이 없을 때만</b> 돈다. 관제 인계 토큰은 이름 클레임을 실어 오므로
     * 통상 경로에서는 조회가 일어나지 않는다(진입 경로에 상시 쿼리를 더하지 않는다).
     *
     * <p>⚠ 이 창구는 여전히 <b>업무 데이터를 싣지 않는다</b> — {@code name} 은 호출자 <b>본인</b>의
     * 표시명이며 채널을 넘나드는 값이 아니다. {@code SecurityConfig} 의 {@code /v1/me} 예외 근거
     * (본인 정보만 반환)는 그대로 유효하다. 여기에 다른 사용자의 값이나 업무 데이터를 더하는 순간
     * 그 근거가 무효가 되므로 채널 게이트를 재검토해야 한다.
     */
    @Operation(
            summary = "현재 인증된 사용자 정보 조회",
            description = """
                    현재 세션 주체의 식별자(sub)와 진입 채널(INTERNAL/PORTAL), 그리고 저작도구가 보관한
                    역할(ADMIN/REVIEWER/WORKER/PORTAL_USER)과 이름을 반환한다.
                    인가에 쓰는 역할의 진실원은 인계 토큰의 role 클레임이 아니라 저작도구가 보관한 값이고,
                    화면 상단이 표시하는 이름의 진실원도 이 응답이며 토큰의 이름 클레임은 보조다 —
                    토큰에 이름이 실려 오지 않아도 저작도구가 아는 이름을 싣는다.
                    아직 역할이 부여되지 않았으면 role 은 null 이며, 이 null 은 확인해서 알아낸
                    「역할 없음」이지 확인하지 못한 상태가 아니다.
                    인증된 JWT 이면 역할과 무관하게 접근 가능하다(역할 미배정 사용자 포함).
                    """)
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "토큰 없음/만료/검증 실패")
    })
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TokenClaims claims)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Object nameAttr = request.getAttribute(JwtAuthenticationFilter.AUTH_NAME_ATTR);
        String name = nameAttr instanceof String s && !s.isBlank() ? s : null;
        if (name == null && claims.channel() == Channel.INTERNAL) {
            // 보관값도 공백이면 null 로 남긴다 — USER_NM 은 NOT NULL 이라 "모른다" 가 빈 문자열로
            //   적재된다. 그것을 이름으로 실으면 화면이 <빈 이름>을 그린다(대체 표기 판단이 막힌다).
            String stored = userNameResolver.resolveOne(claims.sub());
            name = (stored == null || stored.isBlank()) ? null : stored;
        }
        String role = claims.role() == null ? null : claims.role().name();
        String channel = claims.channel() == null ? null : claims.channel().name();
        return ApiResponse.ok(new MeResponse(claims.sub(), name, role, channel));
    }
}
