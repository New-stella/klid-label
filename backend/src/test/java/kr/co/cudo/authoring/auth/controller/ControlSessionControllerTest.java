package kr.co.cudo.authoring.auth.controller;

import kr.co.cudo.authoring.auth.service.ControlSessionRelayService;
import kr.co.cudo.authoring.common.client.ControlAccountClient;
import kr.co.cudo.authoring.common.client.ControlAccountClient.RefreshResult;
import kr.co.cudo.authoring.common.client.ControlAccountClient.RefreshResult.Outcome;
import kr.co.cudo.authoring.common.config.DeployFlavor;
import kr.co.cudo.authoring.common.config.DeployFlavorResolver;
import kr.co.cudo.authoring.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관제 세션 중계 창구 계약 — 응답 코드·검증·Bearer 판정·포털 향 404 (@design API-247 · API-246).
 *
 * <p>보안 필터 체인 없이 컨트롤러·서비스·전역 예외처리만 조립한다. 필터를 거친 end-to-end(로그인 검사 없음·
 * 통지 토큰 미적재)는 {@code ControlSessionRelayIT} 가 따로 고정한다.
 */
class ControlSessionControllerTest {

    private ControlAccountClient client;

    @BeforeEach
    void setUp() {
        client = mock(ControlAccountClient.class);
    }

    private MockMvc mvc(DeployFlavor flavor) {
        ControlSessionController controller = new ControlSessionController(
                new ControlSessionRelayService(client), DeployFlavorResolver.of(flavor));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String body(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    // ─────────────────────────────── 갱신 ───────────────────────────────

    @Test
    @DisplayName("★갱신_성공은_201_에_관제_토큰쌍을_그대로_싣는다")
    void refreshSuccess201() throws Exception {
        when(client.refresh("R-IN")).thenReturn(new RefreshResult(Outcome.SUCCESS, "NEW-S", "NEW-R"));

        mvc(DeployFlavor.CONTROL).perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON).content(body("R-IN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionToken").value("NEW-S"))
                .andExpect(jsonPath("$.data.refreshToken").value("NEW-R"))
                .andExpect(jsonPath("$.errorCode").isEmpty());
        verify(client).refresh("R-IN");
    }

    @Test
    @DisplayName("★새_refresh_토큰이_없어도_201_이며_refreshToken_키는_null_로_남는다_생략하지_않는다")
    void refreshSuccessWithoutNewRefreshToken201() throws Exception {
        // API-247 v4 — 201 의 refreshToken 은 nullable 이다. 브라우저는 키 존재를 전제로
        // 저장 형식의 refresh 칸을 비우므로, 키를 생략하면 「응답이 깨졌다」로 읽힌다.
        when(client.refresh("R-IN")).thenReturn(new RefreshResult(Outcome.SUCCESS, "NEW-S", null));

        mvc(DeployFlavor.CONTROL).perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON).content(body("R-IN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionToken").value("NEW-S"))
                .andExpect(jsonPath("$.data.refreshToken").isEmpty())
                // ★ 이 저장소에는 전역 NON_NULL 이 없다 — 키가 남는지는 원문으로 못박는다.
                .andExpect(content().string(containsString("\"refreshToken\":null")));
    }

