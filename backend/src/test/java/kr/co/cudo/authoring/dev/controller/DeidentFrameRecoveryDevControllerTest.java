package kr.co.cudo.authoring.dev.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dev.dto.DeidentFrameRecoveryResponse;
import kr.co.cudo.authoring.dev.service.DeidentFrameRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 레거시 비식별 프레임 복구 API 슬라이스 — <b>실제 Security 체인</b>을 태워 인증/인가와 입력 검증을
 * 고정한다. 복구 로직은 {@link MockBean} 으로 격리하고(실 동작은
 * {@code DeidentFrameRecoveryServiceTest}·{@code DeidentFrameRecoveryQueryIT} 가 검증) 여기서는
 * ①dry-run 이 복구를 <b>수행하지 않는</b> 것 ②실행 API 의 단건/전체 분기 ③미인증 401 · 비REVIEWER 403
 * ④rawSn 형식 위반 400 ⑤응답에 경로·PII 가 실리지 않는 것을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DeidentFrameRecoveryDevControllerTest {

    private static final String TARGETS_URL = "/v1/dev/deident-frame-recovery/targets";
    private static final String RUNS_URL = "/v1/dev/deident-frame-recovery/runs";

    @Autowired private MockMvc mockMvc;
    @MockBean private DeidentFrameRecoveryService recoveryService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    private DeidentFrameRecoveryResponse response(boolean dryRun, String result, String reason) {
        return new DeidentFrameRecoveryResponse(dryRun, 1, 3L, dryRun ? 0 : 1, dryRun ? 0 : 0,
                dryRun ? 0 : 21, dryRun ? 0 : 21,
                List.of(new DeidentFrameRecoveryResponse.Item(12L, result, reason, 21, 21, 21, 21, 21)));
    }

    @Test
    @DisplayName("대상조회는_복구를_수행하지_않고_예상결과만_반환한다")
    void 대상조회는_복구를_수행하지_않는다() throws Exception {
        given(recoveryService.preview(null)).willReturn(response(true, "PLANNED", null));

        mockMvc.perform(get(TARGETS_URL).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.restoredVideoFrameNoCount").value(0))
                .andExpect(jsonPath("$.data.items[0].result").value("PLANNED"));

        verify(recoveryService, never()).recover(any());
    }

    @Test
    @DisplayName("단건_지정_복구는_그_영상만_처리한다")
    void 단건_지정_복구() throws Exception {
        given(recoveryService.recover(12L)).willReturn(response(false, "RECOVERED", null));

        mockMvc.perform(post(RUNS_URL)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSn\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rawSn").value(12))
                .andExpect(jsonPath("$.data.items[0].result").value("RECOVERED"));

        verify(recoveryService).recover(eq(12L));
    }

    @Test
    @DisplayName("본문없이_호출하면_전체_대상을_처리한다")
    void 전체_대상_복구() throws Exception {
        given(recoveryService.recover(null)).willReturn(response(false, "RECOVERED", null));

        mockMvc.perform(post(RUNS_URL).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        verify(recoveryService).recover(null);
    }

    @Test
    @DisplayName("rawSn이_1미만이면_400이고_복구가_실행되지_않는다")
    void rawSn_형식위반은_400() throws Exception {
        mockMvc.perform(get(TARGETS_URL).param("rawSn", "0")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(RUNS_URL)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSn\":0}"))
                .andExpect(status().isBadRequest());

        verify(recoveryService, never()).preview(any());
        verify(recoveryService, never()).recover(any());
    }

    @Test
    @DisplayName("미인증이면_401이다")
    void 미인증은_401() throws Exception {
        mockMvc.perform(get(TARGETS_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(RUNS_URL)).andExpect(status().isUnauthorized());
        verify(recoveryService, never()).preview(any());
        verify(recoveryService, never()).recover(any());
    }

    @Test
    @DisplayName("REVIEWER가_아니면_403이다")
    void 비검수자는_403() throws Exception {
        mockMvc.perform(get(TARGETS_URL).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(RUNS_URL).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
        verify(recoveryService, never()).preview(any());
        verify(recoveryService, never()).recover(any());
    }

    @Test
    @DisplayName("복구가_진행_중이면_409를_돌려준다")
    void 진행중_재요청은_409() throws Exception {
        given(recoveryService.recover(12L)).willThrow(new CustomException(
                ErrorCode.CONFLICT, "이미 복구가 진행 중입니다. 완료 후 다시 시도해 주세요."));

        // 서비스의 CONFLICT 가 실제로 HTTP 409 로 나가는지 고정한다(동시 실행 가드의 계약면).
        mockMvc.perform(post(RUNS_URL)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSn\":12}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("응답에_경로나_파일명이_실리지_않는다")
    void 응답에_경로가_없다() throws Exception {
        given(recoveryService.recover(null))
                .willReturn(response(false, "SKIPPED", "MARK_COUNT_MISMATCH"));

        String body = mockMvc.perform(post(RUNS_URL).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        // 사유는 서버가 고른 열거값뿐 — 경로 구분자·확장자가 응답에 섞이면 PII 노출 표면이 열린다.
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain(".mp4", ".jpg", "/nas", "storage/");
    }
}
