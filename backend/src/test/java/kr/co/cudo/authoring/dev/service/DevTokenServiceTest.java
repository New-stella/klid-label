package kr.co.cudo.authoring.dev.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.dev.dto.DevTokenRequest;
import kr.co.cudo.authoring.dev.dto.DevTokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevTokenServiceTest {

    /**
     * 테스트용 256bit 키 — 매 테스트 클래스 인스턴스마다 SecureRandom 으로 동적 생성.
     * 정적 시크릿 패턴(secret-filter 훅) 우회 + Fortify "Hardcoded Password" 방지.
     */
    private DevTokenService service;
    private SecretKey key;
    private String secretMaterial;

    @BeforeEach
    void setUp() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        secretMaterial = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secretMaterial.getBytes(StandardCharsets.UTF_8));
        JwtKeyResolver resolver = () -> key;
        service = new DevTokenService(resolver, List.of("klid-auth", "klid", "klid-portal"));
    }

    @Test
    @DisplayName("REVIEWER_INTERNAL_토큰_발급_성공_+_HS256_검증_통과")
    void issueReviewerInternalToken() {
        DevTokenRequest req = new DevTokenRequest(Role.REVIEWER, Channel.INTERNAL, null, null, 3600);

        DevTokenResponse res = service.issue(req);

        assertThat(res.token()).isNotBlank();
        assertThat(res.tokenType()).isEqualTo("Bearer");
        assertThat(res.authorizationHeader()).isEqualTo("Bearer " + res.token());
        assertThat(res.expiresAt()).isPositive();

        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(res.token());
        Claims claims = jws.getPayload();
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(claims.get("role", String.class)).isEqualTo("REVIEWER");
        assertThat(claims.get("channel", String.class)).isEqualTo("INTERNAL");
        assertThat(claims.getIssuer()).isEqualTo("klid-auth");
        assertThat(claims.getSubject()).isEqualTo("1001");
    }

    @Test
    @DisplayName("WORKER_INTERNAL_토큰_발급_+_role_claim_정확")
    void issueWorkerInternalToken() {
        DevTokenRequest req = new DevTokenRequest(Role.WORKER, Channel.INTERNAL, null, null, null);

        DevTokenResponse res = service.issue(req);

        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(res.token()).getPayload();
        assertThat(claims.get("role", String.class)).isEqualTo("WORKER");
        assertThat(claims.get("channel", String.class)).isEqualTo("INTERNAL");
        assertThat(claims.getSubject()).isEqualTo("1002");
    }

    @Test
    @DisplayName("PORTAL_USER_PORTAL_토큰_발급_+_channel_claim_정확")
    void issuePortalUserPortalToken() {
        DevTokenRequest req = new DevTokenRequest(Role.PORTAL_USER, Channel.PORTAL, null, "포털A", 1800);

        DevTokenResponse res = service.issue(req);

        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(res.token()).getPayload();
        assertThat(claims.get("role", String.class)).isEqualTo("PORTAL_USER");
        assertThat(claims.get("channel", String.class)).isEqualTo("PORTAL");
        assertThat(claims.get("name", String.class)).isEqualTo("포털A");
        assertThat(claims.getSubject()).isEqualTo("2001");
    }

    @Test
    @DisplayName("PORTAL_USER_INTERNAL_조합은_INVALID_INPUT_400")
    void portalUserWithInternalChannelRejected() {
        DevTokenRequest req = new DevTokenRequest(Role.PORTAL_USER, Channel.INTERNAL, null, null, null);

        assertThatThrownBy(() -> service.issue(req))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                });
    }

    @Test
    @DisplayName("REVIEWER_PORTAL_조합은_INVALID_INPUT_400")
    void reviewerWithPortalChannelRejected() {
        DevTokenRequest req = new DevTokenRequest(Role.REVIEWER, Channel.PORTAL, null, null, null);

        assertThatThrownBy(() -> service.issue(req))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("WORKER_PORTAL_조합은_INVALID_INPUT_400")
    void workerWithPortalChannelRejected() {
        DevTokenRequest req = new DevTokenRequest(Role.WORKER, Channel.PORTAL, null, null, null);

        assertThatThrownBy(() -> service.issue(req))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("expSeconds_60미만_서비스_단계_가드")
    void expSecondsTooLowGuardedAtService() {
        // Bean Validation 우회 시나리오 — 서비스 레벨 이중 가드 검증
        DevTokenRequest req = new DevTokenRequest(Role.REVIEWER, Channel.INTERNAL, null, null, 30);

        assertThatThrownBy(() -> service.issue(req))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("expSeconds_86400초과_서비스_단계_가드")
    void expSecondsTooHighGuardedAtService() {
        DevTokenRequest req = new DevTokenRequest(Role.REVIEWER, Channel.INTERNAL, null, null, 100000);

        assertThatThrownBy(() -> service.issue(req))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("userNo_미지정시_역할별_기본값")
    void userNoDefaultsByRole() {
        DevTokenResponse rev = service.issue(new DevTokenRequest(Role.REVIEWER, Channel.INTERNAL, null, null, null));
        DevTokenResponse wrk = service.issue(new DevTokenRequest(Role.WORKER, Channel.INTERNAL, null, null, null));
        DevTokenResponse prt = service.issue(new DevTokenRequest(Role.PORTAL_USER, Channel.PORTAL, null, null, null));

        assertThat(Jwts.parser().verifyWith(key).build().parseSignedClaims(rev.token()).getPayload().getSubject())
                .isEqualTo("1001");
        assertThat(Jwts.parser().verifyWith(key).build().parseSignedClaims(wrk.token()).getPayload().getSubject())
                .isEqualTo("1002");
        assertThat(Jwts.parser().verifyWith(key).build().parseSignedClaims(prt.token()).getPayload().getSubject())
                .isEqualTo("2001");
    }

    @Test
    @DisplayName("userNo_지정시_지정값_사용")
    void userNoExplicitValueUsed() {
        DevTokenRequest req = new DevTokenRequest(Role.REVIEWER, Channel.INTERNAL, "9999", null, null);

        DevTokenResponse res = service.issue(req);

        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(res.token()).getPayload();
        assertThat(claims.getSubject()).isEqualTo("9999");
    }

    @Test
    @DisplayName("응답_claims_맵에_secret_미포함")
    void responseClaimsDoesNotLeakSecret() {
        DevTokenResponse res = service.issue(new DevTokenRequest(Role.REVIEWER, Channel.INTERNAL, null, null, null));

        // claims 와 응답 어디에도 secret 문자열 미포함
        assertThat(res.claims()).doesNotContainKey("secret");
        assertThat(res.claims()).doesNotContainKey("key");
        String dump = res.toString();
        assertThat(dump).doesNotContain(secretMaterial);
    }

    @Test
    @DisplayName("expSeconds_null_시_기본값_3600")
    void expSecondsDefaultsTo3600() {
        DevTokenResponse res = service.issue(new DevTokenRequest(Role.REVIEWER, Channel.INTERNAL, null, null, null));

        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(res.token()).getPayload();
        long ttlSec = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(ttlSec).isEqualTo(3600);
    }
}
