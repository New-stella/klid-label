package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.StubControllers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
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

    @Autowired
    private UserRoleResolver userRoleResolver;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    /** 이 테스트 전용 사용자 번호 구간 — 시드·다른 테스트와 충돌 회피. */
    private static final long CTRL_USER_NO = 969_200_001L;
    private static final long DUP_USER_NO_A = 969_200_002L;
    private static final long DUP_USER_NO_B = 969_200_003L;
    private static final String CTRL_USER_ID = "ctrl-admin-969200001";
    private static final String DUP_USER_ID = "ctrl-dup-969200002";

    @BeforeEach
    void seedControlIngressRows() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanupControlIngressRows();
        // AC4: userId 유일 매칭 표본 — REVIEWER 역할(관리자 아님, 공유 DB 부트스트랩 오염 방지).
        insertUser(CTRL_USER_NO, CTRL_USER_ID);
        insertRole(CTRL_USER_NO, "REVIEWER");
        // AC5: 같은 USER_ID 를 가진 두 행 — 유일 제약이 없어 다중 매칭이 되는 상황.
        insertUser(DUP_USER_NO_A, DUP_USER_ID);
        insertUser(DUP_USER_NO_B, DUP_USER_ID);
        insertRole(DUP_USER_NO_A, "REVIEWER");
        insertRole(DUP_USER_NO_B, "REVIEWER");
    }

    @AfterEach
    void teardownControlIngressRows() {
        cleanupControlIngressRows();
    }

    private void insertUser(long userNo, String userId) {
        jdbc.update("INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USE_YN, REG_DT) "
                        + "VALUES (?, ?, ?, 'Y', ?)",
                userNo, userId, "ingress-" + userNo, LocalDateTime.now());
    }

    private void insertRole(long userNo, String roleCd) {
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, ?, ?)",
                userNo, roleCd, LocalDateTime.now());
    }

    private void cleanupControlIngressRows() {
        for (long userNo : new long[]{CTRL_USER_NO, DUP_USER_NO_A, DUP_USER_NO_B}) {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
            jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
            userRoleResolver.evict(userNo);
        }
    }

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

    @Test
    @DisplayName("iss_클레임이_없는_토큰은_게이트를_통과한다_관제_인계")
    void tokenWithoutIssuerPassesGate() throws Exception {
        // @design ADR-063 · UC-041 — 관제 인계 JWT 는 iss 가 없다. iss 를 아예 실지 않은 토큰이
        //   게이트를 통과하고(시크릿·exp 유지), sub=1 → REVIEWER 로 해석돼 보호 엔드포인트 200.
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("1")
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
    @DisplayName("iss가_있고_허용목록_밖이면_여전히_거부된다_회귀0")
    void tokenWithUnknownIssuerStillRejected() throws Exception {
        // 「iss 없음 허용」과 「아무 iss 허용」은 다르다 — 값이 있는데 허용목록 밖이면 거부.
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("1")
                .issuer("evil-issuer")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // 참고: blank iss("") 거부는 JwtIssuerValidatorTest(단위)가 고정한다 — jjwt 빌더는
    //   .issuer("") 를 라운드트립에서 null 로 정규화해(getIssuer()==null) 통합 레벨에서 blank
    //   토큰을 만들 수 없다. 실제 blank 는 검증기 단위 경로(isAllowed("")==false)로 닫힌다.

    @Test
    @DisplayName("관제_토큰_비숫자sub는_userId로_USER_ID조회되어_역할해석_200")
    void controlTokenNonNumericSubResolvesViaUserId() throws Exception {
        // @design ADR-063 · UC-041 — 실제 관제 토큰 모양: iss 없음 · sub 비숫자 · userId 클레임 보유.
        //   userId 가 LS_ACNT_USER.USER_ID 에 유일 매칭되면 그 사용자(REVIEWER)로 인가된다.
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("admin")
                .claim("userId", CTRL_USER_ID)
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
    @DisplayName("관제_토큰_userId가_다중매칭이면_fail_closed_403")
    void controlTokenAmbiguousUserIdFailsClosed() throws Exception {
        // USER_ID 에 유일 제약이 없어 같은 값이 두 행에 있다 → 어느 행인지 추측 금지(CWE-639) →
        //   무권한. 인증은 성립하되 역할이 없어 보호 엔드포인트 403.
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("admin")
                .claim("userId", DUP_USER_ID)
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관제_토큰_userId가_0건매칭이면_fail_closed_403")
    void controlTokenUnknownUserIdFailsClosed() throws Exception {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("admin")
                .claim("userId", "no-such-user-id-969299999")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
