package kr.co.cudo.authoring.auth.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtIssuerValidator {

    private final Set<String> allowed;

    public JwtIssuerValidator(@Value("${authoring.jwt.allowed-issuers:klid,klid-portal,klid-auth}") List<String> allowedIssuers) {
        this.allowed = allowedIssuers.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 발급처(iss) 게이트 — 관제 인계 JWT 는 iss 클레임이 <b>없다</b>(@design ADR-063 · UC-041).
     *
     * <ul>
     *   <li>{@code null}(클레임 부재) → 허용. 관제 인계 토큰이 여기로 온다.</li>
     *   <li>{@code blank}(빈 문자열) → 거부 — 값을 실었는데 비운 것은 부재와 구분한다.
     *       「iss 없음 허용」과 「아무 iss 허용」은 다르며 전자만 연다.</li>
     *   <li>값 존재 → 허용목록에 있을 때만 허용(기존 그대로).</li>
     * </ul>
     *
     * <p>⚠ iss 를 완화해도 발급자에 대한 방어가 사라지지 않는다 — 시크릿 서명검증과 exp(만료)
     * 필수는 {@link kr.co.cudo.authoring.common.security.JwtAuthenticationFilter} 가 그대로
     * 강제하며, iss 완화 후 발급자에 대해 남는 방어가 그 둘이다.
     */
    public boolean isAllowed(String issuer) {
        if (issuer == null) {
            return true;
        }
        if (issuer.isBlank()) {
            return false;
        }
        return allowed.contains(issuer);
    }
}
