package kr.co.cudo.authoring.augment;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AugmentRequestControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private ObjectMapper objectMapper;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        statusRepository.deleteAll();
    }

    private void seedStatus(Long rawDataId, String dataSttsCd) {
        LsRawDataStatus status = LsRawDataStatus.initial(rawDataId);
        status.transitionTo(dataSttsCd);
        statusRepository.saveAndFlush(status);
    }

    @Test
    @DisplayName("AugmentRequestController_WORKER_권한으로_요청시_403")
    void workerForbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(9001L),
                "types", List.of("WINTER")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AugmentRequestController_유효한_요청_바디로_200_응답")
    void validRequestReturns200() throws Exception {
        seedStatus(8001L, LsRawDataStatus.STTS_APPROVED);
        seedStatus(8002L, LsRawDataStatus.STTS_APPROVED);

        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8001L, 8002L),
                "types", List.of("WINTER", "NIGHT")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").isNumber())
                .andExpect(jsonPath("$.data.videoCount").value(2))
                .andExpect(jsonPath("$.data.typeCount").value(2));
    }

    @Test
    @DisplayName("AugmentRequestController_videoIds_빈_배열_요청시_400")
    void emptyVideoIdsReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(),
                "types", List.of("WINTER")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("AugmentRequestController_types에_잘못된_값_요청시_400")
    void invalidTypeReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8101L),
                "types", List.of("INVALID_TYPE")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("증강요청_RESOLUTION_타입_400_거부_allowlist_WINTER_NIGHT_RAIN")
    void resolutionTypeRejected400() throws Exception {
        // 해상도(RESOLUTION)는 외부 증강 위탁 대상이 아님(저작도구 직접 수행) — allowlist 밖이므로 400.
        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8301L),
                "types", List.of("RESOLUTION")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("증강요청_WINTER_단일_정상_200")
    void winterOnlyReturns200() throws Exception {
        seedStatus(8401L, LsRawDataStatus.STTS_APPROVED);

        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8401L),
                "types", List.of("WINTER")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.typeCount").value(1));
    }

    @Test
    @DisplayName("AugmentRequestController_미검수_영상_포함_요청시_400_blockedVideoIds_포함")
    void notReviewedReturns400WithBlockedIds() throws Exception {
        seedStatus(8201L, LsRawDataStatus.STTS_APPROVED);
        seedStatus(8202L, LsRawDataStatus.STTS_IN_REVIEW);

        String body = objectMapper.writeValueAsString(Map.of(
                "videoIds", List.of(8201L, 8202L),
                "types", List.of("WINTER")
        ));

        mockMvc.perform(post("/v1/augments/request")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("NOT_REVIEWED"))
                .andExpect(jsonPath("$.data.blockedVideoIds[0]").value(8202));
    }
}
