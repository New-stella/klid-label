package kr.co.cudo.authoring.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenClaims.roleName() null-safe 동작 검증.
 *
 * <p>역할 분리 Phase 3 이후 INTERNAL 사용자가 LS_USER_ROLE 미배정이면 role 이 null 일 수 있어,
 * {@code role().name()} 직접 호출의 NPE 를 막는 null-safe 접근자가 정확히 동작해야 한다.
 */
class TokenClaimsTest {

    @Test
    @DisplayName("roleName_role이_null이면_null_반환")
    void roleNameNullWhenRoleNull() {
        TokenClaims claims = new TokenClaims("1", null, Channel.INTERNAL, Instant.now());
        assertThat(claims.roleName()).isNull();
    }

    @Test
    @DisplayName("roleName_role이_있으면_enum이름_반환")
    void roleNameReturnsEnumName() {
        TokenClaims claims = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now());
        assertThat(claims.roleName()).isEqualTo("REVIEWER");
    }
}
