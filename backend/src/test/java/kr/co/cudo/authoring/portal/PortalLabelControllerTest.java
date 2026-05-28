package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloRequest;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.portal.dto.PortalAutolabelRequest;
import kr.co.cudo.authoring.portal.entity.LsPortalUserVideo;
import kr.co.cudo.authoring.portal.repository.PortalUserVideoRepository;
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
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    @Autowired private PortalUserVideoRepository portalRepo;

    @MockBean private AiServerClient aiServerClient;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String alicePortalToken;
    private String bobPortalToken;
    private String reviewerInternalToken;

    private Long aliceVideoSn;

    @BeforeEach
    void setUp() {
        alicePortalToken      = JwtTestSupport.token(secret, "alice", "PORTAL_USER", "PORTAL",   issuer, 60);
        bobPortalToken        = JwtTestSupport.token(secret, "bob",   "PORTAL_USER", "PORTAL",   issuer, 60);
        reviewerInternalToken = JwtTestSupport.token(secret, "1",     "REVIEWER",    "INTERNAL", issuer, 60);

        portalRepo.deleteAll();
        LsPortalUserVideo aliceVideo = portalRepo.save(LsPortalUserVideo.create(
                "alice", "v.mp4", "/tmp/alice/v.mp4", 100L, "video/mp4"));
        aliceVideoSn = aliceVideo.getPortalVideoSn();
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
    @DisplayName("간편_라벨링_YOLO_체험_정상_응답")
    void autolabelReturnsYoloResult() throws Exception {
        // ai-server mock — detection 1건
        YoloResponse stub = new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, 50.0, 50.0), 0.85)
        ));
        when(aiServerClient.predictYolo(any(YoloRequest.class))).thenReturn(Mono.just(stub));

        PortalAutolabelRequest req = new PortalAutolabelRequest(aliceVideoSn, "aGVsbG8=");

        mockMvc.perform(post("/v1/portal/autolabel")
                        .header("Authorization", "Bearer " + alicePortalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.detections.length()").value(1))
                .andExpect(jsonPath("$.data.detections[0].label").value("person"));
    }

    @Test
    @DisplayName("간편_라벨링_다른_사용자_영상_접근시_404_NOT_FOUND")
    void autolabelOtherUsersVideoBlocked() throws Exception {
        PortalAutolabelRequest req = new PortalAutolabelRequest(aliceVideoSn, "aGVsbG8=");

        // bob 토큰으로 alice 의 portalVideoSn 접근
        mockMvc.perform(post("/v1/portal/autolabel")
                        .header("Authorization", "Bearer " + bobPortalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
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

    @Test
    @DisplayName("PORTAL_USER_본인_업로드_목록_조회_정상_배열_응답")
    void listMyUploads() throws Exception {
        // FE 계약: data 는 배열 (Page 래퍼 미사용)
        mockMvc.perform(get("/v1/portal/uploads")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("v.mp4"));

        // bob 은 빈 배열
        mockMvc.perform(get("/v1/portal/uploads")
                        .header("Authorization", "Bearer " + bobPortalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
