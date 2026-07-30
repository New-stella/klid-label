package kr.co.cudo.authoring.dataset.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaBackfillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 촬영환경 파생값 정정 백필 수동 트리거 API 슬라이스 — <b>실제 Security 체인</b>을 태워 인증/인가
 * 게이팅을 고정한다({@code @SpringBootTest} + {@code @AutoConfigureMockMvc}).
 *
 * <p>백필 로직은 {@link MockBean} 으로 격리하고, 여기서는 ①dry-run 조회가 정정을 <b>수행하지 않는</b>
 * 것 ②실행 API 가 백필을 호출하고 결과 요약을 매핑하는 것 ③미인증 401 / 비REVIEWER 403 을 본다.
 * 실 DB 정정·멱등은 {@code DatasetVideoMetaEnvCorrectionTriggerIT} 가 별도로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DatasetVideoMetaBackfillDevControllerTest {

    private static final String TARGETS_URL = "/v1/dev/dataset-video-meta/shooting-env-correction-targets";
    private static final String CORRECTIONS_URL = "/v1/dev/dataset-video-meta/shooting-env-corrections";

    @Autowired private MockMvc mockMvc;
    @MockBean private DatasetVideoMetaBackfillService backfillService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("대상조회는_정정을_수행하지_않고_건수만_반환한다")
    void 대상조회는_정정을_수행하지_않고_건수만_반환한다() throws Exception {
        // given: 정정 대상 7건
        given(backfillService.countDerivedShootingEnvCorrectionTargets()).willReturn(7L);

        // when & then: 건수만 응답하고 정정(재동결·통지)은 한 건도 실행되지 않는다
        mockMvc.perform(get(TARGETS_URL).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetCount").value(7));

        verify(backfillService, never()).correctDerivedShootingEnvironment();
    }

    @Test
    @DisplayName("실행API는_백필을_호출하고_정정_잔여건수를_반환한다")
    void 실행API는_백필을_호출하고_정정_잔여건수를_반환한다() throws Exception {
        // given: 상한(max-per-run)에 걸려 3건만 정정되고 5건이 남는 상황
        given(backfillService.correctDerivedShootingEnvironment()).willReturn(3);
        given(backfillService.countDerivedShootingEnvCorrectionTargets()).willReturn(5L);

        // when & then: 잔여 건수를 응답에 담아 재호출을 유도한다(상한 우회 옵션 없음)
        mockMvc.perform(post(CORRECTIONS_URL).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corrected").value(3))
                .andExpect(jsonPath("$.data.remaining").value(5))
                .andExpect(jsonPath("$.data.completed").value(false));

        verify(backfillService).correctDerivedShootingEnvironment();
    }

    @Test
    @DisplayName("잔여가_0이면_완료로_표기된다")
    void 잔여가_0이면_완료로_표기된다() throws Exception {
        // given
        given(backfillService.correctDerivedShootingEnvironment()).willReturn(2);
        given(backfillService.countDerivedShootingEnvCorrectionTargets()).willReturn(0L);

        // when & then
        mockMvc.perform(post(CORRECTIONS_URL).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remaining").value(0))
                .andExpect(jsonPath("$.data.completed").value(true));
    }

    @Test
    @DisplayName("미인증_대상조회는_401이다")
    void 미인증_대상조회는_401이다() throws Exception {
        mockMvc.perform(get(TARGETS_URL)).andExpect(status().isUnauthorized());
        verify(backfillService, never()).correctDerivedShootingEnvironment();
    }

    @Test
    @DisplayName("미인증_실행은_401이고_정정이_수행되지_않는다")
    void 미인증_실행은_401이고_정정이_수행되지_않는다() throws Exception {
        mockMvc.perform(post(CORRECTIONS_URL)).andExpect(status().isUnauthorized());
        verify(backfillService, never()).correctDerivedShootingEnvironment();
    }

    @Test
    @DisplayName("REVIEWER가_아니면_대상조회도_실행도_403이다")
    void REVIEWER가_아니면_대상조회도_실행도_403이다() throws Exception {
        mockMvc.perform(get(TARGETS_URL).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(CORRECTIONS_URL).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());

        verify(backfillService, never()).correctDerivedShootingEnvironment();
    }
}
