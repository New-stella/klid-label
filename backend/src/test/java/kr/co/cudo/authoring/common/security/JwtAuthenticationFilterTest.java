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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    private static final String CTRL_USER_ID = "ctrl-admin-969200001";
    /** 0건 매칭 프로비저닝 표본의 로그인 ID — 발급 userNo 는 시퀀스라 userId 로 걷어낸다. */
    private static final String PROV_USER_ID = "ctrl-prov-969299999";

    @BeforeEach
    void seedControlIngressRows() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanupControlIngressRows();
        // userId 유일 매칭 표본 — REVIEWER 역할(관리자 아님, 공유 DB 부트스트랩 오염 방지).
        insertUser(CTRL_USER_NO, CTRL_USER_ID);
        insertRole(CTRL_USER_NO, "REVIEWER");
        // ★다중매칭 표본은 더 이상 심지 않는다 — V32 의 부분 유니크 인덱스 uk_ls_acnt_user_user_id
        //   가 같은 USER_ID 두 행을 금지해 그 상태를 만들 수 없다. 다중매칭 fail-closed 는 이제
        //   구조적으로 도달 불가(방어 코드)이며, 조회 축약(findUserNoByUserId size!=1 → empty)과
        //   ControlUserProvisioner 의 size>=2 가드가 단위로 지킨다.
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
        for (long userNo : new long[]{CTRL_USER_NO}) {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
            jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
            userRoleResolver.evict(userNo);
        }
        // 프로비저닝 시험이 발급한 행(userNo 는 시퀀스라 예측 불가)은 userId 로 걷어낸다.
        for (Long userNo : jdbc.queryForList(
                "SELECT USER_NO FROM LS_ACNT_USER WHERE USER_ID = ?", Long.class, PROV_USER_ID)) {
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

    // ───────── 포털 전용 인계 헤더 inbound 수용 (@design INT-013) — HTTP 레벨 200/401 ─────────
    // 필터 단위 축(헤더 정규화·충돌·검증 공유)은 JwtFilterPortalHeaderIngressTest 가 고정한다.
    //   여기서는 "그래서 보호 엔드포인트가 실제로 200/401 을 내는가"만 본다 — 401 은 필터가 직접
    //   쓰는 것이 아니라 진입점이 내므로 통합 레벨에서만 확인된다.

    /** 유효한 INTERNAL 토큰(sub=1 → V9001 시드로 REVIEWER). */
    private String internalReviewerToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("1")
                .issuer(issuer)
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();
    }

    @Test
    @DisplayName("★x-access-token_헤더만_실린_요청이_보호엔드포인트_200 — 포털_인계_수용")
    void portalHeaderAloneReturns200() throws Exception {
        mockMvc.perform(get("/v1/manage/test").header("x-access-token", internalReviewerToken()))
                .andExpect(status().isOk())
                .andExpect(content().string("manage-ok"));
    }

    @Test
    @DisplayName("★두_헤더가_같은_토큰이면_200 — 무해한_중복은_막지_않는다")
    void identicalHeadersReturn200() throws Exception {
        String token = internalReviewerToken();
        mockMvc.perform(get("/v1/manage/test")
                        .header("Authorization", "Bearer " + token)
                        .header("x-access-token", token))
                .andExpect(status().isOk())
                .andExpect(content().string("manage-ok"));
    }

    @Test
    @DisplayName("★두_헤더의_토큰이_다르면_401 — 모호한_인증상태_거부")
    void conflictingHeadersReturn401() throws Exception {
        // 둘 다 <그 자체로는 유효>한 토큰이다(sub=1 · sub=770002). 그런데도 401 이어야 한다 —
        //   조용히 한쪽을 채택하면 의도치 않은 신원으로 요청이 처리된다.
        Instant now = Instant.now();
        String other = Jwts.builder()
                .subject("770002")
                .issuer(issuer)
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/test")
                        .header("Authorization", "Bearer " + internalReviewerToken())
                        .header("x-access-token", other))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★서명이_다른_토큰은_x-access-token으로_와도_401 — 검증_공유")
    void forgedSignatureOnPortalHeaderReturns401() throws Exception {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-secret-different-32bytes-min-len".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String forged = Jwts.builder()
                .subject("1")
                .issuer(issuer)
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(otherKey)
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("x-access-token", forged))
                .andExpect(status().isUnauthorized());
    }

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
    @DisplayName("관제_토큰_userId가_0건매칭이면_userNo가_발급되되_무역할이라_403")
    void controlTokenUnknownUserIdProvisionsButStaysUnauthorized() throws Exception {
        // @design ADR-063 ⑤ · UC-041 step5 — 0건 매칭은 fail-closed 가 아니라 <발급>이다:
        //   진입 순간 로컬 userNo 를 발급하되 역할은 부여하지 않아(role=null) 보호 엔드포인트는
        //   여전히 403 이다. 즉 인가는 닫히되 식별 레코드는 생긴다("0건이 곧 아무것도 안 만듦"이 아니다).
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("admin")
                .claim("userId", PROV_USER_ID)
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // 발급됐다 — disjoint 상위 시퀀스(≥ 9e9)로 정확히 1행, 역할은 없다.
        List<Long> nos = jdbc.queryForList(
                "SELECT USER_NO FROM LS_ACNT_USER WHERE USER_ID = ?", Long.class, PROV_USER_ID);
        assertThat(nos).hasSize(1);
        assertThat(nos.get(0)).isGreaterThanOrEqualTo(9_000_000_000L);
        assertThat(jdbc.queryForList(
                "SELECT ROLE_CD FROM LS_USER_ROLE WHERE USER_NO = ?", nos.get(0))).isEmpty();
    }
}
