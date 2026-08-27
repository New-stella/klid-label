package kr.co.cudo.authoring.common.security.adminsession;

import jakarta.servlet.http.HttpServletRequest;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 관리자 단기 유효창 <b>게이트 단일 지점</b> — 「호출처마다 배선하면 샌다」의 대응. [@design ADR-046]
 *
 * <h3>이 클래스가 하는 일과 하지 않는 일</h3>
 * <p>하는 일은 <b>헤더를 읽어 검증자에게 넘기는 것</b>뿐이다. 유효성 판정 자체
 * (서명·만료·subject 결박)는 {@link AdminSessionTokenService} 가 그대로 소유한다 —
 * 여기에 판정을 복제하면 두 번째 진실원이 생겨 한쪽만 갱신되는 순간 한쪽 통로가 헐거워진다.
 *
 * <h3>두 갈래 진입점</h3>
 * <ul>
 *   <li><b>선언적</b> — {@link RequiresAdminSession} + {@link AdminSessionInterceptor}.
 *       창구 전체가 유효창을 요구할 때.</li>
 *   <li><b>프로그램적</b> — {@link #require(String, String)}. 요구가 <b>요청 내용에 따라 갈리는</b>
 *       창구에서 쓴다. 시스템 설정 저장이 그 경우이며, 어느 설정 키에 요구가 걸리는지의 판정은
 *       그 도메인({@code IntegrationEndpoint})이 계속 소유한다 — 이 게이트로 옮기면 게이트가
 *       도메인 규칙을 알게 되어 판정이 두 곳으로 흩어진다.</li>
 * </ul>
 *
 * <h3>거부는 한 종류다</h3>
 * <p>유효창 없음·만료·위조·타인 토큰이 <b>모두 같은 403·같은 문구</b>로 떨어진다(검증자가 그렇게
 * 만든다). 사유를 구분해 알리면 응답 자체가 상태 오라클이 된다(CWE-209).
 */
@Component
@RequiredArgsConstructor
public class AdminSessionGate {

    /**
     * 관리자 단기 유효창 토큰 헤더 — <b>이름의 단일 진실원</b>.
     *
     * <p>값을 <b>바디가 아니라 헤더</b>로 받는 이유는 설정 값과 인증 자격이 한 구조에 섞이면
     * 로그·검증 경로마다 자격증명이 딸려 다니기 때문이다.
     */
    public static final String HEADER = "X-Admin-Session";

    private final AdminSessionTokenService tokenService;

    /**
     * 프로그램적 진입점 — 토큰과 대상 subject 를 직접 받아 판정한다.
     *
     * @param adminSessionToken 관리자 유효창 토큰(없으면 {@code null} 가능 — 그대로 거부된다)
     * @param subject           토큰이 결박된 사용자(JWT sub)
     * @throws kr.co.cudo.authoring.common.exception.CustomException 유효창이 없거나 유효하지 않으면 403
     */
    public void require(String adminSessionToken, String subject) {
        tokenService.verify(adminSessionToken, subject, Instant.now());
    }

    /**
     * 선언적 진입점 — 현재 요청의 헤더와 <b>인증 주체</b>로 판정한다.
     *
     * <p>subject 를 요청이 아니라 {@code SecurityContext} 에서 읽는 것이 핵심이다. 요청이 실어 보낸
     * 값을 쓰면 남의 토큰에 그 사람의 subject 를 붙여 제시하는 것만으로 결박이 무력화된다.
     * 인증 주체를 알 수 없으면 {@code null} 을 넘겨 <b>거부 쪽으로</b> 떨어뜨린다(fail-closed).
     */
    public void requireForRequest(HttpServletRequest request) {
        require(request.getHeader(HEADER), currentSubject());
    }

    private static String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TokenClaims claims)) {
            return null;
        }
        return claims.sub();
    }
}
