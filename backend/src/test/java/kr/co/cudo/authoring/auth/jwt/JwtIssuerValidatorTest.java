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
        assertFalse(validator.isAllowed(null));
        assertFalse(validator.isAllowed(""));
    }

    @Test
    @DisplayName("klid_및_klid_portal_iss는_검증_통과")
    void acceptsAllowedIssuers() {
        JwtIssuerValidator validator = new JwtIssuerValidator(List.of("klid", "klid-portal"));
        assertTrue(validator.isAllowed("klid"));
        assertTrue(validator.isAllowed("klid-portal"));
    }
}