    @Test
    @DisplayName("★관제_거절은_401_CONTROL_SESSION_REJECTED_이며_관제_문구를_싣지_않는다")
    void refreshRejected401() throws Exception {
        when(client.refresh(anyString())).thenReturn(new RefreshResult(Outcome.REJECTED, null, null));

        mvc(DeployFlavor.CONTROL).perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON).content(body("R-IN")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("CONTROL_SESSION_REJECTED"))
                .andExpect(jsonPath("$.message").value("관제 세션이 만료되었습니다. 다시 로그인해 주세요."))
                .andExpect(content().string(not(containsString("리프레시"))));
    }

    @Test
    @DisplayName("★일시장애는_503_CONTROL_SESSION_UNAVAILABLE_이다")
    void refreshUnavailable503() throws Exception {
        when(client.refresh(anyString())).thenReturn(new RefreshResult(Outcome.UNAVAILABLE, null, null));

        mvc(DeployFlavor.CONTROL).perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON).content(body("R-IN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CONTROL_SESSION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("관제 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"refreshToken\":\"\"}", "{\"refreshToken\":\"   \"}", "{\"refreshToken\":null}"})
    @DisplayName("refreshToken_누락_공백은_400_이며_관제를_부르지_않는다")
    void refreshInvalid400(String json) throws Exception {
        mvc(DeployFlavor.CONTROL).perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("refreshToken_4097자는_400_4096자는_통과한다_경계")
    void refreshLengthBoundary() throws Exception {
        String over = "a".repeat(4097);
        mvc(DeployFlavor.CONTROL).perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON).content(body(over)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(over))));
        verifyNoInteractions(client);

        String max = "a".repeat(4096);
        when(client.refresh(max)).thenReturn(new RefreshResult(Outcome.SUCCESS, "S", "R"));
        mvc(DeployFlavor.CONTROL).perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON).content(body(max)))
                .andExpect(status().isCreated());
    }

    // ─────────────────────────────── 로그아웃 ───────────────────────────────

    @Test
    @DisplayName("★Bearer_토큰을_관제로_넘기고_204")
    void logoutRelaysBearer204() throws Exception {
        when(client.logout("AT-1")).thenReturn(true);

        mvc(DeployFlavor.CONTROL).perform(delete("/v1/auth/control-session")
                        .header("Authorization", "Bearer AT-1"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(client).logout("AT-1");
    }

    @Test
    @DisplayName("헤더가_없으면_관제를_부르지_않고_204")
    void logoutWithoutHeader204() throws Exception {
        mvc(DeployFlavor.CONTROL).perform(delete("/v1/auth/control-session"))
                .andExpect(status().isNoContent());
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic dXNlcjpwdw==", "Bearer    ", "Bearer", "bearer AT-1", "AT-1"})
    @DisplayName("★Bearer_형식이_아니거나_비면_관제를_부르지_않고_204_fail_closed")
    void logoutNonBearerNoCall(String header) throws Exception {
        mvc(DeployFlavor.CONTROL).perform(delete("/v1/auth/control-session").header("Authorization", header))
                .andExpect(status().isNoContent());
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("관제_로그아웃이_실패하거나_예외가_나도_204")
    void logoutFailureStill204() throws Exception {
        when(client.logout("AT-1")).thenReturn(false);
        mvc(DeployFlavor.CONTROL).perform(delete("/v1/auth/control-session")
                        .header("Authorization", "Bearer AT-1"))
                .andExpect(status().isNoContent());

        doThrow(new IllegalStateException("boom")).when(client).logout("AT-2");
        mvc(DeployFlavor.CONTROL).perform(delete("/v1/auth/control-session")
                        .header("Authorization", "Bearer AT-2"))
                .andExpect(status().isNoContent());
    }

    // ─────────────────────────────── ★ 포털 향 404 ───────────────────────────────

    @Test
    @DisplayName("★포털_향_배포본에서는_갱신_창구가_404_이며_관제를_부르지_않는다")
    void portalRefresh404() throws Exception {
        mvc(DeployFlavor.PORTAL).perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON).content(body("R-IN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 API를 찾을 수 없습니다."));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("★포털_향에서는_잘못된_본문도_400_이_아니라_404_다_창구의_존재를_드러내지_않는다")
    void portalInvalidBodyStill404() throws Exception {
        mvc(DeployFlavor.PORTAL).perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isNotFound());
        mvc(DeployFlavor.PORTAL).perform(post("/v1/auth/control-tokens")
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("★포털_향_배포본에서는_로그아웃_창구가_404_이며_관제를_부르지_않는다")
    void portalLogout404() throws Exception {
        mvc(DeployFlavor.PORTAL).perform(delete("/v1/auth/control-session")
                        .header("Authorization", "Bearer AT-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 API를 찾을 수 없습니다."));
        verifyNoInteractions(client);
    }
}
