package kr.co.cudo.authoring.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 단기 유효창 토큰 (R11).
 *
 * <p>핵심은 셋이다 — <b>유효성 판정을 서버가 소유</b>하는가, <b>다른 노드에서도 통하는가</b>,
 * <b>인증 토큰으로 오인될 수 없는가</b>.
 */
class AdminSessionTokenServiceTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "admin-session-test-signing-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final JwtKeyResolver RESOLVER = () -> KEY;

    private static AdminSessionTokenService service(long ttlMinutes) {
        return new AdminSessionTokenService(RESOLVER, ttlMinutes);
    }

    @Test
    @DisplayName("발급한_토큰은_유효기간_안에서_통과한다")
    void issuedTokenVerifies() {
        AdminSessionTokenService service = service(10);
        Instant now = Instant.parse("2026-08-10T10:00:00Z");

        AdminSessionTokenService.Issued issued = service.issue("1001", now);

        assertThat(issued.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(10)));
        assertThatCode(() -> service.verify(issued.token(), "1001", now.plusSeconds(300)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★만료된_토큰은_403 — 클라이언트가_유효기간을_스스로_정할_수_없다")
    void expiredTokenRejected() {
        AdminSessionTokenService service = service(10);
        Instant now = Instant.parse("2026-08-10T10:00:00Z");
        AdminSessionTokenService.Issued issued = service.issue("1001", now);

        assertThatThrownBy(() -> service.verify(issued.token(), "1001", now.plus(Duration.ofMinutes(10))))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("★만료_시각을_고쳐도_통하지_않는다 — 서명_대상에_들어_있다")
    void tamperedExpiryRejected() {
        AdminSessionTokenService service = service(10);
        Instant now = Instant.parse("2026-08-10T10:00:00Z");
        String token = service.issue("1001", now).token();

        // 페이로드만 미래로 바꿔치기한다(서명은 원본 그대로).
        String forgedPayload = "v1|1001|" + now.plus(Duration.ofDays(1)).getEpochSecond();
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                + token.substring(token.indexOf('.'));

        assertThatThrownBy(() -> service.verify(forged, "1001", now.plusSeconds(60)))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("★다른_사용자의_토큰은_통하지_않는다 — 유효창이_공유되지_않는다")
    void tokenIsBoundToSubject() {
        AdminSessionTokenService service = service(10);
        Instant now = Instant.now();
        String token = service.issue("1001", now).token();

        assertThatThrownBy(() -> service.verify(token, "2002", now))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("★2노드 — 다른_노드가_발급한_토큰도_통한다 (무상태 서명)")
    void verifiesAcrossNodes() {
        // 서로 다른 인스턴스 = 서로 다른 노드. 공유하는 것은 서명 키(설정)뿐이다.
        AdminSessionTokenService nodeA = service(10);
        AdminSessionTokenService nodeB = service(10);
        Instant now = Instant.now();

        String token = nodeA.issue("1001", now).token();

        assertThatCode(() -> nodeB.verify(token, "1001", now.plusSeconds(60)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("다른_서명키로_만든_토큰은_통하지_않는다")
    void rejectsForeignSignature() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-signing-key-that-is-long-enough-0123456789".getBytes(StandardCharsets.UTF_8));
        AdminSessionTokenService ours = service(10);
        AdminSessionTokenService theirs = new AdminSessionTokenService(() -> otherKey, 10);
        Instant now = Instant.now();

        String foreign = theirs.issue("1001", now).token();

        assertThatThrownBy(() -> ours.verify(foreign, "1001", now))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("★인증_JWT를_이_자리에_넣어도_통하지_않는다 — 서명키가_분리돼_있다")
    void authJwtIsNotAnAdminSessionToken() {
        AdminSessionTokenService service = service(10);
        Instant now = Instant.now();
        String authJwt = Jwts.builder()
                .subject("1001")
                .claim("role", "REVIEWER")
                .expiration(java.util.Date.from(now.plusSeconds(3600)))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> service.verify(authJwt, "1001", now))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("★관리자_세션_토큰은_인증_JWT로_통하지_않는다 — 반대_방향도_막힌다")
    void adminSessionTokenIsNotAValidAuthJwt() {
        AdminSessionTokenService service = service(10);
        String token = service.issue("1001", Instant.now()).token();

        assertThatThrownBy(() -> Jwts.parser().verifyWith(KEY).build().parseSignedClaims(token))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("★토큰에_역할_클레임이_없다 — 권한을_승격시키지_않는다")
    void tokenCarriesNoRole() {
        AdminSessionTokenService service = service(10);
        String token = service.issue("1001", Instant.now()).token();

        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);

        assertThat(payload).doesNotContain("REVIEWER").doesNotContain("role");
        assertThat(payload.split("\\|")).hasSize(3);   // 버전 | subject | 만료
    }

    @Test
    @DisplayName("★유효기간_상한(30분)은_설정으로도_넘을_수_없다")
    void ttlIsCappedAtThirtyMinutes() {
        AdminSessionTokenService overTheCap = service(600);   // 10시간 요청

        assertThat(overTheCap.ttl()).isEqualTo(AdminSessionTokenService.MAX_TTL);
        assertThat(AdminSessionTokenService.MAX_TTL).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("유효기간_설정이_0이하면_기본값(10분)으로_되돌린다 — 유효창이_0이면_기능이_죽는다")
    void nonPositiveTtlFallsBackToDefault() {
        assertThat(service(0).ttl()).isEqualTo(AdminSessionTokenService.DEFAULT_TTL);
        assertThat(service(-5).ttl()).isEqualTo(AdminSessionTokenService.DEFAULT_TTL);
        assertThat(AdminSessionTokenService.DEFAULT_TTL).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("토큰이_없거나_형식이_깨졌으면_403 — 사유를_구분해_알리지_않는다 (CWE-209)")
    void malformedTokenRejectedUniformly() {
        AdminSessionTokenService service = service(10);
        Instant now = Instant.now();

        for (String bad : new String[]{null, "", "   ", "no-dot", ".", "a.", "!!!.!!!"}) {
            assertThatThrownBy(() -> service.verify(bad, "1001", now))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
        }
    }
}
