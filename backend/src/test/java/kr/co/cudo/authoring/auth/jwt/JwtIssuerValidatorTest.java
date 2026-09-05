package kr.co.cudo.authoring.auth.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtIssuerValidatorTest {

    @Test
    @DisplayName("allowed_issuers에_없는_iss는_검증_실패")
    void rejectsUnknownIssuer() {
        JwtIssuerValidator validator = new JwtIssuerValidator(List.of("klid", "klid-portal"));
        assertFalse(validator.isAllowed("evil-issuer"));
    }

    @Test
    @DisplayName("iss_클레임이_없으면_통과_관제_인계_토큰")
    void allowsAbsentIssuer() {
        // @design ADR-063 — 관제 인계 JWT 는 iss 클레임이 없다(null). 이 경우만 연다.
        //   시크릿 서명검증·exp 는 필터가 그대로 강제하므로 iss 완화가 위조·만료를 열지 않는다.
        JwtIssuerValidator validator = new JwtIssuerValidator(List.of("klid", "klid-portal"));
        assertTrue(validator.isAllowed(null));
    }

    @Test
    @DisplayName("blank_iss는_거부_부재와_구분")
    void rejectsBlankIssuer() {
        // 「iss 없음 허용」과 「아무 iss 허용」은 다르다 — 값을 실었는데 비운 것(blank)은 거부한다.
        JwtIssuerValidator validator = new JwtIssuerValidator(List.of("klid", "klid-portal"));
        assertFalse(validator.isAllowed(""));
        assertFalse(validator.isAllowed("   "));
    }

    @Test
    @DisplayName("klid_및_klid_portal_iss는_검증_통과")
    void acceptsAllowedIssuers() {
        JwtIssuerValidator validator = new JwtIssuerValidator(List.of("klid", "klid-portal"));
        assertTrue(validator.isAllowed("klid"));
        assertTrue(validator.isAllowed("klid-portal"));
    }
}
