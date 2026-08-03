package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 11 — 포털 라벨링/Channel 분리 테스트.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalLabelControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private io.github.resilience4j.ratelimiter.RateLimiterRegistry rateLimiterRegistry;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String alicePortalToken;
    private String reviewerInternalToken;

    @BeforeEach
    void setUp() {
        alicePortalToken      = JwtTestSupport.token(secret, "alice", "PORTAL_USER", "PORTAL",   issuer, 60);
        reviewerInternalToken = JwtTestSupport.token(secret, "1",     "REVIEWER",    "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("PORTAL_USER가_내부_API_users_호출시_403")
    void portalUserBlockedFromInternalApi() throws Exception {
        // /v1/users/workers 는 REVIEWER 전용 → PORTAL_USER 토큰은 403
        // /v1/manage/* 도 REVIEWER 전용 (Channel 분리 확인)
        mockMvc.perform(get("/v1/users/workers")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("버전관리_API는_PORTAL_USER에게_미노출_403")
    void versionApiHiddenFromPortalUser() throws Exception {
        // /v1/frames/{srcSn}/versions 는 INTERNAL 채널의 REVIEWER/WORKER 만.
        // PORTAL_USER 는 hasAnyRole('REVIEWER','WORKER') 미일치 → 403.
        mockMvc.perform(get("/v1/frames/9999/versions")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isForbidden());

        // sanity: REVIEWER 토큰은 (프레임 미존재이지만) 200/4xx 비-403 응답
        mockMvc.perform(get("/v1/frames/9999/versions")
                        .header("Authorization", "Bearer " + reviewerInternalToken))
                .andExpect(status().is(org.hamcrest.Matchers.not(org.hamcrest.Matchers.equalTo(403))));
    }

    // ─── R16: 포털 프레임 이미지 채널 격리 + 파라미터 검증 ───

    @Test
    @DisplayName("포털_프레임_이미지_INTERNAL_채널_토큰_403")
    void portalImageBlockedFromInternalChannel() throws Exception {
        // /v1/portal/frames/{srcSn}/image 는 PORTAL 채널 + PORTAL_USER 만.
        // INTERNAL 채널 REVIEWER 토큰은 채널 불일치 → 403.
        mockMvc.perform(get("/v1/portal/frames/9999/image")
                        .header("Authorization", "Bearer " + reviewerInternalToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("포털_프레임_이미지_PORTAL_토큰은_비403_프레임미존재시_404")
    void portalImageAllowedForPortalChannel() throws Exception {
        // PORTAL 채널 통과 — 프레임 미존재이므로 404 (비-403). 채널 격리가 막지 않음을 확인.
        mockMvc.perform(get("/v1/portal/frames/9999/image")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().is(org.hamcrest.Matchers.not(org.hamcrest.Matchers.equalTo(403))));
    }

    @Test
    @DisplayName("포털_데이터마트_라벨_rawSn_누락시_400")
    void datamartLabels_missingRawSn_400() throws Exception {
        mockMvc.perform(get("/v1/portal/datamart/labels")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("포털_데이터마트_라벨_rawSn_타입불일치시_400")
    void datamartLabels_invalidRawSn_400() throws Exception {
        mockMvc.perform(get("/v1/portal/datamart/labels")
                        .param("rawSn", "not-a-number")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("포털_사용자라벨_조회_rawSn_누락시_400")
    void userLabels_missingRawSn_400() throws Exception {
        mockMvc.perform(get("/v1/portal/user-labels")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isBadRequest());
    }

    // ─── Phase B: 포털 데이터마트 영상 목록 채널 격리 ───

    @Test
    @DisplayName("포털_데이터마트_영상목록_PORTAL_토큰은_200_빈목록")
    void datamartVideos_portalToken_ok() throws Exception {
        // PORTAL 채널 통과 — APPROVED 영상이 없으면 빈 페이지지만 비-403/비-401 (200).
        mockMvc.perform(get("/v1/portal/datamart/videos")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("포털_데이터마트_영상목록_미등록_정렬키는_500이_아니라_400")
    void datamartVideos_unknownSortKey_returns400() throws Exception {
        // A-ISSUE-61 — 수정 전에는 Pageable 이 LsDataRaw 조회로 직행해
        //   UnknownPathException(JPQL 원문 ERROR 로그 적재) → 500 이었다.
        String body = mockMvc.perform(get("/v1/portal/datamart/videos")
                        .param("sort", "secretField,desc")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // 입력 반사·내부 엔티티명·JPQL 원문 미노출 (CWE-209)
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("secretField")
                .doesNotContain("LsDataRaw")
                .doesNotContain("SELECT");
    }

    @Test
    @DisplayName("포털_데이터마트_영상목록_원본파일경로_정렬키_400")
    void datamartVideos_internalPathSortKey_returns400() throws Exception {
        // 응답에 노출하지 않는 내부 컬럼(원본 파일 경로)으로 정렬하던 경로 차단.
        mockMvc.perform(get("/v1/portal/datamart/videos")
                        .param("sort", "rawFilePathNm,asc")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("포털_데이터마트_영상목록_등록된_정렬키는_200")
    void datamartVideos_allowedSortKey_returns200() throws Exception {
        mockMvc.perform(get("/v1/portal/datamart/videos")
                        .param("sort", "capturedAt,desc")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("포털_데이터마트_영상목록_INTERNAL_채널_토큰_403")
    void datamartVideos_internalChannel_forbidden() throws Exception {
        // /v1/portal/** 는 PORTAL 채널 + PORTAL_USER 만. INTERNAL 채널 REVIEWER 토큰은 채널 불일치 → 403.
        mockMvc.perform(get("/v1/portal/datamart/videos")
                        .header("Authorization", "Bearer " + reviewerInternalToken))
                .andExpect(status().isForbidden());
    }

    // ─── 내부 SAM2/오토라벨 채널 경계 (포털 전용 SAM2 는 ADR-013 위반으로 제거 — PortalSam2RemovedTest) ───

    private static final String TRACK_BODY = """
            {"srcSn":9999,"trackId":"t-1","prevPolygon":[[10,10],[30,30],[10,30]],
             "label":"person","nextSrcSns":[10000]}""";

    @Test
    @DisplayName("포털_토큰_내부_sam2track_및_autolabel_여전히_403")
    void portalTokenBlockedFromInternalSam2AndAutolabel() throws Exception {
        // 내부 /v1/frames/** 는 INTERNAL 채널만 — PORTAL 토큰은 채널 불일치로 403 (persist 경로 물리 차단).
        mockMvc.perform(post("/v1/frames/9999/sam2-track")
                        .header("Authorization", "Bearer " + alicePortalToken)
                        .contentType("application/json").content(TRACK_BODY))
                .andExpect(status().isForbidden());

        // YOLO 오토라벨(파이프라인) 도 포털 403 유지.
        mockMvc.perform(post("/v1/frames/9999/autolabel")
                        .header("Authorization", "Bearer " + alicePortalToken)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    // ─── 저장 per-user RateLimiter 설정 배선 (CWE-770) ───

    @Test
    @DisplayName("포털_라벨저장_RateLimiter_config가_실제_설정에_존재한다")
    void userLabelRateLimiterConfigWired() {
        // config 이름이 application.yml 과 어긋나면 RateLimiterRegistry 가
        // ConfigurationNotFoundException 을 던져 <b>모든 저장 요청이 500</b> 이 된다.
        // 동작 검증(429)은 PortalLabelControllerRateLimitTest 가 담당하고, 여기서는 실제 설정 배선만 본다.
        io.github.resilience4j.ratelimiter.RateLimiter limiter =
                rateLimiterRegistry.rateLimiter("portalUserLabel-probe", "portalUserLabel");
        assertThat(limiter.getRateLimiterConfig().getLimitForPeriod()).isEqualTo(300);
        assertThat(limiter.getRateLimiterConfig().getTimeoutDuration()).isZero();
    }
}
