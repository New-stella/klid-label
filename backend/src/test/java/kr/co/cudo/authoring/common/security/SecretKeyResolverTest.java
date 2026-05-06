package kr.co.cudo.authoring.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretKeyResolverTest {

    @Test
    @DisplayName("JWT_시크릿이_32바이트_미만이면_IllegalArgumentException")
    void rejectsShortInput() {
        String shortInput = "x".repeat(16);
        assertThatThrownBy(() -> new SecretKeyResolver(shortInput))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("JWT_시크릿이_32바이트_이상이면_정상_생성")
    void acceptsInputAtLeast32Bytes() {
        String longInput = "a".repeat(32);
        SecretKeyResolver resolver = new SecretKeyResolver(longInput);
        assertThat(resolver.resolve()).isNotNull();
    }
}
