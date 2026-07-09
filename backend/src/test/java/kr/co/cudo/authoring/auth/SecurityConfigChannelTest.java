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
 * R5-1 — 채널 기반 인가 (channel claim 격리).
 *
 * <p>INTERNAL 채널 전용 내부 API(영상/라벨/마킹/검수/관리 등)는 channel=INTERNAL 토큰만,
 * 포털 API(/v1/portal/**)는 channel=PORTAL 토큰만 허용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class SecurityConfigChannelTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    @Test
    @DisplayName("PORTAL_채널_토큰으로_내부_영상_API_호출시_403")
    void portalChannelForbiddenOnInternalVideos() throws Exception {
        // given: PORTAL 채널 토큰 (PORTAL_USER)
        String token = JwtTestSupport.token(secret, "portal-1", "PORTAL_USER", "PORTAL", issuer, 60);
        // when: 내부 전용 영상 API 호출 — then: 채널 격리로 403
        mockMvc.perform(get("/v1/videos").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PORTAL_채널_토큰으로_manage_API_호출시_403")
    void portalChannelForbiddenOnManage() throws Exception {
        // given: PORTAL 채널 토큰
        String token = JwtTestSupport.token(secret, "portal-2", "PORTAL_USER", "PORTAL", issuer, 60);
        // when/then: 내부 관리 API 403
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PORTAL_채널_토큰으로_포털_API_호출시_접근_허용")
    void portalChannelAllowedOnPortal() throws Exception {
        // given: PORTAL 채널 토큰
        String token = JwtTestSupport.token(secret, "portal-3", "PORTAL_USER", "PORTAL", issuer, 60);
        // when/then: 포털 API 는 통과 (200)
        mockMvc.perform(get("/v1/portal/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("INTERNAL_채널_토큰으로_포털_API_호출시_403")
    void internalChannelForbiddenOnPortal() throws Exception {
        // given: INTERNAL 채널 토큰 (REVIEWER)
        String token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        // when/then: 포털 전용 API 는 채널 격리로 403
        mockMvc.perform(get("/v1/portal/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("INTERNAL_채널_REVIEWER_토큰으로_내부_관리_API_접근_허용")
    void internalChannelAllowedOnManage() throws Exception {
        // given: INTERNAL 채널 REVIEWER
        String token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        // when/then: 내부 관리 API 200 유지
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("channel_클레임_없는_토큰은_INTERNAL_간주되어_내부_API_접근_허용")
    void noChannelClaimTreatedAsInternal() throws Exception {
        // given: channel 클레임 없는 REVIEWER 토큰 (기존 내부 사용자 토큰 호환)
        String token = JwtTestSupport.token(secret, "1", "REVIEWER", null, issuer, 60);
        // when/then: fail-closed 정책상 무클레임=INTERNAL → 내부 API 접근 허용
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
