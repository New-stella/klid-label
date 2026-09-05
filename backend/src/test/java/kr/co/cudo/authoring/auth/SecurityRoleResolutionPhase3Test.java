package kr.co.cudo.authoring.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 역할 분리 Phase 3 — 인가 역할을 JWT role 클레임이 아닌 LS_USER_ROLE 조회로 해석함을 검증한다.
 *
 * <p>JWT 서명/exp/issuer 검증은 기존 그대로이며, 검증 통과 후 권한 출처만 LS 로 바뀐다.
 * LS 시드는 {@code V9001__test_seed_user_roles.sql}(전체 테스트 공유) 가 제공한다
 * (1=REVIEWER, 100=WORKER).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class SecurityRoleResolutionPhase3Test {

    @Autowired
    private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    @Test
    @DisplayName("INTERNAL_사용자_역할이_LS_USER_ROLE에서_해석되어_인가된다")
    void internalRoleResolvedFromLs() throws Exception {
        // given: LS 시드상 sub=1 → REVIEWER (JWT role 클레임은 인가에 미사용)
        String token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        // when/then: REVIEWER 전용 manage API 접근 허용
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("관제역할만_실린_JWT여도_LS매핑있으면_REVIEWER_인가")
    void controlRoleClaimIgnoredLsWins() throws Exception {
        // given: JWT role=LEARN_MANAGER(관제 역할)이지만 채널 INTERNAL + sub=1 → LS=REVIEWER
        String token = JwtTestSupport.token(secret, "1", "LEARN_MANAGER", "INTERNAL", issuer, 60);
        // when/then: LS 역할(REVIEWER) 기준으로 인가되어 200
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("역할이_해석되지_않는_INTERNAL_사용자는_보호엔드포인트_403")
    void noLsRoleForbidden() throws Exception {
        // given: 역할 해석이 실패하는 사용자 — fail-closed.
        //   ★ADR-055 이후 <시드에 없는 숫자 sub> 는 첫 요청에 WORKER 로 자동 등록되므로 더 이상
        //     role=null 표본이 아니다. 그래서 770001 은 V9002 가 Role enum 밖 코드로 심어 둔다.
        //     이 시험이 지키는 것은 "매처가 role=null 을 막는가" 이지 "미배정 사용자가 존재하는가"
        //     가 아니다.
        String token = JwtTestSupport.token(secret, "770001", "REVIEWER", "INTERNAL", issuer, 60);
        // when/then: 무권한 → 403 (인증은 됐으나 역할 없음)
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비숫자_sub는_fail_closed_403")
    void nonNumericSubFailsClosed() throws Exception {
        // given: 비숫자 sub INTERNAL 토큰 — NumberFormatException 없이 무권한 처리
        String token = JwtTestSupport.token(secret, "not-a-number", "REVIEWER", "INTERNAL", issuer, 60);
        // when/then: 무권한 → 403
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PORTAL_채널은_LS없이_PORTAL_USER로_인가")
    void portalChannelResolvesWithoutLs() throws Exception {
        // given: 비숫자 sub PORTAL 토큰 — resolver 미호출, role=PORTAL_USER 고정
        String token = JwtTestSupport.token(secret, "portal-x", "PORTAL_USER", "PORTAL", issuer, 60);
        // when/then: 포털 API 접근 허용 (회귀)
        mockMvc.perform(get("/v1/portal/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
