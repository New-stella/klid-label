package kr.co.cudo.authoring.auth;

import kr.co.cudo.authoring.auth.dto.ControlTokenRefreshRequest;
import kr.co.cudo.authoring.auth.dto.ControlTokenRefreshResponse;
import kr.co.cudo.authoring.common.logging.LogMaskingPatterns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제 세션 중계의 토큰이 로그에 남지 않는다 — 회귀 가드 (@design API-247 · INT-015).
 *
 * <p>마스킹 규칙 자체는 공용({@link LogMaskingPatterns})이 이미 {@code token} 을 포함한 키와
 * {@code x-access-token:} 헤더·JWT 전문을 가린다. 이 시험은 <b>이번에 새로 생긴 필드 이름 각각</b>이
 * 그 규칙에 실제로 걸리는지를 값 축으로 고정한다 — 「기존 규칙이 있다」를 신규 필드 보호로 착각하지 않게.
 * 표본 값은 실행마다 새로 만든다(고정 문자열을 두지 않는다).
 */
class ControlTokenLogMaskingTest {

    private static final String PROBE = "probe" + UUID.randomUUID().toString().replace("-", "");

    /** JWT 모양 표본 — 헤더.본문.서명 3 세그먼트(값은 실행마다 다르다). */
    private static final String JWT_SHAPED = "eyJhbGciOiJIUzUxMiJ9." + "eyJ0eXBlIjoicmVmcmVzaCJ9." + PROBE;

    @ParameterizedTest
    @ValueSource(strings = {"refreshToken", "sessionToken", "accessToken", "refresh_token", "session_token"})
    @DisplayName("★JSON_본문의_토큰_필드는_값이_가려진다")
    void jsonTokenFieldsAreMasked(String field) {
        String masked = LogMaskingPatterns.mask("{\"" + field + "\":\"" + PROBE + "\"}");
        assertThat(masked).doesNotContain(PROBE);
    }

    @Test
    @DisplayName("★관제로_나가는_x_access_token_헤더_값이_가려진다")
    void outboundHeaderIsMasked() {
        assertThat(LogMaskingPatterns.mask("x-access-token: " + PROBE)).doesNotContain(PROBE);
        assertThat(LogMaskingPatterns.mask("x-access-token: " + JWT_SHAPED)).doesNotContain(PROBE);
    }

    @Test
    @DisplayName("요청_응답_DTO_의_toString_에_토큰이_실리지_않는다")
    void dtoToStringHidesTokens() {
        assertThat(new ControlTokenRefreshRequest(PROBE).toString()).doesNotContain(PROBE);
        assertThat(new ControlTokenRefreshResponse(PROBE, JWT_SHAPED).toString()).doesNotContain(PROBE);
    }
}
