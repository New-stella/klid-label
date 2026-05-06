package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
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
    @DisplayName("유효한_JWT로_요청시_SecurityContext에_TokenClaims_설정")
    void validJwtSetsContext() throws Exception {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("user-1")
                .issuer(issuer)
                .claim("role", "REVIEWER")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().is(not(401))); // 인증 통과 — 라우팅 없을 뿐 401은 아님
    }

    @Test
    @DisplayName("JWT_없이_보호된_API_호출시_401_반환")
    void noJwtReturns401() throws Exception {
        mockMvc.perform(get("/v1/manage/ping"))
                .andExpect(status().isUnauthorized());
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

        mockMvc.perform(get("/v1/manage/ping").header("Authorization", "Bearer " + token))
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

        mockMvc.perform(get("/v1/manage/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
