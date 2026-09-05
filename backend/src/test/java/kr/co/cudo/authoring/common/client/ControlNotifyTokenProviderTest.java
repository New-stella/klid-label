package kr.co.cudo.authoring.common.client;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-063 ⑥ — 관제 outbound 통지용 서비스 토큰 발급기 단위 시험.
 *
 * <p>핵심 회귀 가드: <b>키가 HS512 를 자동선택할 만큼 큰(91바이트)데도 alg 는 HS256 이어야 한다</b>.
 * jjwt 0.12 는 {@code signWith(key)} 단독 호출 시 키 크기로 알고리즘을 자동 선택하므로, 관제 규칙(HS256)에
 * 맞추려면 {@code Jwts.SIG.HS256} 명시가 필수다. 그 명시가 빠지면 이 시험이 HS512 로 잡아낸다.
 */
class ControlNotifyTokenProviderTest {

    // 실제 JWT_SECRET 과 같은 91바이트 — signWith(key) 단독이면 HS512 가 자동 선택되는 크기다.
    private static final String SECRET_91B =
            "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890";
    private final SecretKey key = Keys.hmacShaKeyFor(SECRET_91B.getBytes(StandardCharsets.UTF_8));
    private final JwtKeyResolver keyResolver = () -> key;

    private Jws<Claims> parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }

    @Test
    @DisplayName("발급된_토큰의_alg는_HS256이다 — 91바이트_키의_HS512_자동선택을_명시로_눌렀다")
    void issuesHs256NotHs512() {
        ControlNotifyTokenProvider provider =
                new ControlNotifyTokenProvider(keyResolver, "klid-auth", "klid-authoring-notify", 300);

        String token = provider.issue();

        assertThat(token).isNotNull();
        Jws<Claims> jws = parse(token);
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
    }

    @Test
    @DisplayName("iss와_sub가_설정값으로_실리고_exp가_미래다")
    void carriesIssuerSubjectAndFutureExp() {
        ControlNotifyTokenProvider provider =
                new ControlNotifyTokenProvider(keyResolver, "klid-auth", "klid-authoring-notify", 300);

        Claims claims = parse(provider.issue()).getPayload();

        assertThat(claims.getIssuer()).isEqualTo("klid-auth");
        assertThat(claims.getSubject()).isEqualTo("klid-authoring-notify");
        assertThat(claims.getExpiration().toInstant()).isAfter(Instant.now());
        assertThat(claims.getIssuedAt()).isNotNull();
    }

    @Test
    @DisplayName("설정_가능한_iss_sub가_반영된다")
    void honorsConfiguredIssuerSubject() {
        ControlNotifyTokenProvider provider =
                new ControlNotifyTokenProvider(keyResolver, "custom-iss", "custom-sub", 120);

        Claims claims = parse(provider.issue()).getPayload();

        assertThat(claims.getIssuer()).isEqualTo("custom-iss");
        assertThat(claims.getSubject()).isEqualTo("custom-sub");
    }

    @Test
    @DisplayName("매_발급마다_토큰_문자열이_다르다 — jti로_같은_초_발급도_구분된다")
    void issuesDistinctTokensEachCall() {
        ControlNotifyTokenProvider provider =
                new ControlNotifyTokenProvider(keyResolver, "klid-auth", "klid-authoring-notify", 300);

        assertThat(provider.issue()).isNotEqualTo(provider.issue());
    }

    @Test
    @DisplayName("키가_없으면_발급불가이며_null을_돌려준다 — fail-safe")
    void returnsNullWhenNoKey() {
        ControlNotifyTokenProvider provider =
                new ControlNotifyTokenProvider((JwtKeyResolver) null, "klid-auth", "klid-authoring-notify", 300);

        assertThat(provider.canIssue()).isFalse();
        assertThat(provider.issue()).isNull();
    }

    @Test
    @DisplayName("ttl_오설정은_기본값으로_대체되고_상한을_넘으면_clamp된다")
    void clampsTtl() {
        // 0·음수 → 기본 300
        assertThat(new ControlNotifyTokenProvider(keyResolver, "klid-auth", "s", 0).ttlSeconds())
                .isEqualTo(300);
        assertThat(new ControlNotifyTokenProvider(keyResolver, "klid-auth", "s", -5).ttlSeconds())
                .isEqualTo(300);
        // 상한 3600 초과 → clamp
        assertThat(new ControlNotifyTokenProvider(keyResolver, "klid-auth", "s", 999999).ttlSeconds())
                .isEqualTo(3600);
        // 정상 범위는 그대로
        assertThat(new ControlNotifyTokenProvider(keyResolver, "klid-auth", "s", 120).ttlSeconds())
                .isEqualTo(120);
    }
}
