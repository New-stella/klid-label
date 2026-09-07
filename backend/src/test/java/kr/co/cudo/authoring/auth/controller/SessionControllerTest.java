package kr.co.cudo.authoring.auth.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    private UserRoleResolver userRoleResolver;

    /** 이름 조달 시험 표본 — 토큰이 이름을 싣지 않고 저작도구만 이름을 아는 사용자. */
    private static final long NAMED_IN_DB_ONLY = 969_820_001L;
    /** 이름 조달 시험 표본 — 토큰도 저작도구도 이름을 모르는 사용자. */
    private static final long NAMELESS_EVERYWHERE = 969_820_002L;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUpNameFixtures() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanupNameFixtures();
        // 저작도구만 이름을 아는 사용자 — USER_NM 은 NOT NULL 이라 빈 문자열이 기본값이다.
        jdbc.update("INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USE_YN, REG_DT)"
                        + " VALUES (?, ?, ?, 'Y', CURRENT_TIMESTAMP)",
                NAMED_IN_DB_ONLY, "sess-name-a", "저작도구가아는이름");
        jdbc.update("INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USE_YN, REG_DT)"
                        + " VALUES (?, ?, '', 'Y', CURRENT_TIMESTAMP)",
                NAMELESS_EVERYWHERE, "sess-name-b");
    }

    @AfterEach
    void tearDownNameFixtures() {
        cleanupNameFixtures();
    }

    private void cleanupNameFixtures() {
        for (long userNo : new long[]{NAMED_IN_DB_ONLY, NAMELESS_EVERYWHERE}) {
            // 진입 시 작업자 자동 등록이 남긴 역할 행까지 함께 지운다.
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
            jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
            userRoleResolver.evict(userNo);
        }
    }

    @Test
    @DisplayName("INTERNAL_채널_REVIEWER_토큰으로_me_API_호출시_사용자_정보_반환")
    void meReturnsClaims() throws Exception {
        // Phase 3 — role 은 LS_USER_ROLE(sub=1 → REVIEWER)에서 해석된다. JWT role 클레임이 아님.
        String token = JwtTestSupport.tokenWithName(
                secret, "1", "REVIEWER", "INTERNAL", issuer, "검수자A", 60);

        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value("1"))
                .andExpect(jsonPath("$.data.role").value("REVIEWER"))
                .andExpect(jsonPath("$.data.channel").value("INTERNAL"))
                .andExpect(jsonPath("$.data.name").value("검수자A"));
    }

    @Test
    @DisplayName("JWT_없이_me_API_호출시_401_반환")
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("만료된_JWT로_me_API_호출시_401_반환")
    void meWithExpiredTokenReturns401() throws Exception {
        String token = JwtTestSupport.expiredToken(secret, "user-1", "REVIEWER", "INTERNAL", issuer);

        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★토큰에_이름이_없어도_저작도구가_아는_이름이_실린다_토큰_클레임은_보조다")
    void meFallsBackToStoredNameWhenTokenCarriesNone() throws Exception {
        // ★API-006 — 화면 상단이 표시하는 이름의 진실원은 이 응답이고 인계 토큰의 이름 클레임은
        //   보조다. 구 구현은 토큰 클레임 하나만 봐서, 저작도구가 이름을 <알고 있는데도> 응답이
        //   비어 화면이 대체 표기("사용자")로 떨어졌다.
        //   ⇒ 보강 조회를 지우면 여기서 name 이 null 이 되어 RED 다.
        String token = JwtTestSupport.token(
                secret, String.valueOf(NAMED_IN_DB_ONLY), "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("저작도구가아는이름"));
    }

    @Test
    @DisplayName("★토큰의_이름이_저작도구_보관값보다_먼저다_보강조회가_기존값을_덮지_않는다")
    void mePrefersTokenNameWhenPresent() throws Exception {
        // 보강 조회는 <토큰에 이름이 없을 때만> 돈다. 이 시험이 없으면 보강을 무조건 도는 구현이
        //   통과해 진입 경로에 상시 쿼리가 붙는다.
        String token = JwtTestSupport.tokenWithName(
                secret, String.valueOf(NAMED_IN_DB_ONLY), "WORKER", "INTERNAL", issuer, "토큰이_준_이름", 60);

        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("토큰이_준_이름"));
    }

    @Test
    @DisplayName("★두_조달원_모두_이름을_모르면_null_이다_지어내지_않는다")
    void meLeavesNameNullWhenNeitherSourceKnowsIt() throws Exception {
        // 저작도구의 USER_NM 은 NOT NULL 이라 미상은 빈 문자열이다 — 그것을 이름으로 실어
        //   화면이 빈 이름을 그리게 하지 않는다. 대체 표기 판단은 화면이 한다.
        String token = JwtTestSupport.token(
                secret, String.valueOf(NAMELESS_EVERYWHERE), "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").isEmpty());
    }

    @Test
    @DisplayName("★포털_채널은_보강조회를_돌지_않는다_sub_와_USER_NO_매핑이_확인되지_않았다")
    void mePortalChannelDoesNotResolveStoredName() throws Exception {
        // ★CWE-639 — 포털 토큰의 sub 는 문자열 식별자로 쓰이고 사용자 마스터의 USER_NO 와의
        //   매핑이 확인되지 않았다. 숫자로 읽히는 포털 sub 를 조인하면 <남의 행 이름>을 표시하게
        //   된다. 채널 게이트를 지우면 여기서 "저작도구가아는이름" 이 나와 RED 다.
        String token = JwtTestSupport.token(
                secret, String.valueOf(NAMED_IN_DB_ONLY), "PORTAL_USER", "PORTAL", issuer, 60);

        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value("PORTAL"))
                .andExpect(jsonPath("$.data.name").isEmpty());
    }
}
