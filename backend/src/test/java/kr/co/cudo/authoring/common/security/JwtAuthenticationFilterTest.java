package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.StubControllers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class JwtAuthenticationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    private SecretKey key() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            return Keys.hmacShaKeyFor(padded);
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    @Test
    @DisplayName("유효한_JWT로_요청시_LS역할_REVIEWER로_해석되어_보호엔드포인트_200")
    void validJwtSetsContext() throws Exception {
        // Phase 3 — INTERNAL 인가 역할은 LS_USER_ROLE(sub=1 → REVIEWER)에서 해석된다.
        // JWT role 클레임은 인가 미사용이므로 관제 역할(LEARN_MANAGER)을 실어도 LS 가 진실원.
        // REVIEWER 전용 핸들러가 200 + 본문을 반환 → role 이 실제 REVIEWER 로 해석됨을 입증한다.
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("1")
                .issuer(issuer)
                .claim("role", "LEARN_MANAGER")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("manage-ok"));
    }

    @Test
    @DisplayName("JWT_없이_보호된_API_호출시_401_반환")
    void noJwtReturns401() throws Exception {
        mockMvc.perform(get("/v1/manage/test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효하지않은_channel_클레임은_401")
    void invalidChannelClaimReturns401() throws Exception {
        // channel="BOGUS" → Channel.valueOf 예외 → filter catch → 미인증 → 보호 엔드포인트 401
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("1")
                .issuer(issuer)
                .claim("role", "REVIEWER")
                .claim("channel", "BOGUS")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("LS에_관제역할코드_사용자는_enum불일치_403")
    void controlRoleCodeEnumMismatchReturns403() throws Exception {
        // V9001 시드: 770002 → LEARN_MANAGER (저작도구 Role enum 에 없음).
        // enum 불일치 → role null(fail-closed). CHANNEL_INTERNAL 만 부여 → REVIEWER 엔드포인트 403.
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("770002")
                .issuer(issuer)
                .claim("role", "REVIEWER")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("공백_sub는_fail_closed_403")
    void blankSubFailsClosed() throws Exception {
        // sub=" " → filter parseUserNo null → role null. CHANNEL_INTERNAL 만 부여 → 403.
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(" ")
                .issuer(issuer)
                .claim("role", "REVIEWER")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("만료된_JWT로_요청시_401_반환")
    void expiredJwtReturns401() throws Exception {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("user-1")
                .issuer(issuer)
                .claim("role", "REVIEWER")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("exp_클레임이_없는_JWT는_401_반환")
    void tokenWithoutExpClaimReturns401() throws Exception {
        // A-ISSUE-01 (HIGH, CWE-613) — jjwt 는 exp 가 "없으면" 만료 검사를 건너뛴다. 저작도구는
        // 토큰을 발급하지도, 폐기하지도 못하므로 exp 부재 토큰은 영구 유효한 자격증명이 된다.
        // given: 서명·issuer·channel 은 모두 유효하고 exp 클레임만 없는 토큰
        String token = Jwts.builder()
                .subject("1")
                .issuer(issuer)
                .claim("role", "REVIEWER")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(Instant.now()))
                .signWith(key())
                .compact();

        // when/then: 인증 실패 — 만료 없는 베어러 토큰은 fail-closed 로 거부한다
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("exp_없는_PORTAL_채널_토큰도_401")
    void portalTokenWithoutExpClaimReturns401() throws Exception {
        // 채널 무관 게이트임을 확인 — PORTAL 채널도 동일하게 거부되어야 한다.
        String token = Jwts.builder()
                .subject("alice")
                .issuer(issuer)
                .claim("role", "PORTAL_USER")
                .claim("channel", "PORTAL")
                .issuedAt(Date.from(Instant.now()))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/portal/datamart/videos").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("잘못된_서명의_JWT는_401_반환")
    void invalidSignatureReturns401() throws Exception {
        SecretKey otherKey = Keys.hmacShaKeyFor("another-secret-different-32bytes-min-len".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("user-1")
                .issuer(issuer)
                .claim("role", "REVIEWER")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(otherKey)
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
